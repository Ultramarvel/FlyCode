package com.flycode.tool;

import com.flycode.mcp.McpLoadingStrategy;
import com.flycode.mcp.McpManager;
import com.flycode.permission.PermissionChecker;
import com.flycode.tool.impl.McpCallTool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpCallToolTest {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "issueId", Map.of("type", "string"),
                    "limit", Map.of("type", "integer"),
                    "ratio", Map.of("type", "number"),
                    "flag", Map.of("type", "boolean"),
                    "labels", Map.of("type", "array", "items", Map.of("type", "string")),
                    "ports", Map.of("type", "array", "items", Map.of("type", "integer")),
                    "config", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "replicas", Map.of("type", "integer"),
                                    "features", Map.of("type", "array", "items", Map.of("type", "string"))
                            )
                    )
            )
    );

    /** 够用的 MCP 工具替身：暴露 schema、记录收到的参数。 */
    private static final class FakeMcpTool implements McpToolLike {
        private final String server;
        private final String toolName;
        private final Map<String, Object> schema;
        private boolean deferred = true;
        Map<String, Object> received;

        FakeMcpTool(String server, String toolName, Map<String, Object> schema) {
            this.server = server;
            this.toolName = toolName;
            this.schema = schema;
        }

        @Override public String name() { return McpManager.buildMcpToolName(server, toolName); }
        @Override public String description() { return "fake"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public boolean shouldDefer() { return deferred; }
        @Override public String mcpServerName() { return server; }
        @Override public Map<String, Object> mcpInputSchema() { return schema; }
        @Override public void setDeferLoading(boolean on) { this.deferred = on; }

        @Override public Map<String, Object> schema() {
            return Map.of("name", name(), "description", description(), "input_schema", schema);
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            this.received = args;
            return ToolResult.success("ok");
        }
    }

    private static Map<String, Object> args(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── 强转契约：这七条四个语言必须逐条一致 ────────────────────────────

    @Test
    void stringFromInteger() {
        assertEquals(Map.of("issueId", "8891"),
                McpCallTool.coerceBySchema(Map.of("issueId", 8891), SCHEMA));
    }

    @Test
    void stringFromDecimal() {
        assertEquals(Map.of("issueId", "1.5"),
                McpCallTool.coerceBySchema(Map.of("issueId", 1.5), SCHEMA));
    }

    @Test
    void integerFromNumericString() {
        assertEquals(Map.of("limit", 5L),
                McpCallTool.coerceBySchema(Map.of("limit", "5"), SCHEMA));
    }

    @Test
    void numberFromNumericStringWithWhitespace() {
        assertEquals(Map.of("ratio", 1.5d),
                McpCallTool.coerceBySchema(Map.of("ratio", " 1.5 "), SCHEMA));
    }

    @Test
    void booleanFromTrue() {
        assertEquals(Map.of("flag", true),
                McpCallTool.coerceBySchema(Map.of("flag", "true"), SCHEMA));
    }

    @Test
    void booleanFromUppercaseFalse() {
        assertEquals(Map.of("flag", false),
                McpCallTool.coerceBySchema(Map.of("flag", "FALSE"), SCHEMA));
    }

    @Test
    void arrayUnwrappedFromSingleKeyObject() {
        assertEquals(Map.of("labels", List.of("a", "b")),
                McpCallTool.coerceBySchema(Map.of("labels", Map.of("item", List.of("a", "b"))), SCHEMA));
    }

    @Test
    void arrayFromCommaSeparatedString() {
        assertEquals(Map.of("labels", List.of("a", "b")),
                McpCallTool.coerceBySchema(Map.of("labels", "a, b"), SCHEMA));
    }

    @Test
    void arrayRecursesIntoItems() {
        assertEquals(Map.of("ports", List.of(8080L, 9090L)),
                McpCallTool.coerceBySchema(Map.of("ports", List.of("8080", "9090")), SCHEMA));
    }

    @Test
    void objectRecursesIntoProperties() {
        var given = Map.of("config", Map.of("replicas", "4", "features", Map.of("item", List.of("x"))));
        var want = Map.of("config", Map.of("replicas", 4L, "features", List.of("x")));
        assertEquals(want, McpCallTool.coerceBySchema(given, SCHEMA));
    }

    @Test
    void booleanIsNotTreatedAsNumber() {
        assertEquals(Map.of("issueId", true),
                McpCallTool.coerceBySchema(Map.of("issueId", true), SCHEMA));
    }

    @Test
    void uncoercibleValuesArePassedThrough() {
        // 转不了的原样保留，交给 MCP 服务器报它自己的错
        assertEquals(Map.of("limit", "many"),
                McpCallTool.coerceBySchema(Map.of("limit", "many"), SCHEMA));
        assertEquals(Map.of("flag", "yes"),
                McpCallTool.coerceBySchema(Map.of("flag", "yes"), SCHEMA));
        assertEquals(Map.of("limit", "5abc"),
                McpCallTool.coerceBySchema(Map.of("limit", "5abc"), SCHEMA));
        // "5.7" 配 integer 不截断，跟 Python / Go / TS 一致
        assertEquals(Map.of("limit", "5.7"),
                McpCallTool.coerceBySchema(Map.of("limit", "5.7"), SCHEMA));
    }

    @Test
    void keysAbsentFromSchemaAreUntouched() {
        assertEquals(Map.of("extra", 1),
                McpCallTool.coerceBySchema(Map.of("extra", 1), SCHEMA));
    }

    @Test
    void alreadyValidArgumentsAreUntouched() {
        var good = Map.of("issueId", "X-1", "limit", 3, "flag", false, "ports", List.of(1, 2));
        assertEquals(good, McpCallTool.coerceBySchema(good, SCHEMA));
    }

    @Test
    void emptySchemaIsNoOp() {
        assertEquals(Map.of("a", "1"), McpCallTool.coerceBySchema(Map.of("a", "1"), Map.of()));
    }

    // ── 工具名解析 ──────────────────────────────────────────────────────

    private record Fixture(ToolRegistry registry, McpCallTool dispatcher, FakeMcpTool tool) {}

    private static Fixture setup() {
        var registry = new ToolRegistry();
        registry.setMcpLoadingMode(McpLoadingMode.DISPATCH);
        var tool = new FakeMcpTool("linear", "create_issue", SCHEMA);
        registry.register(tool);
        var dispatcher = new McpCallTool(registry);
        registry.register(dispatcher);
        return new Fixture(registry, dispatcher, tool);
    }

    @Test
    void resolvesFullName() {
        var f = setup();
        var res = f.dispatcher().execute(
                args("server", "linear", "tool", "mcp__linear__create_issue",
                        "arguments", Map.of("issueId", "A")));
        assertFalse(res.isError());
        assertEquals(Map.of("issueId", "A"), f.tool().received);
    }

    @Test
    void resolvesServerPlusShortName() {
        // 模型很常只传短名（实测约三成调用），必须容错，否则白白多一轮重试
        var f = setup();
        var res = f.dispatcher().execute(
                args("server", "linear", "tool", "create_issue",
                        "arguments", Map.of("issueId", "A")));
        assertFalse(res.isError());
        assertEquals(Map.of("issueId", "A"), f.tool().received);
    }

    @Test
    void fallsBackToUniqueSuffixWhenServerIsWrong() {
        var f = setup();
        var res = f.dispatcher().execute(
                args("server", "typo", "tool", "create_issue",
                        "arguments", Map.of("issueId", "A")));
        assertFalse(res.isError());
        assertEquals(Map.of("issueId", "A"), f.tool().received);
    }

    @Test
    void ambiguousSuffixErrorsAndListsAvailableTools() {
        var registry = new ToolRegistry();
        registry.register(new FakeMcpTool("linear", "create_issue", Map.of()));
        registry.register(new FakeMcpTool("jira", "create_issue", Map.of()));
        var dispatcher = new McpCallTool(registry);
        var res = dispatcher.execute(args("server", "nope", "tool", "create_issue",
                "arguments", Map.of()));
        assertTrue(res.isError());
        assertTrue(res.output().contains("mcp__linear__create_issue"), res.output());
    }

    @Test
    void coercesBeforeForwarding() {
        var f = setup();
        f.dispatcher().execute(args("server", "linear", "tool", "create_issue",
                "arguments", args("issueId", 8891, "ports", List.of("1"))));
        assertEquals(args("issueId", "8891", "ports", List.of(1L)), f.tool().received);
    }

    @Test
    void missingToolNameErrors() {
        var f = setup();
        var res = f.dispatcher().execute(args("server", "linear", "arguments", Map.of()));
        assertTrue(res.isError());
    }

    // ── 三路分流 ────────────────────────────────────────────────────────

    @Test
    void officialEndpointDetection() {
        assertTrue(McpLoadingStrategy.isOfficialAnthropicEndpoint(""));
        assertTrue(McpLoadingStrategy.isOfficialAnthropicEndpoint(null));
        assertTrue(McpLoadingStrategy.isOfficialAnthropicEndpoint("https://api.anthropic.com"));
        assertFalse(McpLoadingStrategy.isOfficialAnthropicEndpoint("https://api.minimaxi.com/anthropic"));
    }

    @Test
    void smallSchemaLoadsEagerly() {
        assertEquals(McpLoadingMode.EAGER,
                McpLoadingStrategy.decideMode("https://proxy.example.com", 200_000, 1_000));
    }

    @Test
    void noMcpToolsLoadsEagerly() {
        assertEquals(McpLoadingMode.EAGER,
                McpLoadingStrategy.decideMode("https://proxy.example.com", 200_000, 0));
    }

    @Test
    void officialEndpointUsesNativeDeferral() {
        assertEquals(McpLoadingMode.NATIVE,
                McpLoadingStrategy.decideMode("", 200_000, 500_000));
    }

    @Test
    void thirdPartyEndpointUsesDispatch() {
        assertEquals(McpLoadingMode.DISPATCH,
                McpLoadingStrategy.decideMode("https://api.minimaxi.com/anthropic", 200_000, 500_000));
    }

    @Test
    void onlyMcpSchemasAreMeasured() {
        var registry = new ToolRegistry();
        assertEquals(0, McpLoadingStrategy.measureSchemaChars(registry));
        registry.register(new FakeMcpTool("linear", "create_issue", SCHEMA));
        assertTrue(McpLoadingStrategy.measureSchemaChars(registry) > 0);
    }

    // ── applyMode 对 tools[] 的影响 ─────────────────────────────────────

    private static List<Map<String, Object>> mcpSchemas(ToolRegistry registry, String protocol) {
        var out = new ArrayList<Map<String, Object>>();
        for (var s : registry.getAllSchemas(protocol)) {
            if (String.valueOf(s.get("name")).startsWith(McpManager.MCP_TOOL_PREFIX)) out.add(s);
        }
        return out;
    }

    @Test
    void eagerPutsMcpToolsInArrayWithoutDeferLoading() {
        var registry = new ToolRegistry();
        registry.register(new FakeMcpTool("linear", "create_issue", SCHEMA));
        McpLoadingStrategy.applyMode(registry, McpLoadingMode.EAGER);
        var mcp = mcpSchemas(registry, "anthropic");
        assertEquals(1, mcp.size());
        assertNull(mcp.get(0).get("defer_loading"));
    }

    @Test
    void nativeKeepsMcpToolsInArrayWithDeferLoading() {
        var registry = new ToolRegistry();
        registry.register(new FakeMcpTool("linear", "create_issue", SCHEMA));
        McpLoadingStrategy.applyMode(registry, McpLoadingMode.NATIVE);
        var mcp = mcpSchemas(registry, "anthropic");
        assertEquals(1, mcp.size());
        assertEquals(true, mcp.get(0).get("defer_loading"));
    }

    @Test
    void dispatchKeepsMcpToolsOutOfArray() {
        var registry = new ToolRegistry();
        registry.register(new FakeMcpTool("linear", "create_issue", SCHEMA));
        McpLoadingStrategy.applyMode(registry, McpLoadingMode.DISPATCH);
        assertEquals(0, mcpSchemas(registry, "anthropic").size());
    }

    @Test
    void openAiProtocolNeverCarriesDeferLoading() {
        // defer_loading 是 Anthropic 的字段
        var registry = new ToolRegistry();
        registry.register(new FakeMcpTool("linear", "create_issue", SCHEMA));
        McpLoadingStrategy.applyMode(registry, McpLoadingMode.NATIVE);
        assertEquals(0, mcpSchemas(registry, "openai").size());
    }

    // ── 按模式决定发哪些工具 ────────────────────────────────────────────

    /**
     * 检索和分发只在用得上的模式里发给模型。EAGER 下 MCP 工具全在 tools[] 里，
     * 既没有可搜的对象也不需要分发入口，两个都发过去只是白占 token。
     */
    private static List<String> exposedNames(McpLoadingMode mode) {
        var registry = new ToolRegistry();
        registry.register(new com.flycode.tool.impl.ToolSearchTool(registry));
        registry.register(new McpCallTool(registry));
        registry.register(new FakeMcpTool("linear", "create_issue", SCHEMA));
        McpLoadingStrategy.applyMode(registry, mode);
        var names = new ArrayList<String>();
        for (var s : registry.getAllSchemas("anthropic")) names.add(String.valueOf(s.get("name")));
        names.sort(null);
        return names;
    }

    @Test
    void eagerSendsNeitherHelperTool() {
        assertEquals(List.of("mcp__linear__create_issue"), exposedNames(McpLoadingMode.EAGER));
    }

    @Test
    void nativeSendsToolSearchOnly() {
        assertEquals(List.of("ToolSearch", "mcp__linear__create_issue"),
                exposedNames(McpLoadingMode.NATIVE));
    }

    @Test
    void dispatchSendsBothHelpersAndHidesMcpTools() {
        assertEquals(List.of("ToolSearch", "mcp_call"), exposedNames(McpLoadingMode.DISPATCH));
    }

    @Test
    void withoutMcpNeitherHelperIsSent() {
        // 没连 MCP 时 applyMode 不会被调用，两个开关保持默认关闭
        var registry = new ToolRegistry();
        registry.register(new com.flycode.tool.impl.ToolSearchTool(registry));
        registry.register(new McpCallTool(registry));
        assertEquals(0, registry.getAllSchemas("anthropic").size());
    }

    // ── 权限 content 归一化 ─────────────────────────────────────────────

    @Test
    void permissionContentNormalizesToServerDoubleUnderscoreTool() {
        assertEquals("linear__create_issue",
                McpCallTool.permissionContent("linear", "mcp__linear__create_issue"));
        assertEquals("linear__create_issue",
                McpCallTool.permissionContent("linear", "create_issue"));
        assertEquals("chrome_2__click",
                McpCallTool.permissionContent("chrome-2", "mcp__chrome_2__click"));
        // 短名和全名必须算出同一个 content，否则规则会漏匹配
        assertEquals("chrome_devtools__click",
                McpCallTool.permissionContent("chrome-devtools", "click"));
        assertEquals("chrome_devtools__click",
                McpCallTool.permissionContent("chrome-devtools", "mcp__chrome_devtools__click"));
    }

    @Test
    void extractContentRoutesMcpCallThroughNormalization() {
        assertEquals("linear__create_issue", PermissionChecker.extractContent(
                "mcp_call", Map.of("server", "linear", "tool", "mcp__linear__create_issue")));
    }

    @Test
    void extractContentForOtherToolsIsUnchanged() {
        assertEquals("ls", PermissionChecker.extractContent("Bash", Map.of("command", "ls")));
        // 表外的工具没有可匹配内容，返回空串让它照样进规则层
        assertEquals("", PermissionChecker.extractContent(
                "mcp__linear__create_issue", Map.of("title", "x")));
    }

    // ── 原生延迟的 beta header ──────────────────────────────────────────

    /**
     * beta header 的开关条件：只有工具真带了 defer_loading 才发。
     *
     * <p>官方端点这条路没法拿第三方端点真机验证，这里只能盯住请求该长什么样：
     * header 漏了，defer_loading 会被服务端直接拒；header 多发了，不认识它的
     * 端点也会拒。两头都是硬失败。
     */
    @Test
    void betaHeaderOnlyWhenSomeToolIsDeferred() {
        assertFalse(com.flycode.llm.AnthropicClient.needsToolSearchBeta(null));
        assertFalse(com.flycode.llm.AnthropicClient.needsToolSearchBeta(List.of()));
        assertFalse(com.flycode.llm.AnthropicClient.needsToolSearchBeta(List.of(
                Map.of("name", "Bash"), Map.of("name", "ToolSearch"))));
        assertTrue(com.flycode.llm.AnthropicClient.needsToolSearchBeta(List.of(
                Map.of("name", "Bash"),
                Map.of("name", "mcp__linear__x", "defer_loading", true))));
        assertFalse(com.flycode.llm.AnthropicClient.needsToolSearchBeta(List.of(
                Map.of("name", "x", "defer_loading", false))));
    }

    // ── 工具命名 ────────────────────────────────────────────────────────

    @Test
    void toolNamesUseDoubleUnderscoreSeparator() {
        assertEquals("mcp__linear__create_issue",
                McpManager.buildMcpToolName("linear", "create_issue"));
    }

    @Test
    void dashesAndDotsBecomeUnderscores() {
        assertEquals("mcp__chrome_devtools__take_snapshot",
                McpManager.buildMcpToolName("chrome-devtools", "take.snapshot"));
    }

    @Test
    void prefixHelperMatchesBuiltName() {
        assertTrue(McpManager.buildMcpToolName("chrome-2", "click")
                .startsWith(McpManager.mcpToolNamePrefix("chrome-2")));
    }
}
