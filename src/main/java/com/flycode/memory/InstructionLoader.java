package com.flycode.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 加载项目和用户级别的指令文件（FLYCODE.md / AGENTS.md），支持 @include 递归展开。
 *
 * <p>发现顺序（越靠后优先级越高，模型注意力优先关注后出现的内容）：
 * <ol>
 *   <li>用户全局：~/.flycode/FLYCODE.md、~/.flycode/AGENTS.md</li>
 *   <li>项目级：从 git root 沿目录树向下到 workDir，每个目录的 FLYCODE.md、
 *       AGENTS.md 和 .flycode/FLYCODE.md</li>
 *   <li>workDir/FLYCODE.local.md（私有本地覆盖）</li>
 * </ol>
 *
 * <p>@include 指令：
 * <ul>
 *   <li>@./relative/path、@~/home/path 或 @/absolute/path</li>
 *   <li>相对路径基于包含文件所在目录解析</li>
 *   <li>代码块内跳过</li>
 *   <li>防循环（同一绝对路径不会被包含两次）</li>
 * </ul>
 */
public final class InstructionLoader {

    private InstructionLoader() {}

    /** @include 最大嵌套深度 */
    public static final int MAX_INCLUDE_DEPTH = 5;

    /** 一个已加载的指令文件源 */
    public record InstructionSource(String path, String content) {}

