package com.flycode.tool;

/**
 * MCP 工具怎么进上下文，由 McpLoadingStrategy 在连上服务器后写入 ToolRegistry。
 *
 * <p>枚举放在 tool 包：ToolRegistry 和 ToolSearchTool 都要读它，而它们不该依赖 mcp 包。
 */
public enum McpLoadingMode {

    /** schema 总量不到上下文一成，全量进 tools[]，不延迟。 */
    EAGER,

    /** 官方端点：带 defer_loading 留在 tools[] 里，ToolSearch 回 tool_reference。 */
    NATIVE,

    /** 其他端点：完全不进 tools[]，走 mcp_call 分发。 */
    DISPATCH
}
