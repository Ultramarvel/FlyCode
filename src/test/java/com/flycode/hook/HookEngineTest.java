package com.flycode.hook;

import com.flycode.hook.HookEngine.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hook 引擎单元测试。
 */
class HookEngineTest {

    // ---- 条件求值：叶子操作符测试 ----

    @Test
    void testEvaluateConditionLeafOps() {
        var ctx = new HookContext(
                EventName.PRE_TOOL_USE, "Bash",
                Map.of("command", "rm -rf /"),
                "src/foo.go", null, null);

        // == 精确匹配
        assertTrue(HookEngine.evaluateCondition("tool == \"Bash\"", ctx));
        assertFalse(HookEngine.evaluateCondition("tool == \"Read\"", ctx));

        // != 不等操作符
        assertTrue(HookEngine.evaluateCondition("tool != \"Read\"", ctx));
        assertFalse(HookEngine.evaluateCondition("tool != \"Bash\"", ctx));

        // =~ 正则匹配
        assertTrue(HookEngine.evaluateCondition("event =~ /^pre_/", ctx));
        assertTrue(HookEngine.evaluateCondition("args.command =~ /rm -rf/", ctx));

        // =* glob 匹配
        assertTrue(HookEngine.evaluateCondition("file_path =* \"src/*.go\"", ctx));
        assertFalse(HookEngine.evaluateCondition("file_path =* \"src/*.py\"", ctx));

        // && 复合条件
        assertTrue(HookEngine.evaluateCondition(
                "tool == \"Bash\" && file_path =* \"src/*.go\"", ctx));
        assertFalse(HookEngine.evaluateCondition(
                "tool == \"Bash\" && file_path =* \"src/*.py\"", ctx));

        // || 复合条件
        assertTrue(HookEngine.evaluateCondition(
                "tool == \"Read\" || tool == \"Bash\"", ctx));
        assertFalse(HookEngine.evaluateCondition(
                "tool == \"Read\" || tool == \"Write\"", ctx));

        // ! 取反
        assertTrue(HookEngine.evaluateCondition("!tool == \"Read\"", ctx));
    }

    // ---- Pre-tool hook reject 测试 ----

