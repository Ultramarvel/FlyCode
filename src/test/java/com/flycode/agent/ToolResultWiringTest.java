package com.flycode.agent;

import com.flycode.conversation.ConversationManager;
import com.flycode.conversation.Message;
import com.flycode.conversation.ToolResultBlock;
import com.flycode.llm.LlmClient;
import com.flycode.llm.StreamEvent;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具结果预算在 Agent 主循环里的接线测试：驱动完整主循环，验证单条溢写、
 * 聚合溢写、回读豁免，以及进入对话历史的内容就是最终形态。
 */
class ToolResultWiringTest {

    /** 按脚本逐轮返回事件的假客户端。 */
    static class ScriptedClient implements LlmClient {
        private final List<List<StreamEvent>> scripts;
        private int callIdx = 0;

        ScriptedClient(List<List<StreamEvent>> scripts) { this.scripts = scripts; }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            var q = new LinkedBlockingQueue<StreamEvent>();
            List<StreamEvent> script = callIdx < scripts.size()
                    ? scripts.get(callIdx++)
                    : List.of(new StreamEvent.TextDelta("no more"), new StreamEvent.StreamEnd("end_turn", 1, 1));
            q.addAll(script);
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    /** 返回固定内容的假工具。 */
    static class FixedTool implements Tool {
        private final String name;
        private final String output;

        FixedTool(String name, String output) { this.name = name; this.output = output; }

        @Override public String name() { return name; }
        @Override public String description() { return "fixed output"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public Map<String, Object> schema() {
            return Map.of("name", name, "description", "fixed",
                    "input_schema", Map.of("type", "object", "properties", Map.of()));
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success(output);
        }
    }

    static void drain(Agent agent, ConversationManager conv) throws Exception {
        BlockingQueue<AgentEvent> q = agent.run(conv);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent ev = q.poll(1, java.util.concurrent.TimeUnit.SECONDS);
            if (ev instanceof AgentEvent.LoopComplete || ev instanceof AgentEvent.ErrorEvent) return;
        }
        fail("agent did not complete in time");
    }

    private static Message toolResultsMsg(ConversationManager conv) {
        for (Message m : conv.getMessages()) {
            if (m.getToolResults() != null && !m.getToolResults().isEmpty()) return m;
        }
        throw new AssertionError("no tool-results message in conversation");
    }

    private static List<StreamEvent> endTurn() {
        return List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn", 1, 1));
    }

    @Test
    void ingestSpillsSingleOversizedResult(@TempDir Path dir) throws Exception {
        var client = new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("t1", "BigTool", Map.of()),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                endTurn()));
        var registry = new ToolRegistry();
        registry.register(new FixedTool("BigTool", "x".repeat(60_000)));
        var agent = new Agent(client, registry, "anthropic", new com.flycode.config.ProviderConfig());
        agent.setWorkDir(dir.toString());
        var conv = new ConversationManager();
        conv.addUserMessage("go");

        drain(agent, conv);

        // 进历史的内容是预览，不是原文
        ToolResultBlock tr = toolResultsMsg(conv).getToolResults().get(0);
        assertTrue(tr.content().startsWith("<persisted-output>"),
                "history content should be a preview");
        // 溢写文件保存了完整原文
        Path spill = com.flycode.toolresult.ToolResultBudget.spillDir(dir.toString(), null).resolve("t1.txt");
        assertTrue(Files.exists(spill), "spill file missing");
        assertEquals(60_000, Files.size(spill));
    }

    @Test
    void ingestReadbackExempt(@TempDir Path dir) throws Exception {
        String readbackPath = com.flycode.toolresult.ToolResultBudget.spillDir(dir.toString(), null).resolve("toolu_old.txt").toString();
        var client = new ScriptedClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("t_rb", "ReadFile",
                                Map.of("file_path", readbackPath)),
                        new StreamEvent.StreamEnd("tool_use", 1, 1)),
                endTurn()));
        var registry = new ToolRegistry();
        registry.register(new FixedTool("ReadFile", "y".repeat(60_000)));
        var agent = new Agent(client, registry, "anthropic", new com.flycode.config.ProviderConfig());
        agent.setWorkDir(dir.toString());
        var conv = new ConversationManager();
        conv.addUserMessage("read it back");

        drain(agent, conv);

        // 回读结果豁免溢写：原文进历史，且没有生成新的溢写文件
        ToolResultBlock tr = toolResultsMsg(conv).getToolResults().get(0);
        assertEquals(60_000, tr.content().length(), "readback should stay raw");
        assertFalse(Files.exists(com.flycode.toolresult.ToolResultBudget.spillDir(dir.toString(), null).resolve("t_rb.txt")),
                "readback result must not be spilled");
    }

    @Test
    void ingestAggregateSpillsLargest(@TempDir Path dir) throws Exception {
        Map<String, Integer> sizes = Map.of(
                "T1", 45_000, "T2", 45_000, "T3", 45_001, "T4", 45_000, "T5", 45_000);
        var registry = new ToolRegistry();
        var calls = new ArrayList<StreamEvent>();
        for (String name : List.of("T1", "T2", "T3", "T4", "T5")) {
            registry.register(new FixedTool(name, "z".repeat(sizes.get(name))));
            calls.add(new StreamEvent.ToolCallComplete("t" + name.substring(1).toLowerCase(), name, Map.of()));
        }
        calls.add(new StreamEvent.StreamEnd("tool_use", 1, 1));
        var client = new ScriptedClient(List.of(calls, endTurn()));
        var agent = new Agent(client, registry, "anthropic", new com.flycode.config.ProviderConfig());
        agent.setWorkDir(dir.toString());
        var conv = new ConversationManager();
        conv.addUserMessage("fan out");

        drain(agent, conv);

        Message msg = toolResultsMsg(conv);
        long total = 0;
        int previews = 0;
        String t3 = null;
        for (ToolResultBlock tr : msg.getToolResults()) {
            total += tr.content().length();
            if (tr.content().startsWith("<persisted-output>")) previews++;
            if ("t3".equals(tr.toolUseId())) t3 = tr.content();
        }
        assertTrue(total <= 200_000, "aggregate still over limit: " + total);
        assertEquals(1, previews, "exactly one result should be spilled");
        assertNotNull(t3);
        assertTrue(t3.startsWith("<persisted-output>"), "largest result t3 should be the one spilled");
    }
}
