package com.flycode.agent;

import com.flycode.conversation.ConversationManager;
import com.flycode.llm.StreamEvent;
import com.flycode.memory.MemoryRecall.RecallResult;
import com.flycode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 记忆召回在主循环里的接线测试：召回结果只在工具执行后注入，
 * 且只有真正注入的记忆才记为已注入。
 */
class MemoryRecallWiringTest {

    private static final String REMINDER = "## Memory: a.md";

    private static boolean reminderInConv(ConversationManager conv) {
        return conv.getMessages().stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains(REMINDER));
    }

    /** 有工具调用的一轮：召回结果在工具结果之后注入，同时记为已注入。 */
    @Test
    void injectedAfterTools(@TempDir Path dir) throws Exception {
        var client = new ToolResultWiringTest.ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("t1", "ReadFile", Map.of()),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn", 1, 1))));
        var registry = new ToolRegistry();
        registry.register(new ToolResultWiringTest.FixedTool("ReadFile", "ok"));
        var agent = new Agent(client, registry, "anthropic", new com.flycode.config.ProviderConfig());
        agent.setWorkDir(dir.toString());
        agent.setMemoryRecallFuture(CompletableFuture.completedFuture(
                new RecallResult(REMINDER, List.of("/mem/a.md"))));
        var conv = new ConversationManager();
        conv.addUserMessage("read it");

        ToolResultWiringTest.drain(agent, conv);

        assertTrue(reminderInConv(conv), "recall reminder should be injected after tool results");
        assertTrue(agent.surfacedMemPathsSnapshot().contains("/mem/a.md"),
                "injected memory should be marked surfaced");
    }

    /** 没有工具调用的一轮：召回结果没被消费，对应记忆不能记为已注入。 */
    @Test
    void notSurfacedWithoutTools(@TempDir Path dir) throws Exception {
        var client = new ToolResultWiringTest.ScriptedClient(List.of(
                List.of(new StreamEvent.TextDelta("plain answer"), new StreamEvent.StreamEnd("end_turn", 1, 1))));
        var agent = new Agent(client, new ToolRegistry(), "anthropic", new com.flycode.config.ProviderConfig());
        agent.setWorkDir(dir.toString());
        agent.setMemoryRecallFuture(CompletableFuture.completedFuture(
                new RecallResult(REMINDER, List.of("/mem/a.md"))));
        var conv = new ConversationManager();
        conv.addUserMessage("hi");

        ToolResultWiringTest.drain(agent, conv);

        assertFalse(reminderInConv(conv), "recall reminder must not be injected when no tool ran");
        assertTrue(agent.surfacedMemPathsSnapshot().isEmpty(),
                "unconsumed recall must not be marked surfaced");
    }
}