    @Test
    void testRunPreToolHooksReject() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "block-rm-rf", EventName.PRE_TOOL_USE,
                "tool == \"Bash\" && args.command =~ /rm -rf/",
                new Action(ActionType.PROMPT, null, "destructive command blocked"),
                true)));

        var result = engine.runPreToolHooks("Bash", Map.of("command", "rm -rf /tmp/x"));
        assertTrue(result.rejected());
        assertTrue(result.message().contains("destructive command blocked"));
    }

    @Test
    void testRunPreToolHooksAllowsWhenConditionFails() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "block-go", EventName.PRE_TOOL_USE,
                "file_path =* \"**/*.go\"",
                new Action(ActionType.PROMPT, null, "blocked"),
                true)));

        // filePath 是 .py，不匹配 .go glob，应放行
        var result = engine.runPreToolHooks("WriteFile", Map.of());
        assertFalse(result.rejected());
    }

    // ---- once 单次触发测试 ----

    @Test
    void testHookOnceOnlyFiresOnce() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "greet", EventName.SESSION_START, null,
                new Action(ActionType.PROMPT, null, "hello"),
                false, true, false, null)));

        var res1 = engine.runHooks(new HookContext(EventName.SESSION_START, null, null, null, null, null));
        var res2 = engine.runHooks(new HookContext(EventName.SESSION_START, null, null, null, null, null));
        assertEquals(1, res1.size(), "首次触发应产生 1 个结果");
        assertEquals(0, res2.size(), "第二次触发应为空（once）");
    }

    // ---- async 异步测试 ----

    @Test
    void testHookAsyncIsNonBlocking() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "slow", EventName.TURN_END, null,
                new Action(ActionType.COMMAND, "sleep 0.2", null),
                false, false, true, null)));

        long start = System.nanoTime();
        var res = engine.runHooks(new HookContext(EventName.TURN_END, null, null, null, null, null));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 异步 hook 不应阻塞调用方
        assertTrue(elapsed < 150, "async hook 阻塞了 " + elapsed + "ms");
        assertEquals(1, res.size());
        assertEquals("(async)", res.get(0).output());
    }

    // ---- onError=reject 测试 ----

    @Test
    void testHookOnErrorReject() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "fail", EventName.PRE_TOOL_USE, null,
                new Action(ActionType.COMMAND, "exit 7", null),
                false, false, false, "reject")));

        var result = engine.runPreToolHooks("Bash", Map.of());
        assertTrue(result.rejected(), "命令失败 + onError=reject 应触发拦截");
    }

    // ---- 配置校验测试 ----

    @Test
    void testValidateCatchesMissingFields() {
        // command 类型缺少 command 字段
        var errors1 = HookEngine.validate(List.of(new Hook(
                "no-cmd", EventName.PRE_TOOL_USE, null,
                new Action(ActionType.COMMAND, null, null),
                false)));
        assertTrue(errors1.stream().anyMatch(e -> e.contains("action.command must be non-empty")));

        // prompt 类型缺少 message 字段
        var errors2 = HookEngine.validate(List.of(new Hook(
                "no-msg", EventName.SESSION_START, null,
                new Action(ActionType.PROMPT, null, null),
                false)));
        assertTrue(errors2.stream().anyMatch(e -> e.contains("action.message must be non-empty")));

        // http 类型缺少 url 字段
        var errors3 = HookEngine.validate(List.of(new Hook(
                "no-url", EventName.POST_TOOL_USE, null,
                new Action(ActionType.HTTP, null, null, null, null, null, null, Duration.ZERO),
                false)));
        assertTrue(errors3.stream().anyMatch(e -> e.contains("action.url must be non-empty")));

        // http 类型无效 url
        var errors4 = HookEngine.validate(List.of(new Hook(
                "bad-url", EventName.POST_TOOL_USE, null,
                new Action(ActionType.HTTP, null, null, "not-a-url", null, null, null, Duration.ZERO),
                false)));
        assertTrue(errors4.stream().anyMatch(e -> e.contains("action.url must be a valid http(s) URL")));
    }

    @Test
    void testValidateAcceptsGoodConfig() {
        var hooks = List.of(
                new Hook("fmt", EventName.POST_TOOL_USE, null,
                        new Action(ActionType.COMMAND, "echo ok", null), false),
                new Hook("ctx", EventName.SESSION_START, null,
                        new Action(ActionType.PROMPT, null, "hello"), false),
                new Hook("slack", EventName.POST_TOOL_USE, null,
                        new Action(ActionType.HTTP, null, null, "https://hooks.slack.com/services/xxx",
                                null, null, null, Duration.ZERO), false),
                new Hook("review", EventName.POST_TOOL_USE, null,
                        new Action(ActionType.AGENT, null, "review the change"), false)
        );
        var errors = HookEngine.validate(hooks);
        assertTrue(errors.isEmpty(), "合法配置不应有错误: " + errors);
    }

    // ---- 命令超时测试 ----

    @Test
    void testRunCommandTimeout() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "slow", EventName.POST_TOOL_USE, null,
                new Action(ActionType.COMMAND, "sleep 5", null, null, null, null, null,
                        Duration.ofMillis(200)),
                false)));

        long start = System.nanoTime();
        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "Bash", null, null, null, null));
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("timed out"));
        assertTrue(elapsed < 2000, "超时命令应在 200ms 内被终止，实际 " + elapsed + "ms");
    }

    // ---- FLYCODE_FILE_PATH 环境变量测试 ----

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void testCommandInjectsFilePathEnvVar() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "echo-path", EventName.POST_TOOL_USE, null,
                new Action(ActionType.COMMAND, "echo $FLYCODE_FILE_PATH", null),
                false)));

        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "WriteFile", null,
                        "/tmp/test.txt", null, null));
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("/tmp/test.txt", results.get(0).output());
    }

    // ---- HookContext.expand 模板变量替换测试 ----

    @Test
    void testHookContextExpand() {
        var ctx = new HookContext(
                EventName.POST_TOOL_USE, "Bash",
                Map.of("file", "main.go"),
                "/src/main.go", "hello world", null);

        assertEquals("tool=Bash path=/src/main.go",
                ctx.expand("tool=${tool} path=${file_path}"));
        assertEquals("file=main.go",
                ctx.expand("file=${args.file}"));
        assertEquals("no template here",
                ctx.expand("no template here"));
    }

    // ---- agent action 测试 ----

    @Test
    void testAgentActionWithoutRunner() {
        var engine = new HookEngine();
        engine.loadHooks(List.of(new Hook(
                "review", EventName.POST_TOOL_USE, null,
                new Action(ActionType.AGENT, null, "review changes"),
                false)));

        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "Bash", null, null, null, null));
        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        assertTrue(results.get(0).output().contains("no AgentRunner registered"));
    }

    @Test
    void testAgentActionWithRunner() {
        var engine = new HookEngine();
        engine.setAgentRunner((prompt, ctx) -> "reviewed: " + prompt);
        engine.loadHooks(List.of(new Hook(
                "review", EventName.POST_TOOL_USE, null,
                new Action(ActionType.AGENT, null, "review changes"),
                false)));

        var results = engine.runHooks(
                new HookContext(EventName.POST_TOOL_USE, "Bash", null, null, null, null));
        assertEquals(1, results.size());
        assertTrue(results.get(0).success());
        assertEquals("reviewed: review changes", results.get(0).output());
    }
}
