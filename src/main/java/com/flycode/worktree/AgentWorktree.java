package com.flycode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Lightweight worktree API for sub-agents. Does NOT touch global session
 * state (WorktreeSessionStore).
 */
public final class AgentWorktree {

    private static final Logger log = Logger.getLogger(AgentWorktree.class.getName());

    public record Result(String worktreePath, String worktreeBranch, String headCommit, String gitRoot) {}

    private AgentWorktree() {}

    /**
     * Creates or resumes a worktree for a sub-agent.
     */
    public static Result create(String slug, String repoRoot, List<String> symlinkDirs) throws Exception {
        SlugValidator.validate(slug);

        Path wtPath = Path.of(repoRoot, ".flycode", "worktrees", SlugValidator.flatten(slug));
        String branch = "worktree-" + SlugValidator.flatten(slug);

        // Fast-resume: check if worktree already exists
        if (Files.isDirectory(wtPath)) {
            // Bump mtime to prevent stale cleanup
            Files.setLastModifiedTime(wtPath, java.nio.file.attribute.FileTime.from(Instant.now()));
            String head = readHead(wtPath.toString());
            return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
        }

        Files.createDirectories(wtPath.getParent());

        ProcessBuilder pb = new ProcessBuilder("git", "worktree", "add", "-B", branch, wtPath.toString(), "HEAD");
        pb.directory(Path.of(repoRoot).toFile());
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "");
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished || proc.exitValue() != 0) {
            throw new IOException("Failed to create agent worktree: " + output);
        }

        PostCreationSetup.perform(repoRoot, wtPath.toString(), symlinkDirs);

        String head = readHead(wtPath.toString());
        return new Result(wtPath.toString(), branch, head != null ? head : "", repoRoot);
    }

    /**
     * Removes a worktree created by {@link #create}.
     */
    public static boolean remove(String worktreePath, String worktreeBranch, String gitRoot) {
        if (gitRoot == null || gitRoot.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "worktree", "remove", "--force", worktreePath);
            pb.directory(Path.of(gitRoot).toFile());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.getInputStream().readAllBytes();
            proc.waitFor(30, TimeUnit.SECONDS);
            if (proc.exitValue() != 0) return false;

            if (worktreeBranch != null && !worktreeBranch.isBlank()) {
                Thread.sleep(100); // wait for git lockfile release
                ProcessBuilder delBranch = new ProcessBuilder("git", "branch", "-D", worktreeBranch);
                delBranch.directory(Path.of(gitRoot).toFile());
                delBranch.redirectErrorStream(true);
                Process branchProc = delBranch.start();
                branchProc.getInputStream().readAllBytes();
                branchProc.waitFor(30, TimeUnit.SECONDS);
            }
            return true;
        } catch (Exception e) {
            log.fine("Failed to remove agent worktree: " + e.getMessage());
            return false;
        }
    }

    /**
     * Builds the notice text for sub-agents running in isolated worktrees.
     */
    public static String buildNotice(String parentCwd, String worktreeCwd) {
        return "You've inherited the conversation context above from a parent agent working in %s. "
                .formatted(parentCwd)
                + "You are operating in an isolated git worktree at %s — same repository, same relative "
                .formatted(worktreeCwd)
                + "file structure, separate working copy. Paths in the inherited context refer to the "
                + "parent's working directory; translate them to your worktree root. Re-read files before "
                + "editing if the parent may have modified them since they appear in the context. Your "
                + "changes stay in this worktree and will not affect the parent's files.";
    }

    // SHA-1（40 位）或 SHA-256（64 位）十六进制校验
    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{40}([0-9a-f]{24})?$");
    // ref 名称安全字符集：字母、数字、/、.、_、+、-、@
    private static final Pattern SAFE_REF = Pattern.compile("^[a-zA-Z0-9/._+@-]+$");

    /**
     * 纯文件系统 HEAD 读取，不启动 git 子进程。
     * <p>
     * Worktree 的 .git 是一个指向 gitdir 的指针文件（{@code gitdir: <path>}）。
     * 读取该指针定位到实际 git 目录，再解析 HEAD 获取 commit SHA。
     * 在大仓库中可节省 ~15ms 的进程创建开销。
     */
    private static String readHead(String worktreePath) {
        try {
            Path dotGit = Path.of(worktreePath, ".git");
            if (!Files.exists(dotGit)) return null;

            String gitDir;
            if (Files.isDirectory(dotGit)) {
                // 普通仓库：.git 是目录
                gitDir = dotGit.toString();
            } else {
                // Worktree：.git 是指针文件，内容为 "gitdir: <path>"
                String pointer = Files.readString(dotGit).strip();
                if (!pointer.startsWith("gitdir:")) return null;
                String rel = pointer.substring("gitdir:".length()).strip();
                Path resolved = Path.of(rel).isAbsolute()
                        ? Path.of(rel)
                        : Path.of(worktreePath, rel).normalize();
                gitDir = resolved.toString();
            }

            // 读取 HEAD 文件
            Path headFile = Path.of(gitDir, "HEAD");
            if (!Files.exists(headFile)) return null;
            String content = Files.readString(headFile).strip();

            if (content.startsWith("ref:")) {
                // 指向分支：解析 ref 到 SHA
                String ref = content.substring("ref:".length()).strip();
                if (!SAFE_REF.matcher(ref).matches() || ref.contains("..")) return null;
                return resolveRef(gitDir, ref);
            }
            // 分离 HEAD：直接是 SHA
            return SHA_PATTERN.matcher(content).matches() ? content : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 ref 到 commit SHA：先查松散 ref 文件，再查 packed-refs。
     * 对 worktree 会额外检查 commondir 指向的共享 git 目录。
     */
    private static String resolveRef(String gitDir, String ref) {
        try {
            // 尝试松散 ref 文件
            String sha = resolveRefInDir(gitDir, ref);
            if (sha != null) return sha;

            // Worktree 场景：ref 可能在 commondir 指向的共享目录中
            Path commonFile = Path.of(gitDir, "commondir");
            if (Files.exists(commonFile)) {
                String commonRel = Files.readString(commonFile).strip();
                String commonDir = Path.of(commonRel).isAbsolute()
                        ? commonRel
                        : Path.of(gitDir, commonRel).normalize().toString();
                if (!commonDir.equals(gitDir)) {
                    return resolveRefInDir(commonDir, ref);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 在单个 git 目录中解析 ref：先查松散文件，再查 packed-refs。
     */
    private static String resolveRefInDir(String dir, String ref) throws IOException {
        // 松散 ref 文件
        Path loosePath = Path.of(dir, ref);
        if (Files.exists(loosePath)) {
            String content = Files.readString(loosePath).strip();
            if (content.startsWith("ref:")) {
                // 符号引用链
                String target = content.substring("ref:".length()).strip();
                if (!SAFE_REF.matcher(target).matches() || target.contains("..")) return null;
                return resolveRef(dir, target);
            }
            return SHA_PATTERN.matcher(content).matches() ? content : null;
        }

        // packed-refs 回退
        Path packed = Path.of(dir, "packed-refs");
        if (!Files.exists(packed)) return null;
        for (String line : Files.readAllLines(packed)) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("^")) continue;
            int sp = line.indexOf(' ');
            if (sp == -1) continue;
            if (line.substring(sp + 1).equals(ref)) {
                String sha = line.substring(0, sp);
                return SHA_PATTERN.matcher(sha).matches() ? sha : null;
            }
        }
        return null;
    }
}
