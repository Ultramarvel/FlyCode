package com.flycode.tool;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果。
 *
 * <p>{@code contentBlocks} 是给 tool_result 用的结构化内容块，为空时按 {@code output}
 * 发纯文本。目前只有 ToolSearch 在官方端点下用得上：它回 tool_reference 块，让服务端
 * 把 MCP 工具的 schema 展开进上下文，tools 数组因此一个字节都不用动。
 */
public record ToolResult(String output, boolean isError, List<Map<String, Object>> contentBlocks) {

    public ToolResult(String output, boolean isError) {
        this(output, isError, null);
    }

    public static ToolResult success(String output) {
        return new ToolResult(output, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }

    /** 带结构化内容块的成功结果，output 作为拿不到块时的兜底文本。 */
    public static ToolResult withContentBlocks(String output, List<Map<String, Object>> blocks) {
        return new ToolResult(output, false, blocks);
    }
}
