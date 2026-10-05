package com.flycode.agent;

import com.flycode.compact.RecoveryState;
import com.flycode.hook.HookEngine;
import com.flycode.permission.PermissionChecker;
import com.flycode.permission.PermissionResponse;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent tool executor that partitions tool calls into read-only (parallel)
 * and write/command (sequential) batches.
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final PermissionChecker checker;

    private final HookEngine hookEngine;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    public record ToolExecResult(String toolId, String output, boolean isError,
                                java.util.List<Map<String, Object>> contentBlocks) {

        public ToolExecResult(String toolId, String output, boolean isError) {
            this(toolId, output, isError, null);
        }
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue) {
        this(registry, checker, hookEngine, eventQueue, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
    }

    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
        // 按相邻性分批：连续的只读工具合成一个并行批次，写/命令工具各自独占一批
        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolExecResult>();

        for (var batch : batches) {
            if (batch.concurrent && batch.calls.size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = batch.calls.stream()
                            .map(call -> executor.submit(() -> executeSingle(call)))
                            .toList();
                    for (var future : futures) {
                        try { results.add(future.get()); }
                        catch (Exception ignored) {}
                    }
                }
            } else {
                for (var call : batch.calls) results.add(executeSingle(call));
            }
        }

        return results;
    }

    private record ToolBatch(boolean concurrent, List<ToolCallInfo> calls) {}

    private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            var tool = registry.get(call.toolName());
            // 安不安全按这一次调用的实际参数算，不是只看工具类别。ls 和 rm 都是 Bash，
            // 前者可以跟 ReadFile 一起并发，后者必须独占。
            boolean safe = tool != null && tool.isConcurrencySafe(call.args());

            if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
                batches.getLast().calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    private ToolExecResult executeSingle(ToolCallInfo call) {
        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolExecResult(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }

        // 权限检查优先于 hook：先拦截无权操作，再让 hook 介入
        if (checker != null) {
            var check = checker.check(tool, call.args());
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    return new ToolExecResult(call.toolId(), msg, true);
                }
                case ASK -> {
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                    PermissionResponse response;
                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == PermissionResponse.DENY) {
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(),
                                com.flycode.conversation.ToolPairing.REJECTED_TOOL_RESULT, true, 0));
                        return new ToolExecResult(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.args());
                        if (content != null) {
                            // 写入本地规则文件，写完立即重载，本轮之后即刻生效
                            String pattern = content.length() > 60
                                    ? content.substring(0, 60) + "*"
                                    : content + "*";
                            checker.appendLocalRule(call.toolName(), pattern);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }

        // Pre-tool hook 在权限通过后执行，可拦截特定工具调用
        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
            if (hookResult.rejected()) {
                String msg = "Rejected by hook: " + hookResult.message();
                putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                return new ToolExecResult(call.toolId(), msg, true);
            }
        }

        long start = System.nanoTime();
        ToolResult result;
        try {
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        snapshotForRecovery(call, result);

        String output = result.output();
        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

        // Post-tool hooks
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolExecResult(call.toolId(), output, result.isError(), result.contentBlocks());
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Capture what ReadFile just returned so the compact recovery block
     * can replay it after a Layer 2 summary wipes the transcript.
     * Re-reads from disk to keep the snapshot independent of how the tool
     * formats its output (e.g. line-number prefixes).
     */
    private void snapshotForRecovery(ToolCallInfo call, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        if (!"ReadFile".equals(call.toolName())) return;
        Object pathObj = call.args() == null ? null : call.args().get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        String path = (String) pathObj;
        try {
            String content = Files.readString(Path.of(path));
            recoveryState.recordFileRead(path, content);
        } catch (IOException ignored) {
            // Best-effort snapshot; if the file vanished between the tool
            // call and now, just skip — the model has the tool output it
            // already saw.
        }
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }
}
