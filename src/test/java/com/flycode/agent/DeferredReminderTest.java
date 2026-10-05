package com.flycode.agent;

import com.flycode.conversation.ConversationManager;
import com.flycode.llm.LlmClient;
import com.flycode.llm.StreamEvent;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.ToolResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 延迟工具清单提醒的注入时机，驱动的是真实主循环。
 *
 * <p>这条提醒是加进历史的，发一次就一直在上下文里，所以每轮重发只是拿相同内容占
 * 窗口，六十来个 MCP 工具一份清单五百多 token，四十轮下来两万多。
 *
 * <p>该长什么样：一场多轮的工具调用里只出现一次；池子变了补一次；历史被压掉之后
 * 重新发。
 */
class DeferredReminderTest {

    /** 按脚本逐轮返回事件的假客户端。 */
    static class ScriptedClient implements LlmClient {
        private final List<List<StreamEvent>> scripts;
        int callIdx = 0;

        ScriptedClient(List<List<StreamEvent>> scripts) { this.scripts = scripts; }

        @Override
        public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
            var q = new LinkedBlockingQueue<StreamEvent>();
            q.addAll(callIdx < scripts.size()
                    ? scripts.get(callIdx++)
                    : List.of(new StreamEvent.TextDelta("no more"),
                              new StreamEvent.StreamEnd("end_turn", 1, 1)));
            return q;
        }

        @Override
        public void setSystemPrompt(String prompt) {}
    }

    /** 普通工具，被主循环调用来撑出多个轮次。 */
    record EchoTool() implements Tool {
        @Override public String name() { return "Echo"; }
        @Override public String description() { return "echo"; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public Map<String, Object> schema() {
            return Map.of("name", "Echo", "description", "echo",
                    "input_schema", Map.of("type", "object", "properties", Map.of()));
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success("echoed");
        }
    }

    /** 带延迟标记的占位工具，用来把 registry 推进「有延迟工具」的状态。 */
    record DeferredStub(String toolName) implements Tool {
        @Override public String name() { return toolName; }
        @Override public String description() { return toolName; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public boolean shouldDefer() { return true; }
        @Override public Map<String, Object> schema() {
            return Map.of("name", toolName, "input_schema", Map.of("type", "object"));
        }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success("ok");
        }
    }

    private static void drain(Agent agent, ConversationManager conv) throws Exception {
        BlockingQueue<AgentEvent> q = agent.run(conv);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            AgentEvent ev = q.poll(1, TimeUnit.SECONDS);
            if (ev instanceof AgentEvent.LoopComplete || ev instanceof AgentEvent.ErrorEvent) return;
        }
        fail("agent did not complete in time");
    }

    private static Agent agentFor(LlmClient client, ToolRegistry registry, Path dir) {
        var agent = new Agent(client, registry, "anthropic", new com.flycode.config.ProviderConfig());
        agent.setWorkDir(dir.toString());
        return agent;
    }

    private static long count(ConversationManager conv) {
        return conv.getMessages().stream()
                .filter(m -> "user".equals(m.getRole())
                        && m.getContent() != null
                        && m.getContent().contains(Agent.DEFERRED_REMINDER_MARKER))
                .count();
    }

    private static List<StreamEvent> toolTurn(String id) {
        return List.of(new StreamEvent.ToolCallComplete(id, "Echo", Map.of()),
                new StreamEvent.StreamEnd("tool_use", 1, 1));
    }

    private static List<StreamEvent> endTurn() {
        return List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("end_turn", 1, 1));
    }

    @Test
    @DisplayName("四个轮次里只注入一次")
    void injectedOnceAcrossIterations(@TempDir Path dir) throws Exception {
        // 三轮工具调用 + 一轮收尾，主循环一共转四次
        var client = new ScriptedClient(List.of(
                toolTurn("t1"), toolTurn("t2"), toolTurn("t3"), endTurn()));
        var registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new DeferredStub("mcp__linear__create_issue"));
        registry.register(new DeferredStub("mcp__sentry__resolve_issue"));

        var conv = new ConversationManager();
        conv.addUserMessage("do three things");
        drain(agentFor(client, registry, dir), conv);

        assertEquals(4, client.callIdx, "主循环该转 4 次");
        assertEquals(1, count(conv), "四轮下来清单该只注入 1 次");
    }

    @Test
    @DisplayName("工具池变了补一次")
    void reannouncedWhenPoolChanges(@TempDir Path dir) throws Exception {
        var registry = new ToolRegistry();
        registry.register(new DeferredStub("mcp__linear__create_issue"));
        var conv = new ConversationManager();

        conv.addUserMessage("第一个回合");
        drain(agentFor(new ScriptedClient(List.of(endTurn())), registry, dir), conv);
        assertEquals(1, count(conv), "首个回合该注入 1 次");

        // MCP 服务器姗姗来迟，池子多出一个工具
        registry.register(new DeferredStub("mcp__infra__scale_service"));
        conv.addUserMessage("第二个回合");
        drain(agentFor(new ScriptedClient(List.of(endTurn())), registry, dir), conv);
        assertEquals(2, count(conv), "池子变化后该补 1 次");
    }

    @Test
    @DisplayName("历史被压掉之后重新宣告")
    void reannouncedAfterHistoryWiped(@TempDir Path dir) throws Exception {
        var registry = new ToolRegistry();
        registry.register(new DeferredStub("mcp__linear__create_issue"));
        var conv = new ConversationManager();

        conv.addUserMessage("第一个回合");
        drain(agentFor(new ScriptedClient(List.of(endTurn())), registry, dir), conv);
        assertEquals(1, count(conv), "首个回合该注入 1 次");

        // 模拟 compact：历史被压成一条摘要，那条提醒随之消失
        conv.getMessagesMutable().clear();
        conv.addUserMessage("summary of earlier conversation");

        drain(agentFor(new ScriptedClient(List.of(endTurn())), registry, dir), conv);
        assertEquals(1, count(conv), "历史被压掉后该重新宣告");
    }

    @Test
    @DisplayName("延迟工具名按字典序返回，顺序稳定")
    void namesAreSorted() {
        var registry = new ToolRegistry();
        for (var n : List.of("mcp__z__b", "mcp__a__c", "mcp__m__a")) {
            registry.register(new DeferredStub(n));
        }
        var want = List.of("mcp__a__c", "mcp__m__a", "mcp__z__b");
        assertEquals(want, registry.getDeferredToolNames());
        assertEquals(want, registry.getDeferredToolNames());
    }

    @Test
    @DisplayName("没有延迟工具时完全不注入")
    void noReminderWithoutDeferredTools(@TempDir Path dir) throws Exception {
        var conv = new ConversationManager();
        conv.addUserMessage("hi");
        drain(agentFor(new ScriptedClient(List.of(endTurn())), new ToolRegistry(), dir), conv);
        assertEquals(0, count(conv));
    }
}
