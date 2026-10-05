package com.flycode.tool;

import java.util.Map;

/**
 * MCP 工具包装器额外暴露给分发和分流逻辑的能力。
 *
 * <p>接口放在 tool 包而不是 mcp 包：McpCallTool 要按目标工具的 schema 强转参数，
 * McpLoadingStrategy 要批量改延迟标记，两边都只关心「这个工具是不是 MCP 工具、
 * 它的 schema 长什么样」，不需要知道 MCP 客户端怎么连的。
 */
public interface McpToolLike extends Tool {

    /** 工具所属的 MCP 服务器名，未经 sanitize 的原始配置名。 */
    String mcpServerName();

    /** 目标工具的完整 input_schema，供参数强转逐层比对。 */
    Map<String, Object> mcpInputSchema();

    /** 由分流逻辑在会话启动时统一设置：eager 模式下所有 MCP 工具都不延迟。 */
    void setDeferLoading(boolean on);
}
