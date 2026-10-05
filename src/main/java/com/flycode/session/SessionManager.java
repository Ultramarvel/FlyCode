package com.flycode.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flycode.conversation.ConversationManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

public class SessionManager {

    /**
     * TYPE_COMPACT_BOUNDARY marks a session record as a compaction boundary
     * rather than a plain conversation message. A boundary record's content
     * holds a JSON blob (see {@link CompactBoundary}) carrying the summary text
     * plus the recent tail (keep) preserved verbatim at compaction time. Plain
     * messages leave {@code type} null/empty, so old sessions and normal turns
     * are unaffected (append-only, backward-compatible).
     */
    public static final String TYPE_COMPACT_BOUNDARY = "compact_boundary";

    /**
     * A session record. {@code type} distinguishes record kinds: empty/null (the
     * default) means a plain conversation message; {@link #TYPE_COMPACT_BOUNDARY}
     * means {@code content} is a {@link CompactBoundary} JSON blob written by
     * {@link #saveCompactBoundary}.
     * <p>
     * {@code toolUses} / {@code toolResults} 保存这条消息携带的工具块，存的是与
     * 协议无关的内部表示而不是某一家厂商的线格式，因此恢复会话时即使换了 provider
     * 也能还原。两者为空时不写进 JSON，不含这些字段的旧会话文件依然能正常读出。
     */
    public record SessionMessage(String role, String type, String content, long timestamp,
                                 List<ToolUseRecord> toolUses, List<ToolResultRecord> toolResults) {
        public SessionMessage {
            toolUses = toolUses == null ? List.of() : toolUses;
            toolResults = toolResults == null ? List.of() : toolResults;
        }

        /** Convenience constructor for plain (non-boundary) messages. */
        public SessionMessage(String role, String content, long timestamp) {
            this(role, null, content, timestamp, List.of(), List.of());
        }

        /** Convenience constructor with type but no tool blocks. */
        public SessionMessage(String role, String type, String content, long timestamp) {
            this(role, type, content, timestamp, List.of(), List.of());
        }

        public boolean isCompactBoundary() {
            return TYPE_COMPACT_BOUNDARY.equals(type);
        }
    }

    /**
     * 落盘形式的工具调用。JSON 字段用 snake_case，arguments 为空时省略。
     */
    public record ToolUseRecord(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("arguments") @JsonInclude(JsonInclude.Include.NON_EMPTY)
            Map<String, Object> arguments) {}

    /** 落盘形式的工具结果，与 ToolUseRecord 通过 tool_use_id 配对。is_error 为 false 时省略。 */
    public record ToolResultRecord(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") @JsonInclude(JsonInclude.Include.NON_DEFAULT)
            boolean isError) {}