    /**
     * 加载并拼接所有发现的指令文件，返回合并后的文本。
     * 每个文件内容前加上 "Contents of &lt;path&gt;:" 标签，文件间用分隔线分隔。
     */
    public static String loadInstructions(String workDir) {
        List<InstructionSource> sources = discoverInstructions(workDir);
        if (sources.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        Path workPath = Path.of(workDir);
        for (var s : sources) {
            // 尽量显示相对路径，更简洁
            String label = s.path();
            try {
                Path rel = workPath.relativize(Path.of(s.path()));
                if (!rel.toString().startsWith("..")) {
                    label = rel.toString();
                }
            } catch (IllegalArgumentException ignored) {}
            parts.add("Contents of %s:\n\n%s".formatted(label, stripTrailingNewlines(s.content())));
        }
        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 按优先级顺序（低到高）发现指令文件，返回加载后的源列表。
     */
    static List<InstructionSource> discoverInstructions(String workDir) {
        var sources = new ArrayList<InstructionSource>();
        var seen = new HashSet<String>();

        // 1. 用户全局指令
        String home = System.getProperty("user.home");
        if (home != null && !home.isEmpty()) {
            addSource(sources, seen, Path.of(home, ".flycode", "FLYCODE.md"));
            addSource(sources, seen, Path.of(home, ".flycode", "AGENTS.md"));
        }

        // 2. 项目级：从 git root 到 workDir 的每一级目录
        for (String dir : projectInstructionDirs(workDir)) {
            addSource(sources, seen, Path.of(dir, "FLYCODE.md"));
            addSource(sources, seen, Path.of(dir, "AGENTS.md"));
            // .flycode/ 下的同名文件：想让指令进 .gitignore 的项目放这里
            addSource(sources, seen, Path.of(dir, ".flycode", "FLYCODE.md"));
        }

        // 3. 本地私有覆盖
        addSource(sources, seen, Path.of(workDir, "FLYCODE.local.md"));

        return sources;
    }

    /**
     * 尝试读取指定路径的文件，成功则加入列表（含 @include 展开）。
     * 已读取过的路径不会重复加入（防循环）。
     */
    private static void addSource(List<InstructionSource> out, Set<String> seen, Path path) {
        String abs;
        try {
            abs = path.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return;
        }
        if (seen.contains(abs)) {
            return;
        }
        String content;
        try {
            content = Files.readString(Path.of(abs));
        } catch (IOException e) {
            return;
        }
        seen.add(abs);
        // 展开 @include 指令
        String expanded = expandIncludes(content, Path.of(abs).getParent().toString(), seen, 0);
        out.add(new InstructionSource(abs, expanded));
    }

    /**
     * 递归展开文本中的 @include 指令。
     * 在代码块（```）内的 @include 不会被展开。
     * 同一路径不会被重复包含（防循环）。
     */
    static String expandIncludes(String content, String baseDir, Set<String> seen, int depth) {
        if (depth > MAX_INCLUDE_DEPTH) {
            return content;
        }
        var out = new StringBuilder();
        boolean inCode = false;
        try (var reader = new BufferedReader(new StringReader(content))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                // 检测代码块边界
                if (trimmed.startsWith("```")) {
                    inCode = !inCode;
                    out.append(line).append('\n');
                    continue;
                }
                // 代码块外才处理 @include
                if (!inCode) {
                    String includePath = parseInclude(trimmed);
                    if (includePath != null) {
                        String resolved = resolveInclude(includePath, baseDir);
                        if (resolved != null && !resolved.isEmpty()) {
                            try {
                                String absPath = Path.of(resolved).toAbsolutePath().normalize().toString();
                                if (!seen.contains(absPath)) {
                                    String data = Files.readString(Path.of(absPath));
                                    seen.add(absPath);
                                    out.append("<!-- included from %s -->\n".formatted(includePath));
                                    out.append(expandIncludes(data,
                                            Path.of(absPath).getParent().toString(), seen, depth + 1));
                                    out.append('\n');
                                    continue;
                                }
                            } catch (IOException ignored) {
                                // 包含失败时保留原始行，让用户注意到
                            }
                        }
                    }
                }
                out.append(line).append('\n');
            }
        } catch (IOException ignored) {
            return content;
        }
        return out.toString();
    }

    /**
     * 解析 @include 行。仅识别以下模式：
     * @./relative/path、@../relative/path、@~/home/path、@/absolute/path
     * 其它 @token（如 @username）返回 null，避免误判。
     */
    static String parseInclude(String trimmed) {
        if (!trimmed.startsWith("@") || trimmed.startsWith("@@")) {
            return null;
        }
        String rest = trimmed.substring(1);
        if (rest.isEmpty()) {
            return null;
        }
        // 含空白符的不是路径
        if (rest.contains(" ") || rest.contains("\t")) {
            return null;
        }
        // 仅识别明确的路径前缀
        if (rest.startsWith("./") || rest.startsWith("../")
                || rest.startsWith("~/") || rest.startsWith("/")) {
            return rest;
        }
        return null;
    }

    /**
     * 将 @include 路径解析为绝对路径。
     * ~/path 展开为 $HOME/path，相对路径基于 baseDir 解析。
     */
    static String resolveInclude(String p, String baseDir) {
        if (p.startsWith("~/")) {
            String home = System.getProperty("user.home");
            if (home == null || home.isEmpty()) {
                return "";
            }
            return Path.of(home, p.substring(2)).toString();
        }
        if (Path.of(p).isAbsolute()) {
            return p;
        }
        return Path.of(baseDir, p).toString();
    }

    /**
     * 返回从 git root 到 workDir 的目录列表。
     * 如果 workDir 不在 git 仓库中，仅返回 [workDir]。
     */
    static List<String> projectInstructionDirs(String workDir) {
        String abs;
        try {
            abs = Path.of(workDir).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return List.of(workDir);
        }
        String root = findGitRoot(abs);
        if (root == null || root.isEmpty()) {
            return List.of(abs);
        }
        // 从 workDir 向上走到 git root，收集路径，然后反转（root 在前）
        var dirs = new ArrayList<String>();
        String cur = abs;
        while (true) {
            dirs.addFirst(cur);
            if (cur.equals(root)) {
                break;
            }
            Path parent = Path.of(cur).getParent();
            if (parent == null || parent.toString().equals(cur)) {
                break;
            }
            cur = parent.toString();
        }
        return dirs;
    }

    /**
     * 从 start 向上搜索 .git 目录，找到 git 仓库根。
     */
    private static String findGitRoot(String start) {
        String cur = start;
        while (true) {
            if (Files.exists(Path.of(cur, ".git"))) {
                return cur;
            }
            Path parent = Path.of(cur).getParent();
            if (parent == null || parent.toString().equals(cur)) {
                return null;
            }
            cur = parent.toString();
        }
    }

    private static String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(0, end);
    }
}
