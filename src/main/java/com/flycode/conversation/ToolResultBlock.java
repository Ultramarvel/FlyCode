package com.flycode.conversation;

import java.util.List;
import java.util.Map;

/**
 * 一条工具结果在对话历史里的形态。
 *
 * <p>{@code contentBlocks} 非空时，发请求会用这些结构化块代替 {@code content} 纯文本，
 * 用于 ToolSearch 在官方端点下回的 tool_reference。
 */
public record ToolResultBlock(String toolUseId, String content, boolean isError,
                              List<Map<String, Object>> contentBlocks) {

    public ToolResultBlock(String toolUseId, String content, boolean isError) {
        this(toolUseId, content, isError, null);
    }
}
