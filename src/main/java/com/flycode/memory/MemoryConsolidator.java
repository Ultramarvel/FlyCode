package com.flycode.memory;

import com.flycode.agent.Agent;
import com.flycode.agent.AgentEvent;
import com.flycode.config.ProviderConfig;
import com.flycode.conversation.ConversationManager;
import com.flycode.llm.LlmClient;
import com.flycode.permission.PermissionChecker;
import com.flycode.permission.PermissionMode;
import com.flycode.session.SessionManager;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.impl.*;

import java.util.concurrent.BlockingQueue;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 后台记忆整理（autoDream）。
 * 满足时间门（≥24h）和会话门（≥5 sessions）后自动 fork 子 Agent，
 * 扫描现有记忆，合并重复、删除过时、修正矛盾、维护索引。
 */
public class MemoryConsolidator {

    private static final Logger logger = Logger.getLogger(MemoryConsolidator.class.getName());

    private static final int DEFAULT_MIN_HOURS = 24;
    private static final int DEFAULT_MIN_SESSIONS = 5;
    private static final long SCAN_THROTTLE_MS = 10L * 60 * 1000;
    private static final String LOCK_FILE = ".consolidate-lock";
    private static final long HOLDER_STALE_MS = 60L * 60 * 1000;
    private static final String ENTRYPOINT_NAME = "MEMORY.md";
    // 与注入时的截断上限共用一个来源，避免 prompt 里承诺的额度和实际加载的额度对不上
    private static final int MAX_ENTRYPOINT_LINES = MemoryManager.MAX_ENTRYPOINT_LINES;

    private final String workDir;
    private final Path memDir;
    private final Path userMemDir;
    private final int minHours;
    private final int minSessions;
    private long lastScanAt;

    public MemoryConsolidator(String workDir) {
        this(workDir, DEFAULT_MIN_HOURS, DEFAULT_MIN_SESSIONS);
    }

    /**
     * 组装后台整理 Agent 的权限检查器：沙箱基线取项目根，
     * 用户级记忆目录在项目根之外、整理时要往里写，显式放开。
     */
    PermissionChecker buildSubAgentChecker() {
        PermissionChecker checker = new PermissionChecker(PermissionMode.BYPASS, Path.of(workDir));
        checker.allowExtraRoot(userMemDir);
        return checker;
    }

    public MemoryConsolidator(String workDir, int minHours, int minSessions) {
        this.workDir = workDir;
        this.memDir = Path.of(workDir, ".flycode", "memory");
        this.userMemDir = Path.of(System.getProperty("user.home"), ".flycode", "memory");
        this.minHours = minHours;
        this.minSessions = minSessions;
    }

    /**
     * 检查门控条件，满足则后台执行一次整理。
     * 每轮 Agent Loop 完成后调用。
     */
    public void maybeRun(LlmClient client, ConversationManager conversation, String protocol) {
        if (!Files.isDirectory(memDir)) return;

        // 时间门
        long lastAt = readLastConsolidatedAt();
        double hoursSince = (System.currentTimeMillis() - lastAt) / 3_600_000.0;
        if (hoursSince < minHours) return;

        // 扫描节流
        long now = System.currentTimeMillis();
        if (now - lastScanAt < SCAN_THROTTLE_MS) return;
        lastScanAt = now;

        // 会话门
        List<String> sessionIds = listSessionsSince(lastAt);
        if (sessionIds.size() < minSessions) return;

        // 获取锁
        Long priorMtime = tryAcquireLock();
        if (priorMtime == null) return;

        logger.fine(String.format("[consolidation] firing — %.1fh since last, %d sessions",
                hoursSince, sessionIds.size()));

        // 后台执行
        final long pmtime = priorMtime;
        Thread.startVirtualThread(() -> {
            try {
                run(client, conversation, protocol, sessionIds);
            } catch (Exception e) {
                logger.fine("[consolidation] failed: " + e.getMessage());
                rollbackLock(pmtime);
            }
        });
    }

    private void run(LlmClient client, ConversationManager conversation,
                     String protocol, List<String> sessionIds) {
        Path transcriptDir = Path.of(workDir, ".flycode", "sessions");
        String prompt = buildConsolidationPrompt(
                memDir.toString(), userMemDir.toString(),
                transcriptDir.toString(), sessionIds);

        // 构建子 Agent 的工具注册表
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new BashTool());

        PermissionChecker checker = buildSubAgentChecker();

        ConversationManager conv = new ConversationManager();
        conv.addUserMessage(prompt);

        // 构建一个最小的 ProviderConfig 供 Agent 构造
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProtocol(protocol);

        Agent subAgent = new Agent(client, registry, protocol, cfg);
        subAgent.setChecker(checker);
        subAgent.setWorkDir(workDir);
        subAgent.setMaxIterations(15);

