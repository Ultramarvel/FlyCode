package com.flycode.agent;

import com.flycode.config.ProviderConfig;
import com.flycode.conversation.ConversationManager;
import com.flycode.conversation.ThinkingBlock;
import com.flycode.conversation.ToolResultBlock;
import com.flycode.conversation.ToolUseBlock;
import com.flycode.hook.HookEngine;
import com.flycode.llm.LlmClient;
import com.flycode.llm.StreamEvent;
import com.flycode.permission.PermissionChecker;
import com.flycode.permission.PermissionMode;
import com.flycode.plan.PlanFile;
import com.flycode.prompt.CoordinatorPrompt;
import com.flycode.prompt.PlanModePrompt;
import com.flycode.tool.ToolRegistry;
import com.flycode.toolresult.ToolResultBudget;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.concurrent.*;

public class Agent {

    private static final int MAX_TOKENS_CEILING = 64_000;
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    /**
     * 延迟工具清单提醒的固定开头。用它在历史里回认这条提醒还在不在：compact 把历史
     * 压成摘要之后原来那条就没了，得重发一遍。
     */
    static final String DEFERRED_REMINDER_MARKER =
            "The following deferred tools are available via ToolSearch.";

    /**
     * 上一次告诉模型的延迟工具清单，按字典序。跟当前清单一比就知道工具池有没有变，
     * 没变就不重发那条提醒。
     */
    private List<String> announcedDeferred = List.of();

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    private PermissionChecker checker;
    private HookEngine hookEngine;
    private int maxIterations;
    private String workDir;
    /**
     * Session log id for the on-disk transcript. Plumbed so that an in-loop
     * compaction can append a compact_boundary record into the same session file
     * (enabling resume to rebuild the compacted state). Null for sub-agents /
     * one-shot callers that should not write boundaries into the main session.
     */
    private String sessionId;
    private java.util.function.Supplier<List<String>> notificationFn;

    private java.util.function.Predicate<String> toolNameFilter;
    /**
     * 报告 coordinator 模式当前是否生效。每轮与 toolNameFilter 一同检查，
     * 让调度指引在工具收窄的同时出现，并在 Team 拆除后一同消失。
     */
    private java.util.function.BooleanSupplier coordinatorActiveFn;
    private String instructions = "";
    private String memoryContent = "";
    /** 可用 Skill 清单。跟着项目走，所以不进系统提示词，随首条 system-reminder 注入。 */
    private String skillSection = "";
    /** 返回本轮新出现的 Skill 清单，已通知过的不再返回。 */
    private java.util.function.Supplier<String> skillDeltaFn;

    // 非阻塞 memory recall：prefetch 与主 LLM 调用并行，工具执行后注入
    private CompletableFuture<com.flycode.memory.MemoryRecall.RecallResult> memoryRecallFuture;
    private boolean memoryRecallConsumed;
    /**
     * 本次会话已经注入过的记忆路径，召回前做预过滤，避免同一条每轮都占一个名额。
     * 只在 reminder 真正写进对话时登记，没消费掉的召回结果不留痕。
     * agent 线程写，召回的虚拟线程读，所以用并发集合。
     */
    private final Set<String> surfacedMemPaths = ConcurrentHashMap.newKeySet();
    private final com.flycode.compact.ContextCompactor.AutoCompactTrackingState compactTracking =
            new com.flycode.compact.ContextCompactor.AutoCompactTrackingState();


    /**
     * Holds the snapshots needed to rebuild working context after Layer 2
     * collapses the conversation: most-recent file reads + skill SOPs.
     * Recorded on each ReadFile / skill call; consumed by ContextCompactor
     * when the threshold trips.
     */
    private final com.flycode.compact.RecoveryState recoveryState =
            new com.flycode.compact.RecoveryState();

    public com.flycode.compact.RecoveryState getRecoveryState() { return recoveryState; }

