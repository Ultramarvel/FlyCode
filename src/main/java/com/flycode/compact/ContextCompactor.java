package com.flycode.compact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.flycode.conversation.ConversationManager;
import com.flycode.conversation.Message;
import com.flycode.conversation.ThinkingBlock;
import com.flycode.conversation.ToolResultBlock;
import com.flycode.conversation.ToolUseBlock;
import com.flycode.llm.LlmClient;
import com.flycode.llm.StreamEvent;
import com.flycode.session.SessionManager;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Layer 2 上下文压缩：已用 token 逼近上下文窗口时，让大模型把较早的对话
 * 摘要掉，只保留近期原文。Layer 1（工具结果预算）不在这里：单条超限和
 * 单消息聚合超限都在结果进入对话历史那一刻由
 * {@link com.flycode.toolresult.ToolResultBudget} 处理完，这里拿到的
 * 消息大小即最终大小。
 */
public final class ContextCompactor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // AUTOCOMPACT_THRESHOLD is the legacy ratio gate (kept for reference only).
    // The live decision now uses the absolute-token formula below:
    // 当已用 token 数接近上下文窗口上限时触发压缩，腾出空间继续对话。
    private static final double AUTOCOMPACT_THRESHOLD = 0.80;

    // SUMMARY_OUTPUT_RESERVE reserves room for the summary response itself, so the
    // effective window is contextWindow − min(model maxOutput, SUMMARY_OUTPUT_RESERVE).
    private static final int SUMMARY_OUTPUT_RESERVE = 20_000;
    // AUTO_COMPACT_SAFETY_MARGIN sets the soft auto-compact trigger line below the
    // effective window.
    private static final int AUTO_COMPACT_SAFETY_MARGIN = 13_000;
    // MANUAL_COMPACT_SAFETY_MARGIN sets the hard-block line: once used tokens cross
    // effectiveWindow − MANUAL_COMPACT_SAFETY_MARGIN, we force a compaction rather
    // than rely on the soft trigger.
    private static final int MANUAL_COMPACT_SAFETY_MARGIN = 3_000;

    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // PTL 重试：摘要请求本身超出上下文窗口时，按 API 轮次丢弃最老的消息组重试
    private static final int MAX_PTL_RETRIES = 3;
    private static final String PTL_RETRY_MARKER = "[earlier conversation truncated for compaction retry]";

    // ── messagesToKeep window ─────────────────────────────────────────────
    // A Layer 2 compaction no longer collapses the whole conversation into a
    // lone summary; it keeps the recent verbatim tail and only summarizes the
    // older prefix. KEEP_RECENT_TOKENS is the floor we try to keep, capped by
    // KEEP_MAX_TOKENS; MIN_KEEP_MESSAGES guarantees at least a few raw turns
    // survive even when they are tiny.
    private static final int KEEP_RECENT_TOKENS = 10_000;
    private static final int MIN_KEEP_MESSAGES = 5;
    private static final int KEEP_MAX_TOKENS = 40_000;

    /** Recovery limits applied to the attachment block appended after a Layer 2 summary. */
    public static final int RECOVERY_FILE_LIMIT = 5;
    public static final int RECOVERY_TOKENS_PER_FILE = 5_000;
    public static final int RECOVERY_SKILLS_BUDGET = 25_000;
    public static final int RECOVERY_TOKENS_PER_SKILL = 5_000;
    private static final double RECOVERY_CHARS_PER_TOKEN = 3.5;
    private static final DateTimeFormatter RECOVERY_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String SUMMARY_SYSTEM_PROMPT = """
            Your task is to create a detailed summary of the conversation so far, paying close attention to the user's explicit requests and your previous actions.
            This summary should be thorough in capturing technical details, code patterns, and architectural decisions that would be essential for continuing development work without losing context.

            Before providing your final summary, wrap your analysis in <analysis> tags to organize your thoughts and ensure you've covered all necessary points. In your analysis process:

            1. Chronologically analyze each message and section of the conversation. For each section thoroughly identify:
               - The user's explicit requests and intents
               - Your approach to addressing the user's requests
               - Key decisions, technical concepts and code patterns
               - Specific details like:
                 - file names
                 - full code snippets
                 - function signatures
                 - file edits
               - Errors that you ran into and how you fixed them
               - Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
            2. Double-check for technical accuracy and completeness, addressing each required element thoroughly.

            After your analysis, output your final summary wrapped in <summary> tags. Your summary should include the following sections:

            1. Primary Request and Intent: Capture all of the user's explicit requests and intents in detail
            2. Key Technical Concepts: List all important technical concepts, technologies, and frameworks discussed.
            3. Files and Code Sections: Enumerate specific files and code sections examined, modified, or created. Pay special attention to the most recent messages and include full code snippets where applicable and include a summary of why this file read or edit is important.
            4. Errors and fixes: List all errors that you ran into, and how you fixed them. Pay special attention to specific user feedback that you received, especially if the user told you to do something differently.
            5. Problem Solving: Document problems solved and any ongoing troubleshooting efforts.
            6. All user messages: List ALL user messages that are not tool results. These are critical for understanding the users' feedback and changing intent.
            7. Pending Tasks: Outline any pending tasks that you have explicitly been asked to work on.
            8. Current Work: Describe in detail precisely what was being worked on immediately before this summary request, paying special attention to the most recent messages from both user and assistant. Include file names and code snippets where applicable.
            9. Optional Next Step: List the next step that you will take that is related to the most recent work you were doing. IMPORTANT: ensure that this step is DIRECTLY in line with the user's most recent explicit requests. If there is a next step, include direct quotes from the most recent conversation showing exactly what task you were working on and where you left off.

            Output structure:

            <analysis>
            [Your thought process]
            </analysis>

            <summary>
            1. Primary Request and Intent:
               [Detailed description]

            2. Key Technical Concepts:
               - [Concept 1]

            3. Files and Code Sections:
               - [File and code snippet]

            4. Errors and fixes:
               - [Error and fix]

            5. Problem Solving:
               [Description]

            6. All user messages:
               - [User message 1]

            7. Pending Tasks:
               - [Task 1]

            8. Current Work:
               [Precise description]

            9. Optional Next Step:
               [Next step if applicable]
            </summary>""";

    private ContextCompactor() {}

    // ── Circuit Breaker ────────────────────────────────────────────────

    public static class AutoCompactTrackingState {
        private int consecutiveFailures;

        public boolean isTripped() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }

        public void recordFailure() {
            consecutiveFailures++;
        }

        public void reset() {
            consecutiveFailures = 0;
        }
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns the absolute used-token line at which Layer 2 should fire.
     * effectiveWindow = contextWindow − min(maxOutput, SUMMARY_OUTPUT_RESERVE);
     * the threshold is effectiveWindow minus the safety margin (manual margin for
     * the hard-block line, auto margin for the soft trigger).
     */
    private static int computeCompactThreshold(int contextWindow, int maxOutput, boolean manual) {
        int reserve = SUMMARY_OUTPUT_RESERVE;
        if (maxOutput > 0 && maxOutput < reserve) {
            reserve = maxOutput;
        }
        int effectiveWindow = contextWindow - reserve;
        int margin = manual ? MANUAL_COMPACT_SAFETY_MARGIN : AUTO_COMPACT_SAFETY_MARGIN;
        return effectiveWindow - margin;
    }

    /**
     * Layer 1 runs unconditionally (offload + snip). Layer 2 fires when used
     * tokens reach the auto-compact threshold (effectiveWindow − auto margin);
     * once they cross the hard-block line (effectiveWindow − manual margin) it
     * forces a compaction. See {@link #computeCompactThreshold}.
     */
    public static String manage(ConversationManager conv, LlmClient client,
                                int contextWindow, int maxOutput, String workDir, String sessionId,
                                AutoCompactTrackingState tracking,
                                RecoveryState recovery,
                                List<Map<String, Object>> toolSchemas) {
        // 历史里的工具结果在入历史时已按预算处理为终态，conv 自身消息
        // 就是实际发送量，直接用它估算。
        UsageAnchor anchor = conv.hasUsageAnchor()
                ? new UsageAnchor(conv.getBaselineTokens(), conv.getAnchorCount())
                : null;
        int tokens = currentTokens(conv.getMessages(), anchor);
        // 软触发：已用 token >= effectiveWindow − 自动安全边距
        if (tokens < computeCompactThreshold(contextWindow, maxOutput, false)) {
            return "";
        }

        // 硬触发：已用 token 逼近上下文窗口极限，强制压缩
        if (tokens >= computeCompactThreshold(contextWindow, maxOutput, true)) {
            return forceCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
        }

        if (tracking == null || !tracking.isTripped()) {
            try {
                String l2 = autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
                if (tracking != null) tracking.reset();
                return l2;
            } catch (Exception e) {
                if (tracking != null) tracking.recordFailure();
            }
        }
        return "";
    }

    /**
     * Force a full auto-compact, writing a compact_boundary into the session log
     * when {@code workDir}/{@code sessionId} are provided.
     */
    public static String forceCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        return autoCompact(conv, client, contextWindow, workDir, sessionId, recovery, toolSchemas);
    }

    /**
     * Real API-usage anchor for context-window accounting. Captured after each
     * stream ends: {@code baselineTokens} = input + cacheRead + cacheCreation +
     * output reported by the provider, and {@code anchorCount} = the number of
     * conversation messages present when that usage was measured. Everything
     * after {@code anchorCount} is estimated incrementally on top of the
     * baseline, so a cache hit (where the real input is far below the raw
     * character estimate) no longer inflates the compaction decision.
     */
    public record UsageAnchor(int baselineTokens, int anchorCount) {}

    /**
     * Current used-token estimate for the compaction decision.
     *
     * <p>With a real-usage {@code anchor}: {@code baselineTokens} plus a
     * character estimate of only the messages appended after the anchor
     * (index >= anchorCount). Without an anchor (cold start, before any stream
     * has reported usage) it falls back to estimating all messages, matching
     * the legacy behaviour so the first turn still works.
     */
    public static int currentTokens(List<Message> messages, UsageAnchor anchor) {
        if (anchor == null || anchor.anchorCount() < 0
                || anchor.anchorCount() > messages.size()) {
            return estimateTokens(messages);
        }
        List<Message> appended = messages.subList(anchor.anchorCount(), messages.size());
        return anchor.baselineTokens() + estimateTokens(appended);
    }

    /** Estimate the token count for a list of messages using a simple heuristic. */
    public static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message m : messages) {
            total += (int) (safeLength(m.getContent()) / 3.5) + 4;

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    String argsJson;
                    try {
                        argsJson = MAPPER.writeValueAsString(tu.arguments());
                    } catch (JsonProcessingException e) {
                        argsJson = "{}";
                    }
                    total += 50 + (int) (argsJson.length() / 3.5);
                }
            }

            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    total += (int) (safeLength(tr.content()) / 3.5) + 10;
                }
            }

            if (m.getThinkingBlocks() != null) {
                for (ThinkingBlock tb : m.getThinkingBlocks()) {
                    total += (int) (safeLength(tb.thinking()) / 3.5);
                }
            }
        }
        return total;
    }

    // ── Layer 2: Auto-compact ──────────────────────────────────────────

    /**
     * Pick the index where the verbatim "keep" tail begins, mirroring Claude
     * Code's messagesToKeep selection.
     *
     * <p>Walk backwards from the end accumulating each message's estimated
     * tokens. We stop and keep everything from the current index once either
     * floor (KEEP_RECENT_TOKENS of tokens, or MIN_KEEP_MESSAGES of messages) is
     * met — whichever comes first. The accumulator is also capped: if adding a
     * message would push the kept tail over KEEP_MAX_TOKENS we stop before it.
     *
     * <p>Pairing protection: a {@code user} message carrying tool_result blocks
     * must never be kept without its originating {@code assistant} tool_use
     * message. If the chosen boundary lands on such a message, we walk back one
     * more (to include the assistant turn that issued the tool_use), so we never
     * keep an orphaned half of a tool_use↔tool_result pair.
     *
     * @return the start index of the keep window, or 0 when everything fits in
     *         the keep window (nothing left to summarize).
     */
    static int computeKeepStartIndex(List<Message> messages) {
        int n = messages.size();
        if (n == 0) return 0;

        int accumulated = 0;
        int kept = 0;
        int keepStart = n; // exclusive walk: keepStart is the first kept index
        for (int i = n - 1; i >= 0; i--) {
            int msgTokens = estimateTokens(List.of(messages.get(i)));
            // Cap: if this message would push the kept tail past the ceiling,
            // stop now and leave it in the summarized prefix.
            if (accumulated + msgTokens > KEEP_MAX_TOKENS && kept > 0) {
                break;
            }
            accumulated += msgTokens;
            kept++;
            keepStart = i;
            // Floor: satisfied as soon as EITHER threshold is reached.
            if (accumulated >= KEEP_RECENT_TOKENS || kept >= MIN_KEEP_MESSAGES) {
                break;
            }
        }

        // Pairing protection: never start the keep window on a user message that
        // only carries tool_result blocks — that would orphan it from its
        // assistant tool_use. Move the boundary back to include the assistant
        // turn (and skip any further dangling tool_result messages).
        while (keepStart > 0 && isToolResultMessage(messages.get(keepStart))) {
            keepStart--;
        }
        return keepStart;
    }

    private static boolean isToolResultMessage(Message m) {
        return "user".equals(m.getRole())
                && m.getToolResults() != null
                && !m.getToolResults().isEmpty();
    }

    private static String autoCompact(ConversationManager conv, LlmClient client, int contextWindow,
                                      String workDir, String sessionId,
                                      RecoveryState recovery, List<Map<String, Object>> toolSchemas) {
        List<Message> messages = conv.getMessages();
        int beforeTokens = estimateTokens(messages);

        // Keep the recent verbatim tail; only summarize the older prefix.
        int keepStartIndex = computeKeepStartIndex(messages);

        // Degenerate cases: nothing to summarize (everything is in the keep
        // window) or the prefix is too small to be worth a summary round-trip.
        // Fall back to the original behaviour of leaving the conversation as-is.
        if (keepStartIndex <= 0 || keepStartIndex < MIN_KEEP_MESSAGES) {
            return "";
        }

        List<Message> toSummarize = messages.subList(0, keepStartIndex);
        List<Message> toKeep = messages.subList(keepStartIndex, messages.size());

        // 调用 LLM 生成摘要，带 PTL 重试：摘要请求本身超出上下文窗口时，
        // 按 API 轮次从最老的开始丢弃，最多重试 MAX_PTL_RETRIES 次。
        String summaryText = requestSummaryWithPTLRetry(client, toSummarize, toolSchemas);

        // Persist a compact_boundary record so a later resume can rebuild this
        // compacted state (summary + kept tail) instead of replaying the full
        // pre-compaction transcript. Append-only: the original prefix messages
        // stay in the file but won't be replayed past this boundary. The kept tail
        // is inlined as role+content text (matching how the session log already
        // stores messages — text only, no tool blocks). The boundary stores the
        // pure summary text, not the recovery attachment, since the recovery
        // snapshots are an in-memory rebuild aid unavailable on resume. Skipped
        // when sessionId/workDir is null/blank (tests, one-shot callers).
        if (workDir != null && !workDir.isBlank() && sessionId != null && !sessionId.isBlank()) {
            List<SessionManager.KeepMessage> keepRecords = new ArrayList<>(toKeep.size());
            for (Message m : toKeep) {
                keepRecords.add(SessionManager.toKeepMessage(m));
            }
            SessionManager.saveCompactBoundary(workDir, sessionId, summaryText, keepRecords);
        }

        String content = "本次会话延续自之前的对话，因上下文空间不足进行了压缩。以下是早期对话的摘要：\n\n" + summaryText;
        if (!toKeep.isEmpty()) {
            content += "\n\n近期消息已原样保留。";
        }
        if (workDir != null && !workDir.isBlank() && sessionId != null && !sessionId.isBlank()) {
            content += "\n\n如果你需要压缩前的具体细节（代码片段、报错信息等），请用 ReadFile 读取完整会话记录："
                    + Path.of(workDir, ".flycode", "sessions", sessionId + ".jsonl");
        }
        String attachment = buildRecoveryAttachment(recovery, toolSchemas);
        if (!attachment.isEmpty()) {
            content += "\n\n---\n\n" + attachment;
        }

        // Rebuild = summary (user) + recent verbatim tail (no assistant ack).
        ConversationManager compacted = new ConversationManager();
        compacted.addUserMessage(content);
        for (Message m : toKeep) {
            appendMessage(compacted, m);
        }

        replaceConversation(conv, compacted);

        int afterTokens = estimateTokens(conv.getMessages());
        return String.format("Compacted: %d -> %d estimated tokens", beforeTokens, afterTokens);
    }

    // ── Post-compact recovery attachment ───────────────────────────────

    /**
     * Render the four-section recovery block that gets appended to the
     * summary user message. Returns "" when there is nothing worth
     * emitting so the caller can keep the summary clean.
     *
     * @param state        per-agent snapshots of recent file reads + skills
     * @param toolSchemas  the schemas the agent will send on the next request
     */
    public static String buildRecoveryAttachment(RecoveryState state,
                                                 List<Map<String, Object>> toolSchemas) {
        var sb = new StringBuilder();

        if (state != null) {
            var files = state.snapshotFiles(RECOVERY_FILE_LIMIT);
            if (!files.isEmpty()) {
                sb.append("## Recently read files\n\n")
                  .append("These snapshots are what the file-reading tool last returned. ")
                  .append("Re-open with the tool if you need the current bytes.\n\n");
                for (var f : files) {
                    String body = truncateByTokens(f.content(), RECOVERY_TOKENS_PER_FILE);
                    sb.append("### ").append(f.path())
                      .append("  (read ").append(RECOVERY_TS.format(f.timestamp())).append(")\n\n")
                      .append("```\n").append(body);
                    if (!body.endsWith("\n")) sb.append('\n');
                    sb.append("```\n\n");
                }
            }
        }

        if (state != null) {
            var skills = state.snapshotSkills();
            if (!skills.isEmpty()) {
                var section = new StringBuilder();
                section.append("## Active skills\n\n")
                       .append("These skills were invoked earlier in the session. ")
                       .append("Continue to follow each SOP when its triggering condition applies.\n\n");
                int used = 0;
                boolean emitted = false;
                for (var sk : skills) {
                    String body = truncateByTokens(sk.body(), RECOVERY_TOKENS_PER_SKILL);
                    int tokens = approxTokens(body) + approxTokens(sk.name()) + 8;
                    if (used + tokens > RECOVERY_SKILLS_BUDGET) break;
                    used += tokens;
                    section.append("### ").append(sk.name()).append("\n\n")
                           .append(body).append("\n\n");
                    emitted = true;
                }
                if (emitted) sb.append(section);
            }
        }

        if (toolSchemas != null && !toolSchemas.isEmpty()) {
            sb.append("## Available tools\n\n")
              .append("You still have access to the following tools — call them directly when the task needs one:\n\n");
            for (var t : toolSchemas) {
                if (t == null) continue;
                Object nameObj = t.get("name");
                if (nameObj == null) continue;
                String name = nameObj.toString();
                if (name.isEmpty()) continue;
                Object descObj = t.get("description");
                String desc = descObj == null ? "" : firstLine(descObj.toString());
                if (!desc.isEmpty()) {
                    sb.append("- ").append(name).append(" — ").append(desc).append('\n');
                } else {
                    sb.append("- ").append(name).append('\n');
                }
            }
            sb.append('\n');
        }

        if (sb.length() == 0) return "";

        sb.append("## Note\n\nEverything above the divider is reconstructed context. ")
          .append("For exact code, error strings, or user-typed text, re-read the source rather than ")
          .append("guess from the summary.\n");
        return sb.toString();
    }

    private static int approxTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        return (int) (s.length() / RECOVERY_CHARS_PER_TOKEN);
    }

    private static String truncateByTokens(String s, int tokenBudget) {
        if (s == null || s.isEmpty() || tokenBudget <= 0) return s == null ? "" : s;
        if (approxTokens(s) <= tokenBudget) return s;
        int maxChars = (int) (tokenBudget * RECOVERY_CHARS_PER_TOKEN);
        if (maxChars <= 0 || maxChars >= s.length()) return s;
        return s.substring(0, maxChars) + "\n… (content truncated)";
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }


    static String formatCompactSummary(String raw) {
        int start = raw.indexOf("<summary>");
        int end = raw.indexOf("</summary>");
        if (start >= 0 && end > start) {
            return raw.substring(start + "<summary>".length(), end).strip();
        }
        return raw.strip();
    }

    /**
     * 按 API 轮次分组：每个助手新回复开始一个新组，tool_use/tool_result 对保持在同一组。
     */
    private static List<List<Message>> groupMessagesByAPIRound(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        boolean prevHadToolResult = false;

        for (Message m : messages) {
            if ("assistant".equals(m.getRole()) && prevHadToolResult && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(m);
            prevHadToolResult = m.getToolResults() != null && !m.getToolResults().isEmpty();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 从最老的 API 轮次组开始丢弃，直到腾出足够 token。至少保留一组用于摘要。
     */
    private static List<Message> truncateHeadForPTL(List<Message> prefix, int tokenGap) {
        List<List<Message>> groups = groupMessagesByAPIRound(prefix);
        if (groups.size() < 2) return null;

        int dropCount;
        if (tokenGap > 0) {
            int acc = 0;
            dropCount = 0;
            for (List<Message> g : groups) {
                acc += estimateTokens(g);
                dropCount++;
                if (acc >= tokenGap) break;
            }
        } else {
            dropCount = Math.max(1, groups.size() / 5);
        }

        dropCount = Math.min(dropCount, groups.size() - 1);
        if (dropCount < 1) return null;

        List<Message> result = new ArrayList<>();
        for (int i = dropCount; i < groups.size(); i++) {
            result.addAll(groups.get(i));
        }
        if (!result.isEmpty() && !"user".equals(result.get(0).getRole())) {
            result.add(0, new Message("user", PTL_RETRY_MARKER));
        }
        return result;
    }

    /**
     * 带 PTL 重试的摘要生成：捕获 ContextTooLongException，丢弃最老轮次后重试，
     * 最多重试 MAX_PTL_RETRIES 次。
     */
    private static String requestSummaryWithPTLRetry(LlmClient client, List<Message> prefix,
                                                     List<Map<String, Object>> toolSchemas) {
        List<Message> currentPrefix = new ArrayList<>(prefix);
        for (int attempt = 0; ; attempt++) {
            String serialized = serializeForSummary(currentPrefix, 500);
            try {
                String raw = requestSummary(client, SUMMARY_SYSTEM_PROMPT + "\n\n" + serialized, toolSchemas);
                return formatCompactSummary(raw);
            } catch (RuntimeException e) {
                // 检查是否为上下文超限错误
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean isPTL = msg.contains("prompt") && msg.contains("long")
                        || msg.contains("too many") || msg.contains("context_length");
                if (!isPTL || attempt >= MAX_PTL_RETRIES) {
                    throw e;
                }
                int tokenGap = estimateTokens(currentPrefix) / 5;
                List<Message> truncated = truncateHeadForPTL(currentPrefix, tokenGap);
                if (truncated == null) {
                    throw e;
                }
                currentPrefix = truncated;
            }
        }
    }

    private static String requestSummary(LlmClient client, String prompt,
                                         List<Map<String, Object>> toolSchemas) {
        ConversationManager summaryConv = new ConversationManager();
        summaryConv.addUserMessage(prompt);

        BlockingQueue<StreamEvent> events = client.stream(summaryConv, toolSchemas);
        var summary = new StringBuilder();

        try {
            while (true) {
                StreamEvent ev = events.take();
                if (ev instanceof StreamEvent.TextDelta td) {
                    summary.append(td.text());
                } else if (ev instanceof StreamEvent.Error err) {
                    throw new RuntimeException("LLM summary failed: " + err.message());
                } else if (ev instanceof StreamEvent.StreamEnd) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Summary interrupted", e);
        }

        return summary.toString();
    }

    private static String serializeForSummary(List<Message> messages, int toolResultCap) {
        var sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(String.format("[%s]: %s\n", m.getRole(), nullSafe(m.getContent())));

            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    sb.append(String.format("[tool_use %s]: %s\n", tu.toolName(), tu.toolUseId()));
                }
            }
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    String content = nullSafe(tr.content());
                    if (content.length() > toolResultCap) {
                        content = content.substring(0, toolResultCap) + "...";
                    }
                    sb.append(String.format("[tool_result]: %s\n", content));
                }
            }
        }
        return sb.toString();
    }

    private static void appendMessage(ConversationManager conv, Message m) {
        if (m.getToolUses() != null && !m.getToolUses().isEmpty()) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), m.getToolUses());
        } else if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
            conv.addToolResultsMessage(m.getToolResults());
        } else if ("user".equals(m.getRole())) {
            conv.addUserMessage(m.getContent());
        } else if ("assistant".equals(m.getRole())) {
            conv.addAssistantFull(m.getContent(), m.getThinkingBlocks(), null);
        }
    }

    private static void replaceConversation(ConversationManager target, ConversationManager source) {
        List<Message> targetList = target.getMessagesMutable();
        targetList.clear();
        targetList.addAll(source.getMessages());
    }

    private static int safeLength(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