        // 驱动子 Agent 到完成
        BlockingQueue<AgentEvent> queue = subAgent.run(conv);
        try {
            while (true) {
                AgentEvent event = queue.take();
                if (event instanceof AgentEvent.LoopComplete) break;
                if (event instanceof AgentEvent.ErrorEvent) break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        logger.fine("[consolidation] completed");
    }

    // --- 锁文件管理 ---

    private Path lockPath() {
        return memDir.resolve(LOCK_FILE);
    }

    private long readLastConsolidatedAt() {
        try {
            return Files.getLastModifiedTime(lockPath()).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 获取锁。成功返回旧 mtime（ms），失败返回 null。
     */
    private Long tryAcquireLock() {
        Path path = lockPath();
        Long mtimeMs = null;
        Long holderPid = null;

        if (Files.exists(path)) {
            try {
                mtimeMs = Files.getLastModifiedTime(path).toMillis();
                String raw = Files.readString(path).trim();
                if (!raw.isEmpty()) holderPid = Long.parseLong(raw);
            } catch (Exception ignored) {}
        }

        if (mtimeMs != null && System.currentTimeMillis() - mtimeMs < HOLDER_STALE_MS) {
            if (holderPid != null && isProcessRunning(holderPid)) {
                return null;
            }
        }

        try {
            Files.createDirectories(memDir);
            Files.writeString(path, String.valueOf(ProcessHandle.current().pid()));
        } catch (IOException e) {
            return null;
        }

        // 回读验证
        try {
            String verify = Files.readString(path).trim();
            if (Long.parseLong(verify) != ProcessHandle.current().pid()) return null;
        } catch (Exception e) {
            return null;
        }

        return mtimeMs != null ? mtimeMs : 0L;
    }

    private void rollbackLock(long priorMtime) {
        Path path = lockPath();
        try {
            if (priorMtime == 0) {
                Files.deleteIfExists(path);
                return;
            }
            Files.writeString(path, "");
            Instant t = Instant.ofEpochMilli(priorMtime);
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(t));
        } catch (IOException ignored) {}
    }

    private static boolean isProcessRunning(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    // --- 会话列表 ---

    private List<String> listSessionsSince(long sinceMs) {
        var sessions = SessionManager.listSessions(workDir);
        Instant since = Instant.ofEpochMilli(sinceMs);
        return sessions.stream()
                .filter(s -> s.modTime().isAfter(since))
                .map(SessionManager.SessionInfo::id)
                .collect(Collectors.toList());
    }

    // --- Prompt ---

    private String buildConsolidationPrompt(
            String memDirStr, String userMemDirStr,
            String transcriptDir, List<String> sessionIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Dream: Memory Consolidation\n\n");
        sb.append("You are performing a dream — a reflective pass over your memory files. ");
        sb.append("Synthesize what you've learned recently into durable, well-organized ");
        sb.append("memories so that future sessions can orient quickly.\n\n");
        sb.append(String.format("Project memory directory: `%s`\n", memDirStr));
        sb.append(String.format("User memory directory: `%s`\n", userMemDirStr));
        sb.append("The memory directory already exists — write to it directly.\n\n");
        sb.append(String.format("Session transcripts: `%s` (large JSONL files — grep narrowly, don't read whole files)\n\n", transcriptDir));
        sb.append("---\n\n");
        sb.append("## Phase 1 — Orient\n\n");
        sb.append("- `ls` the memory directory to see what already exists\n");
        sb.append(String.format("- Read `%s` to understand the current index\n", ENTRYPOINT_NAME));
        sb.append("- Skim existing topic files so you improve them rather than creating duplicates\n\n");
        sb.append("## Phase 2 — Gather recent signal\n\n");
        sb.append("Look for new information worth persisting:\n\n");
        sb.append("1. **Existing memories that drifted** — facts that contradict something you see in the codebase now\n");
        sb.append("2. **Transcript search** — if you need specific context, grep the JSONL transcripts for narrow terms\n\n");
        sb.append("Don't exhaustively read transcripts. Look only for things you already suspect matter.\n\n");
        sb.append("## Phase 3 — Consolidate\n\n");
        sb.append("For each thing worth remembering, write or update a memory file. ");
        sb.append("Each memory file uses YAML frontmatter with name, description, and metadata.type fields, ");
        sb.append("followed by a Markdown body.\n\n");
        sb.append("Focus on:\n");
        sb.append("- Merging new signal into existing topic files rather than creating near-duplicates\n");
        sb.append("- Converting relative dates (\"yesterday\", \"last week\") to absolute dates\n");
        sb.append("- Deleting contradicted facts — if today's investigation disproves an old memory, fix it at the source\n\n");
        sb.append("## Phase 4 — Prune and index\n\n");
        sb.append(String.format("Update `%s` so it stays under %d lines AND under ~25KB. ", ENTRYPOINT_NAME, MAX_ENTRYPOINT_LINES));
        sb.append("It's an **index**, not a dump — each entry should be one line under ~150 characters: ");
        sb.append("`- [Title](file.md) — one-line hook`. Never write memory content directly into it.\n\n");
        sb.append("- Remove pointers to memories that are now stale, wrong, or superseded\n");
        sb.append("- Demote verbose entries: if an index line is over ~200 chars, shorten the line, move the detail\n");
        sb.append("- Add pointers to newly important memories\n");
        sb.append("- Resolve contradictions — if two files disagree, fix the wrong one\n\n");
        sb.append("---\n\n");
        sb.append("**Tool constraints for this run:** Bash is restricted to read-only commands ");
        sb.append("(`ls`, `find`, `grep`, `cat`, `stat`, `wc`, `head`, `tail`, and similar). ");
        sb.append("Anything that writes, redirects to a file, or modifies state will be denied.\n\n");

        if (!sessionIds.isEmpty()) {
            sb.append(String.format("Sessions since last consolidation (%d):\n", sessionIds.size()));
            for (String id : sessionIds) {
                sb.append("- ").append(id).append("\n");
            }
        }

        sb.append("\nReturn a brief summary of what you consolidated, updated, or pruned. ");
        sb.append("If nothing changed (memories are already tight), say so.\n");

        return sb.toString();
    }
}