    private com.flycode.filehistory.FileHistory fileHistory;
    public void setFileHistory(com.flycode.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public com.flycode.filehistory.FileHistory getFileHistory() { return fileHistory; }

    public ToolRegistry getRegistry() { return registry; }
    public String getProtocol() { return protocol; }

    public Agent(LlmClient client, ToolRegistry registry, String protocol, ProviderConfig cfg) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.contextWindow = cfg.resolvedContextWindow();
        this.maxOutput = cfg.resolvedMaxOutputTokens();
    }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public PermissionChecker getChecker() { return checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setMaxIterations(int max) { this.maxIterations = max; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public String getWorkDir() { return workDir; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionId() { return sessionId; }
    public void setNotificationFn(java.util.function.Supplier<List<String>> fn) { this.notificationFn = fn; }

    public void setToolNameFilter(java.util.function.Predicate<String> filter) { this.toolNameFilter = filter; }
    public void setCoordinatorActiveFn(java.util.function.BooleanSupplier fn) { this.coordinatorActiveFn = fn; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public void setSkillSection(String skillSection) { this.skillSection = skillSection; }
    public void setSkillDeltaFn(java.util.function.Supplier<String> fn) { this.skillDeltaFn = fn; }
    public void setMemoryRecallFuture(CompletableFuture<com.flycode.memory.MemoryRecall.RecallResult> future) {
        this.memoryRecallFuture = future;
        this.memoryRecallConsumed = false;
    }

    /** 已注入过的记忆路径快照，供召回在自己的线程里做预过滤。 */
    public Set<String> surfacedMemPathsSnapshot() { return Set.copyOf(surfacedMemPaths); }

    /** 记下这一轮注入了哪些记忆，下一轮召回时先把它们排除掉。 */
    public void markMemoriesSurfaced(List<String> paths) {
        if (paths != null) surfacedMemPaths.addAll(paths);
    }
    public HookEngine getHookEngine() { return hookEngine; }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        run(conv, queue);
        return queue;
    }

    // 使用调用方提供的 queue，允许 TUI 预先创建 queue 立即开始轮询
    public void run(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        Thread.startVirtualThread(() -> {
            try {
                agentLoop(conv, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
    }

    private void agentLoop(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        conv.injectLongTermMemory(instructions, memoryContent, skillSection);

        int totalInput = 0, totalOutput = 0;
        int outputRecoveries = 0;
        boolean maxTokensEscalated = false;

        int contextRetries = 0;
        boolean loopCompleted = false;

        try {
        for (int iteration = 1; ; iteration++) {
            if (maxIterations > 0 && iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }

            if (Thread.currentThread().isInterrupted()) break;

            // Drain background task notifications and inject as system reminders
            if (notificationFn != null) {
                for (String note : notificationFn.get()) {
                    conv.addSystemReminder(note);
                }
            }

            // 会话中途新增的 Skill：只补新出现的那几条，不重发整份清单，
            // 更不动系统提示词，避免把缓存前缀顶掉。
            if (skillDeltaFn != null) {
                String delta = skillDeltaFn.get();
                if (delta != null && !delta.isEmpty()) {
                    conv.addSystemReminder("The following skills became available:\n" + delta);
                }
            }

            // Compute the tool schemas once per iteration so the recovery
            // attachment (when compact fires) and the Stream call below see
            // the same set. Skill filters can only change between iterations.
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }

            // Inject deferred tool names as system reminder
            //
            // 只在需要的时候发，不每轮重发。这条提醒是加进历史的，发过一次就一直在
            // 上下文里，之后每轮再发一遍只是拿同样的内容占窗口：六十来个 MCP 工具
            // 一份清单五百多 token，四十轮下来就是两万多。
            //
            // 两种情况要重发：池子变了（MCP 是异步连上的，服务器也可能掉线重连），
            // 或者历史里那条已经被 compact 压掉了。后者靠回扫历史发现，这样就不用在
            // compact 那边额外挂钩子。
            var deferredNames = registry.getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                boolean poolChanged = !deferredNames.equals(announcedDeferred);
                if (poolChanged || !conv.hasReminderContaining(DEFERRED_REMINDER_MARKER)) {
                    var sb = new StringBuilder();
                    sb.append(DEFERRED_REMINDER_MARKER);
                    sb.append(" Their schemas are NOT loaded - use ToolSearch with ");
                    sb.append("query \"select:<name>[,<name>...]\" to load tool schemas");
                    // dispatch 模式下这些工具永远不会进 tools[]，必须额外告诉模型调用要走
                    // mcp_call，否则它读完 schema 也不知道从哪儿调
                    if (registry.getMcpLoadingMode() == com.flycode.tool.McpLoadingMode.DISPATCH) {
                        sb.append(", then invoke them with the mcp_call tool");
                    } else {
                        sb.append(" before calling them");
                    }
                    sb.append(":\n");
                    for (var dn : deferredNames) {
                        sb.append(dn).append("\n");
                    }
                    conv.addSystemReminder(sb.toString());
                    announcedDeferred = deferredNames;
                }
            }

            // Plan mode: inject structured workflow reminder
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, iteration);
                conv.addSystemReminder(reminder);
            }

            // Coordinator 模式：工具集被收窄的同时注入调度指引。
            // 走 system-reminder 而不是系统提示词：长会话里开头那份约束会被淹没，
            // 每轮追加一次才拉得回来，而且系统提示词是缓存前缀，动它整段都要重新计费。
            if (coordinatorActiveFn != null && coordinatorActiveFn.getAsBoolean()) {
                conv.addSystemReminder(CoordinatorPrompt.buildReminder(iteration));
            }

            // Layer 2: auto-compact check
            // Layer 1（工具结果预算）在结果入历史时已处理完，历史里的内容
            // 就是最终大小，直接用 conv 消息估算 token
            try {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                String compactMsg = com.flycode.compact.ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, compactTracking,
                        recoveryState, iterToolSchemas);
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                if (conv.size() < sizeBefore) {
                    conv.clearUsageAnchor();
                    conv.resetLtmInjected();
                    conv.injectLongTermMemory(instructions, memoryContent, skillSection);
                }
            } catch (Exception ignored) {}

            var tools = iterToolSchemas;
            var streamQueue = client.stream(conv, tools);

            // Consume stream events, collect tool calls
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;

            while (true) {
                StreamEvent event;
                try {
                    event = streamQueue.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (event == null) {
                    putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                    return;
                }

                switch (event) {
                    case StreamEvent.TextDelta td -> {
                        text.append(td.text());
                        putSafe(queue, new AgentEvent.StreamText(td.text()));
                    }
                    case StreamEvent.ThinkingDelta td ->
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    case StreamEvent.ToolCallDelta tcd -> {}
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        putSafe(queue, new AgentEvent.ToolUseEvent(
                                tcc.toolId(), tcc.toolName(), tcc.arguments()));
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                        turnInput = se.inputTokens();
                        turnOutput = se.outputTokens();
                        turnCacheRead = se.cacheReadTokens();
                        turnCacheCreation = se.cacheCreationTokens();
                    }
                    case StreamEvent.Error err -> {
                        lastStreamError = err.message();
                        putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        streamError = true;
                    }
                }

                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
            }

            // Error recovery
            if (streamError) {
                var lastErr = events_drain_last_error(queue);
                if (lastErr != null && (lastErr.contains("context") || lastErr.contains("too long")
                        || lastErr.contains("prompt"))) {
                    if (contextRetries < 3) {
                        contextRetries++;
                        putSafe(queue, new AgentEvent.RetryEvent("Context too long, compacting...", 0));
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = workDir != null ? workDir : System.getProperty("user.dir");
                            com.flycode.compact.ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    recoveryState, iterToolSchemas);
                        } catch (Exception ignored) {}
                        // forceCompact shrinks the conversation (summary + kept
                        // tail), so the prior anchor's message count no longer
                        // lines up; drop it and re-anchor on the next stream.
                        if (conv.size() < sizeBeforeForce) {
                            conv.clearUsageAnchor();
                            conv.resetLtmInjected();
                            conv.injectLongTermMemory(instructions, memoryContent, skillSection);
                        }
                        continue;
                    }
                }
                if (lastErr != null && lastErr.toLowerCase().contains("rate limit")) {
                    putSafe(queue, new AgentEvent.RetryEvent("Rate limited, waiting 5s...", 5000));
                    try { Thread.sleep(5000); } catch (InterruptedException e) { break; }
                    continue;
                }
                break;
            }

            totalInput += turnInput;
            totalOutput += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(totalInput, totalOutput));

            // Max tokens handling
            if ("max_tokens".equals(stopReason)) {
                if (!maxTokensEscalated) {
                    maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                        persistLastMessage(conv);
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    continue;
                } else if (outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                    outputRecoveries++;
                    conv.addAssistantFull(text.toString(), thinkingBlocks, List.of());
                    persistLastMessage(conv);
                    conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    continue;
                }
                // Exhausted: fall through to normal completion
            } else {
                outputRecoveries = 0;
            }

            // Save assistant message to conversation
            var toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
                    .toList();
            conv.addAssistantFull(text.toString(), thinkingBlocks, toolUseBlocks);
            persistLastMessage(conv);

            // Re-anchor the compaction estimate on this turn's real usage. The
            // baseline = input + cacheRead + cacheCreation + output covers the
            // sent context and the assistant message just appended; messages
            // added after this point (tool results, next user turn) are
            // estimated incrementally on top. A cache hit reports a small real
            // input, so the anchor tracks the true window far better than the
            // raw character estimate.
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                conv.recordUsageAnchor(turnInput, turnOutput, turnCacheRead, turnCacheCreation);
            }

            // No tool calls → done
            if (toolCalls.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                // No TurnComplete on the terminal (no-tool) turn
                // (agent.go emits only LoopComplete here). The TUI's TurnComplete
                // handler flushes+clears streamBuf without persisting; if we emitted
                // it first, LoopComplete would see an empty buffer and the final
                // assistant message would never be saved to the session file.
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            // Execute tool calls
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, recoveryState);
            var callInfos = toolCalls.stream()
                    .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId, tc.toolName, tc.args))
                    .toList();
            var results = executor.executeAll(callInfos);

            // Layer 1 工具结果预算：结果进入对话历史前处理完，消息一进
            // 历史就是终态。溢写文件的回读结果豁免——把模型刚读回来的
            // 内容再写盘换成预览，模型就永远看不到全文。
            Path budgetSpillDir = ToolResultBudget.spillDir(workDir, sessionId);
            java.util.Set<String> exemptIds = new java.util.HashSet<>();
            for (var tc : toolCalls) {
                if (ToolResultBudget.isSpillReadback(tc.toolName, tc.args, budgetSpillDir)) {
                    exemptIds.add(tc.toolId);
                }
            }

            var resultBlocks = new java.util.ArrayList<ToolResultBlock>(results.size());
            for (var r : results) {
                String content = r.output();
                if (content.length() > com.flycode.tool.ToolRegistry.MAX_OUTPUT_CHARS
                        && !exemptIds.contains(r.toolId())) {
                    // 单条超限：写盘换预览。写盘失败会原样保留，同一块磁盘
                    // 聚合预算也不必再试，所以两种结果都标记豁免。
                    content = ToolResultBudget.persistLargeResult(budgetSpillDir, r.toolId(), r.output());
                    exemptIds.add(r.toolId());
                }
                resultBlocks.add(new ToolResultBlock(r.toolId(), content, r.isError(), r.contentBlocks()));
            }

            // 聚合预算：一轮并行工具的结果落在同一条消息里，单条阈值管不住
            // 合计超限的情况。
            var budgeted = ToolResultBudget.apply(resultBlocks, budgetSpillDir, exemptIds);
            conv.addToolResultsMessage(budgeted);
            persistLastMessage(conv);

            // 非阻塞 memory recall：工具执行完后检查 prefetch 是否就绪
            // 记忆在第 1 轮工具执行后、第 2 轮迭代前注入
            if (memoryRecallFuture != null && !memoryRecallConsumed) {
                if (memoryRecallFuture.isDone()) {
                    try {
                        var recall = memoryRecallFuture.getNow(null);
                        if (recall != null && !recall.reminder().isEmpty()) {
                            conv.addSystemReminder(recall.reminder());
                            // 真正进了对话才算「已注入」
                            markMemoriesSurfaced(recall.paths());
                        }
                    } catch (Exception ignored) {}
                    memoryRecallConsumed = true;
                }
            }

            boolean exitPlanCalled = toolCalls.stream()
                    .anyMatch(tc -> "ExitPlanMode".equals(tc.toolName));
            if (exitPlanCalled) {
                putSafe(queue, new AgentEvent.TurnComplete(iteration));
                putSafe(queue, new AgentEvent.LoopComplete(iteration));
                loopCompleted = true;
                break;
            }

            putSafe(queue, new AgentEvent.TurnComplete(iteration));
        }
        } finally {
            if (!loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
        }
    }

    private String lastStreamError;

    private String events_drain_last_error(BlockingQueue<AgentEvent> queue) {
        return lastStreamError;
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}

    /**
     * 把刚追加进对话历史的那条消息写入会话日志。
     * <p>
     * 落盘点放在主循环而不是各个前端：TUI 和 Web 共用同一条记录路径，
     * 中间轮次的助手文本和完整的工具调用链都会被记下来，恢复会话时才能还原。
     * workDir 或 sessionId 为空时（一次性调用、子 Agent）跳过。
     */
    private void persistLastMessage(ConversationManager conv) {
        if (workDir == null || workDir.isBlank() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        var msgs = conv.getMessages();
        if (msgs.isEmpty()) return;
        com.flycode.session.SessionManager.saveConversationMessage(
                workDir, sessionId, msgs.get(msgs.size() - 1));
    }
}
