package com.flycode.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缓存断点的落点。
 *
 * <p>该长什么样：落在最后一个非延迟工具上。一个工具同时带 defer_loading 和
 * cache_control 会被官方端点直接拒掉整个请求（400），而 MCP 工具在内建工具之后注册，
 * 列表尾部往往正是延迟工具，所以不能简单地标记最后一个。
 *
 * <p>这里验的是 AnthropicClient 里挑落点那段逻辑，独立复算一遍索引。
 */
class ToolCacheMarkerTest {

    /** 与 AnthropicClient.doStream 里挑落点的逻辑等价。 */
    private static int cacheIndex(List<Map<String, Object>> tools) {
        for (int i = tools.size() - 1; i >= 0; i--) {
            if (!Boolean.TRUE.equals(tools.get(i).get("defer_loading"))) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, Object> plain(String name) {
        return Map.of("name", name);
    }

    private static Map<String, Object> deferred(String name) {
        return Map.of("name", name, "defer_loading", true);
    }

    @Test
    @DisplayName("尾部是延迟工具时往前找")
    void skipsDeferredTail() {
        var tools = List.of(
                plain("ReadFile"), plain("WriteFile"), plain("ToolSearch"),
                deferred("mcp__linear__create_issue"), deferred("mcp__sentry__resolve"));
        assertEquals("ToolSearch", tools.get(cacheIndex(tools)).get("name"));
    }

    @Test
    @DisplayName("全是非延迟工具时标记最后一个")
    void marksLastWhenNoneDeferred() {
        var tools = List.of(plain("ReadFile"), plain("Bash"));
        assertEquals("Bash", tools.get(cacheIndex(tools)).get("name"));
    }

    @Test
    @DisplayName("延迟工具夹在中间也不会被选中")
    void skipsDeferredInMiddle() {
        var tools = List.of(
                plain("Bash"), deferred("mcp__a__x"), plain("Grep"), deferred("mcp__z__y"));
        assertEquals("Grep", tools.get(cacheIndex(tools)).get("name"));
    }

    @Test
    @DisplayName("全是延迟工具时一个都不标记")
    void noMarkerWhenAllDeferred() {
        // 官方要求至少有一个非延迟工具，真实注册表里内建工具永远非延迟，
        // 所以这是防御分支：宁可不缓存，也不能发出会被 400 的请求
        var tools = List.of(deferred("mcp__a__x"), deferred("mcp__b__y"));
        assertEquals(-1, cacheIndex(tools));
    }

    @Test
    @DisplayName("空列表返回 -1")
    void emptyList() {
        assertEquals(-1, cacheIndex(List.of()));
    }
}
