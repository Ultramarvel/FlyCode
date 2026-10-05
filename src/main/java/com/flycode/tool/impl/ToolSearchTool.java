package com.flycode.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.flycode.mcp.McpManager;
import com.flycode.tool.McpLoadingMode;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.ToolResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ToolSearchTool implements Tool {

    /** 工具检索的名字，注册表按模式筛它时要用。 */
    public static final String TOOL_SEARCH_TOOL_NAME = "ToolSearch";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final String DESCRIPTION = """
            Search for and load additional tools that are not immediately available. \
            Some tools are deferred (not loaded by default) to save context space. \
            Use this tool to discover and load them.

            Query forms:
            - "select:ToolName,AnotherTool" -- fetch exact tools by name
            - "keyword search" -- keyword search, returns up to max_results matches

            When you need a tool that isn't in your current tool list, use this to find it.""";

    private final ToolRegistry registry;
    private final String protocol;

    public ToolSearchTool(ToolRegistry registry) {
        this(registry, "anthropic");
    }

    public ToolSearchTool(ToolRegistry registry, String protocol) {
        this.registry = registry;
        this.protocol = protocol;
    }

    @Override
    public String name() {
        return TOOL_SEARCH_TOOL_NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.READ;
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "Query to find deferred tools. Use \"select:Name1,Name2\" for direct selection, or keywords to search."
                                ),
                                "max_results", Map.of(
                                        "type", "integer",
                                        "description", "Maximum results to return (default: 5)",
                                        "default", 5
                                )
                        ),
                        "required", List.of("query")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String query = stringArg(args, "query", "");
        if (query.isEmpty()) {
            return ToolResult.error("Error: query is required");
        }

        int maxResults = intArg(args, "max_results", 5);
        if (maxResults < 1) {
            maxResults = 5;
        }
        if (maxResults > 20) {
            maxResults = 20;
        }

        List<Map<String, Object>> schemas;

        if (query.startsWith("select:")) {
            List<String> names = Arrays.stream(query.substring("select:".length()).split(","))
                    .map(String::trim)
                    .toList();
            schemas = registry.findDeferredByNames(names, protocol);
        } else {
            schemas = registry.searchDeferred(query, maxResults, protocol);
        }

        if (schemas.isEmpty()) {
            List<Tool> deferred = registry.getDeferredTools();
            String nameList = deferred.stream()
                    .map(Tool::name)
                    .collect(Collectors.joining(", "));
            return ToolResult.success(
                    "No matching deferred tools found for query \"" + query
                            + "\". Available deferred tools: " + nameList
            );
        }

        // 非 MCP 的延迟工具没有 mcp_call 这条入口，只能照旧标记成已发现、
        // 让它进下一轮的 tools[]
        var mcpNames = new ArrayList<String>();
        for (var s : schemas) {
            Object nameObj = s.get("name");
            if (!(nameObj instanceof String n)) continue;
            if (n.startsWith(McpManager.MCP_TOOL_PREFIX)) {
                mcpNames.add(n);
            } else {
                registry.markDiscovered(n);
            }
        }

        // 官方端点：回 tool_reference，让服务端把 schema 展开进上下文。
        // tools 数组不动，缓存前缀因此不断。
        if (!mcpNames.isEmpty()
                && registry.getMcpLoadingMode() == McpLoadingMode.NATIVE
                && "anthropic".equals(protocol)) {
            var blocks = new ArrayList<Map<String, Object>>(mcpNames.size());
            for (var n : mcpNames) {
                blocks.add(Map.of("type", "tool_reference", "tool_name", n));
            }
            return ToolResult.withContentBlocks(
                    "Loaded " + mcpNames.size() + " tool(s): " + String.join(", ", mcpNames)
                            + ". You can call them directly now.",
                    blocks
            );
        }

        String schemasJson;
        try {
            schemasJson = MAPPER.writeValueAsString(schemas);
        } catch (JsonProcessingException e) {
            return ToolResult.error("Error serializing schemas: " + e.getMessage());
        }

        // 其他端点：schema 原文给模型看，调用走 mcp_call。
        // 这段文本落在 messages 末尾，属于追加，不影响缓存前缀。
        String suffix = mcpNames.isEmpty() ? "" :
                "\n\nTo invoke any of the tools above, call mcp_call with that tool's "
                        + "full name and an `arguments` object matching its input_schema exactly, "
                        + "using the same JSON types.";

        return ToolResult.success(
                "Found " + schemas.size() + " tool(s). Their full schemas are below:\n\n"
                        + schemasJson + suffix
        );
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }

    private static int intArg(Map<String, Object> args, String key, int def) {
        var v = args.get(key);
        if (v instanceof Number n) return n.intValue();
        return def;
    }
}
