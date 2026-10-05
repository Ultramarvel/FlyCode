package com.flycode.tool;

import com.flycode.tool.impl.McpCallTool;
import com.flycode.tool.impl.ToolSearchTool;

import java.util.*;

public class ToolRegistry {

    /**
     * 单条工具结果进入对话历史前的溢写阈值：超过这个字符数就把完整内容写盘，
     * 历史里只留预览和文件路径。定在 50000 而不是更小的值，是为了让模型一次
     * 能看到足够多的内容，不必为了看全结果再发一轮 ReadFile。
     */
    public static final int MAX_OUTPUT_CHARS = 50_000;

    private final Map<String, Tool> tools = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> discoveredTools = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * MCP 工具的加载方式，由 McpLoadingStrategy 在连上服务器后写入。ToolSearch 靠它
     * 决定回什么、client 靠它决定要不要发 defer_loading。没有 MCP 时保持 EAGER，
     * 行为等同于不延迟。
     */
    private volatile McpLoadingMode mcpLoadingMode = McpLoadingMode.EAGER;

    /**
     * 检索和分发这两个工具发不发给模型，由 McpLoadingStrategy 在会话启动时算一次。
     * 不每轮按「当前还有没有延迟工具」现算：工具可能被运行时禁用，现算会让 tools[]
     * 中途少一个，那就是一次数组变动，缓存前缀照样断。
     */
    private volatile boolean exposeToolSearch = false;
    private volatile boolean exposeMcpCall = false;

    public void setExposeToolSearch(boolean on) { this.exposeToolSearch = on; }
    public void setExposeMcpCall(boolean on) { this.exposeMcpCall = on; }

    public McpLoadingMode getMcpLoadingMode() { return mcpLoadingMode; }

    public void setMcpLoadingMode(McpLoadingMode mode) {
        this.mcpLoadingMode = mode != null ? mode : McpLoadingMode.EAGER;
    }

    private static boolean isOpenAIProtocol(String protocol) {
        return "openai".equals(protocol) || "openai-compat".equals(protocol);
    }

    public void markDiscovered(String name) {
        discoveredTools.add(name);
    }

    public boolean isDiscovered(String name) {
        return discoveredTools.contains(name);
    }

    /**
     * 还没被捞出来的延迟工具名，按字典序。
     *
     * <p>排序不只是为了好看：调用方要靠比较这份清单判断工具池到底变没变，顺序飘的话
     * 同一批工具会拼出不同的文本，比较就失效了。
     */
    public List<String> getDeferredToolNames() {
        return tools.values().stream()
                .filter(t -> t.shouldDefer() && !discoveredTools.contains(t.name()))
                .map(Tool::name)
                .sorted()
                .toList();
    }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public List<Tool> listTools() {
        return List.copyOf(tools.values());
    }

    public List<Map<String, Object>> getAllSchemas(String protocol) {
        boolean openai = isOpenAIProtocol(protocol);
        // 官方端点走原生延迟：工具留在 tools[] 里但打上 defer_loading，由服务端决定
        // 给不给模型看。这样即使发现了新工具，tools 数组的字节也不变。其他端点只能
        // 把延迟工具整个藏起来，靠 mcp_call 兜。defer_loading 是 Anthropic 的字段，
        // openai 协议下不带。
        boolean native_ = mcpLoadingMode == McpLoadingMode.NATIVE && !openai;
        var schemas = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            // 检索和分发只在用得上的模式里发。eager 下没有延迟工具可搜、也不需要
            // 分发，两个都发过去只是白占 token，还可能引诱模型去绕一圈。
            String name = tool.name();
            if (ToolSearchTool.TOOL_SEARCH_TOOL_NAME.equals(name) && !exposeToolSearch) continue;
            if (McpCallTool.MCP_CALL_TOOL_NAME.equals(name) && !exposeMcpCall) continue;
            boolean deferred = tool.shouldDefer() && !discoveredTools.contains(tool.name());
            if (deferred && !native_) continue;
            var base = tool.schema();
            if (openai) {
                schemas.add(Map.of(
                        "type", "function",
                        "name", base.get("name"),
                        "description", base.get("description"),
                        "parameters", base.get("input_schema")
                ));
            } else if (deferred) {
                var withDefer = new LinkedHashMap<String, Object>(base);
                withDefer.put("defer_loading", true);
                schemas.add(withDefer);
            } else {
                schemas.add(base);
            }
        }
        return schemas;
    }

    public List<Tool> getDeferredTools() {
        return tools.values().stream()
                .filter(Tool::shouldDefer)
                .toList();
    }

    public List<Map<String, Object>> searchDeferred(String query, int maxResults, String protocol) {
        String lower = query.toLowerCase();
        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (!tool.shouldDefer()) continue;
            if (tool.name().toLowerCase().contains(lower)
                    || tool.description().toLowerCase().contains(lower)) {
                var base = tool.schema();
                if (isOpenAIProtocol(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
                if (matches.size() >= maxResults) break;
            }
        }
        return matches;
    }

    public List<Map<String, Object>> findDeferredByNames(List<String> names, String protocol) {
        var nameSet = new HashSet<String>();
        for (var n : names) nameSet.add(n.toLowerCase());

        var matches = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            if (nameSet.contains(tool.name().toLowerCase())) {
                var base = tool.schema();
                if (isOpenAIProtocol(protocol)) {
                    matches.add(Map.of(
                            "type", "function",
                            "name", base.get("name"),
                            "description", base.get("description"),
                            "parameters", base.get("input_schema")
                    ));
                } else {
                    matches.add(base);
                }
            }
        }
        return matches;
    }

    public static ToolRegistry createDefault() {
        var reg = new ToolRegistry();
        reg.register(new com.flycode.tool.impl.ReadFileTool());
        reg.register(new com.flycode.tool.impl.WriteFileTool());
        reg.register(new com.flycode.tool.impl.EditFileTool());
        reg.register(new com.flycode.tool.impl.BashTool());
        reg.register(new com.flycode.tool.impl.GlobTool());
        reg.register(new com.flycode.tool.impl.GrepTool());
        return reg;
    }
}