    /**
     * 压缩发生时原样保留下来的一条近期消息。与 SessionMessage 一样携带工具块，
     * 压缩后恢复会话时这段尾巴才不会缺掉工具调用链。
     */
    public record KeepMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") String content,
            @JsonProperty("tool_uses") @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<ToolUseRecord> toolUses,
            @JsonProperty("tool_results") @JsonInclude(JsonInclude.Include.NON_EMPTY)
            List<ToolResultRecord> toolResults) {
        public KeepMessage {
            // 空列表落盘时被省略，反序列化回来是 null，这里统一归一避免回放时空指针
            toolUses = toolUses == null ? List.of() : toolUses;
            toolResults = toolResults == null ? List.of() : toolResults;
        }

        /** 无工具块的便利构造器。 */
        public KeepMessage(String role, String content) {
            this(role, content, List.of(), List.of());
        }
    }

    /**
     * Structured payload stored (as JSON) in the content of a boundary record.
     * {@code summary} is the LLM-produced summary of the older prefix; {@code keep}
     * is the recent tail kept verbatim. On resume the compacted state is rebuilt
     * as: [user message = summary] + keep + any plain messages appended after the
     * boundary.
     */
    public record CompactBoundary(String summary, List<KeepMessage> keep) {}

    /** Result of {@link #findLastCompactBoundary}: the boundary and the plain messages after it. */
    public record BoundaryScan(CompactBoundary boundary, List<SessionMessage> after, boolean found) {}

    public record SessionInfo(String id, String firstMessage, int messageCount,
                              long fileSize, String gitBranch, Instant modTime) {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path sessionsDir(String workDir) {
        return Path.of(workDir, ".flycode", "sessions");
    }

    // ---- ID generation ----

    /**
     * 生成带随机后缀的 session ID，格式为 yyyyMMdd-HHmmss-xxxx。
     * 随机后缀使用 SecureRandom 生成 2 字节十六进制，防止同秒并发冲突。
     */
    public static String newId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        byte[] randomBytes = new byte[2];
        try {
            java.security.SecureRandom.getInstanceStrong().nextBytes(randomBytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            // SecureRandom 极少失败；兜底用纳秒低 16 位
            int fallback = (int) (System.nanoTime() & 0xFFFF);
            return "%s-%04x".formatted(timestamp, fallback);
        }
        return "%s-%s".formatted(timestamp,
                java.util.HexFormat.of().formatHex(randomBytes));
    }

    // ---- Persistence ----

    public static void saveMessage(String workDir, String sessionId, String role, String content) {
        saveRecord(workDir, sessionId, role, null, content, List.of(), List.of());
    }

    /**
     * 保存一条对话消息，连同它携带的工具块一起落盘。
     * 思考块不落盘：它的 signature 只在同一轮工具循环内需要回传，跨会话恢复用不上。
     */
    public static void saveConversationMessage(String workDir, String sessionId,
                                               com.flycode.conversation.Message msg) {
        saveRecord(workDir, sessionId, msg.getRole(), null, msg.getContent(),
                toolUseRecords(msg), toolResultRecords(msg));
    }

    private static List<ToolUseRecord> toolUseRecords(com.flycode.conversation.Message msg) {
        if (msg.getToolUses() == null || msg.getToolUses().isEmpty()) return List.of();
        List<ToolUseRecord> out = new ArrayList<>();
        for (var tu : msg.getToolUses()) {
            out.add(new ToolUseRecord(tu.toolUseId(), tu.toolName(), tu.arguments()));
        }
        return out;
    }

    private static List<ToolResultRecord> toolResultRecords(com.flycode.conversation.Message msg) {
        if (msg.getToolResults() == null || msg.getToolResults().isEmpty()) return List.of();
        List<ToolResultRecord> out = new ArrayList<>();
        for (var tr : msg.getToolResults()) {
            out.add(new ToolResultRecord(tr.toolUseId(), tr.content(), tr.isError()));
        }
        return out;
    }

    /**
     * Append a compaction boundary record so a later resume can rebuild the
     * compacted state (summary + kept tail) instead of replaying the full
     * pre-compaction transcript. Append-only: the original prefix messages stay
     * in the file but won't be replayed past this boundary (see
     * {@link #findLastCompactBoundary}). The summary + keep are inlined into the
     * record's content as a {@link CompactBoundary} JSON blob. No-op when
     * workDir/sessionId is null/blank (tests, one-shot callers).
     */
    public static void saveCompactBoundary(String workDir, String sessionId,
                                           String summary, List<KeepMessage> keep) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            String blob = MAPPER.writeValueAsString(
                    new CompactBoundary(summary, keep == null ? List.of() : keep));
            saveRecord(workDir, sessionId, "system", TYPE_COMPACT_BOUNDARY, blob, List.of(), List.of());
        } catch (JsonProcessingException ignored) {
            // best-effort: a failed boundary just means the next resume replays
            // verbatim, which is still correct (backward-compatible).
        }
    }

    private static void saveRecord(String workDir, String sessionId, String role, String type,
                                   String content, List<ToolUseRecord> toolUses,
                                   List<ToolResultRecord> toolResults) {
        try {
            Path baseDir = sessionsDir(workDir);
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(sessionId + ".jsonl");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("role", role);
            // 普通消息省略 type 键，读取方遇到缺字段的记录照常解析
            if (type != null && !type.isEmpty()) {
                line.put("type", type);
            }
            line.put("content", content);
            line.put("ts", Instant.now().getEpochSecond());
            // 工具块仅在非空时写入，旧读取方和旧会话文件不受影响
            if (toolUses != null && !toolUses.isEmpty()) {
                line.put("tool_uses", toolUses);
            }
            if (toolResults != null && !toolResults.isEmpty()) {
                line.put("tool_results", toolResults);
            }
            String json = MAPPER.writeValueAsString(line) + "\n";
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 尽力而为：会话日志写失败不该打断正在进行的对话
        }
    }

    public static List<SessionMessage> loadSession(String workDir, String sessionId) {
        Path file = sessionsDir(workDir).resolve(sessionId + ".jsonl");
        if (!Files.exists(file)) {
            return List.of();
        }
        List<SessionMessage> messages = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = MAPPER.readValue(line, Map.class);
                    String role = (String) map.get("role");
                    String type = (String) map.get("type");
                    String content = (String) map.get("content");
                    long ts = map.get("ts") instanceof Number n ? n.longValue() : 0L;
                    List<ToolUseRecord> toolUses = readToolUses(map.get("tool_uses"));
                    List<ToolResultRecord> toolResults = readToolResults(map.get("tool_results"));
                    // 只带工具结果的消息本身没有文本内容，不能按 content 是否为空来过滤，
                    // 否则整条工具往返都会在恢复会话时被丢掉。
                    boolean empty = (content == null || content.isEmpty())
                            && toolUses.isEmpty() && toolResults.isEmpty();
                    if (!empty) {
                        messages.add(new SessionMessage(role, type,
                                content == null ? "" : content, ts, toolUses, toolResults));
                    }
                } catch (IOException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException ignored) {
            // return whatever we collected so far
        }
        return messages;
    }

    // ---- Compaction-boundary scanning ----

    /**
     * Scan the loaded records for the LAST compaction boundary. Returns the
     * parsed boundary plus the plain (non-boundary) messages appended after it.
     * When no boundary exists (or its blob is corrupt) {@code found} is false and
     * the caller should replay all records verbatim — backward-compatible with
     * old sessions that have no boundary records.
     */
    public static BoundaryScan findLastCompactBoundary(List<SessionMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).isCompactBoundary()) {
                last = i;
            }
        }
        if (last < 0) {
            return new BoundaryScan(null, List.of(), false);
        }
        CompactBoundary boundary;
        try {
            boundary = MAPPER.readValue(messages.get(last).content(), CompactBoundary.class);
        } catch (IOException e) {
            // Corrupt boundary blob — fall back to full replay rather than losing
            // the conversation.
            return new BoundaryScan(null, List.of(), false);
        }
        List<SessionMessage> after = new ArrayList<>();
        for (int i = last + 1; i < messages.size(); i++) {
            SessionMessage m = messages.get(i);
            if (m.isCompactBoundary()) continue; // defensive; we targeted the final one
            after.add(m);
        }
        return new BoundaryScan(boundary, after, true);
    }

    // ---- Conversation rebuild ----

    /**
     * Compaction-aware rebuild. If the session contains a {@code compact_boundary},
     * the live conversation is the compacted state — [summary as user message] +
     * kept tail + any plain messages appended after the boundary — and the
     * original pre-compaction prefix is NOT replayed (it stays in the file for
     * audit). Without a boundary (old sessions) everything is replayed verbatim.
     */
    public static ConversationManager rebuildConversation(List<SessionMessage> messages) {
        BoundaryScan scan = findLastCompactBoundary(messages);
        if (!scan.found()) {
            return replay(messages);
        }
        List<SessionMessage> replay = new ArrayList<>();
        // Summary becomes the leading user message with the same Chinese framing
        // as autoCompact, so the model sees a consistent context header on resume.
        String resumeSummary = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n"
                + scan.boundary().summary();
        if (!scan.boundary().keep().isEmpty()) {
            resumeSummary += "\n\n近期消息已原样保留。";
        }
        replay.add(new SessionMessage("user", resumeSummary, 0L));
        for (KeepMessage k : scan.boundary().keep()) {
            // 保留的尾巴要连同工具块一起回放，否则压缩后恢复会话会缺掉调用链
            replay.add(new SessionMessage(k.role(), null, k.content(), 0L,
                    k.toolUses(), k.toolResults()));
        }
        replay.addAll(scan.after());
        return replay(replay);
    }

    private static ConversationManager replay(List<SessionMessage> messages) {
        ConversationManager conversation = new ConversationManager();
        for (SessionMessage msg : messages) {
            if (msg.isCompactBoundary()) continue; // never replay the raw boundary blob
            com.flycode.conversation.Message restored = toConversationMessage(msg);
            if (restored.getToolUses() != null && !restored.getToolUses().isEmpty()) {
                conversation.addAssistantMessageWithTools(restored.getContent(), restored.getToolUses());
            } else if (restored.getToolResults() != null && !restored.getToolResults().isEmpty()) {
                conversation.addToolResultsMessage(restored.getToolResults());
            } else if ("assistant".equals(msg.role())) {
                conversation.addAssistantMessage(msg.content());
            } else {
                conversation.addUserMessage(msg.content());
            }
        }
        return conversation;
    }

    /** 把落盘记录还原成内存中的对话消息，供恢复会话时重建历史。 */
    public static com.flycode.conversation.Message toConversationMessage(SessionMessage msg) {
        List<com.flycode.conversation.ToolUseBlock> uses = new ArrayList<>();
        for (ToolUseRecord tu : msg.toolUses()) {
            uses.add(new com.flycode.conversation.ToolUseBlock(
                    tu.toolUseId(), tu.toolName(), tu.arguments()));
        }
        List<com.flycode.conversation.ToolResultBlock> results = new ArrayList<>();
        for (ToolResultRecord tr : msg.toolResults()) {
            results.add(new com.flycode.conversation.ToolResultBlock(
                    tr.toolUseId(), tr.content(), tr.isError()));
        }
        var restored = new com.flycode.conversation.Message(msg.role(), msg.content());
        restored.setToolUses(uses);
        restored.setToolResults(results);
        return restored;
    }

    /** 把内存中的对话消息转成压缩边界里保留的尾巴记录。 */
    public static KeepMessage toKeepMessage(com.flycode.conversation.Message msg) {
        return new KeepMessage(msg.getRole(), msg.getContent(),
                toolUseRecords(msg), toolResultRecords(msg));
    }


    @SuppressWarnings("unchecked")
    private static List<ToolUseRecord> readToolUses(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ToolUseRecord> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            out.add(new ToolUseRecord(
                    (String) m.get("tool_use_id"),
                    (String) m.get("tool_name"),
                    (Map<String, Object>) m.get("arguments")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolResultRecord> readToolResults(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ToolResultRecord> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            out.add(new ToolResultRecord(
                    (String) m.get("tool_use_id"),
                    (String) m.get("content"),
                    Boolean.TRUE.equals(m.get("is_error"))));
        }
        return out;
    }

    // ---- Session expiry cleanup ----

    /** 过期阈值：30 天 */
    private static final long EXPIRY_DAYS = 30;

    /**
     * 自动清理超过 30 天的过期 session 文件。
     * 根据文件的最后修改时间判断是否过期。
     * 失败时静默忽略——清理是尽力而为，不应影响正常流程。
     */
    public static void cleanExpiredSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        long cutoffMs = System.currentTimeMillis() - EXPIRY_DAYS * 24 * 60 * 60 * 1000L;
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     try {
                         long mtime = Files.getLastModifiedTime(p).toMillis();
                         if (mtime < cutoffMs) {
                             Files.deleteIfExists(p);
                         }
                     } catch (IOException ignored) {
                         // 单个文件清理失败不影响其它
                     }
                 });
        } catch (IOException ignored) {
            // 目录不可读时静默忽略
        }
    }

    // ---- Listing ----

    public static List<SessionInfo> listSessions(String workDir) {
        Path baseDir = sessionsDir(workDir);
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        String branch = currentGitBranch(workDir);
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> paths = Files.list(baseDir)) {
            paths.filter(p -> p.toString().endsWith(".jsonl"))
                 .filter(Files::isRegularFile)
                 .forEach(p -> {
                     String fileName = p.getFileName().toString();
                     String id = fileName.substring(0, fileName.length() - ".jsonl".length());
                     try {
                         long fileSize = Files.size(p);
                         Instant modTime = Files.getLastModifiedTime(p).toInstant();
                         List<SessionMessage> msgs = loadSession(workDir, id);
                         String first = msgs.stream()
                                 .filter(m -> "user".equals(m.role()))
                                 .map(SessionMessage::content)
                                 .findFirst()
                                 .orElse("");
                         sessions.add(new SessionInfo(id, first, msgs.size(),
                                 fileSize, branch, modTime));
                     } catch (IOException ignored) {
                         // skip this file
                     }
                 });
        } catch (IOException ignored) {
            // return empty
        }
        sessions.sort(Comparator.comparing(SessionInfo::modTime).reversed());
        return sessions;
    }

    // ---- Git branch ----

    public static String currentGitBranch(String workDir) {
        try {
            Process proc = new ProcessBuilder("git", "-C", workDir, "rev-parse", "--abbrev-ref", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(proc.getInputStream().readAllBytes()).trim();
            int code = proc.waitFor();
            return code == 0 ? output : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }

    // ---- Formatting helpers ----

    public static String formatRelativeTime(Instant t) {
        Duration d = Duration.between(t, Instant.now());
        long seconds = d.getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes == 1 ? "1 minute ago" : minutes + " minutes ago";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours == 1 ? "1 hour ago" : hours + " hours ago";
        }
        long days = hours / 24;
        if (days < 7) {
            return days == 1 ? "1 day ago" : days + " days ago";
        }
        long weeks = days / 7;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            double kb = bytes / 1024.0;
            return kb == (long) kb
                    ? String.format("%.0fKB", kb)
                    : String.format("%.1fKB", kb);
        }
        double mb = bytes / 1024.0 / 1024.0;
        return String.format("%.1fMB", mb);
    }

    // ---- Search ----

    public static boolean matchesSearch(SessionInfo s, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String q = query.toLowerCase();
        return s.firstMessage().toLowerCase().contains(q)
                || s.id().toLowerCase().contains(q);
    }
}
