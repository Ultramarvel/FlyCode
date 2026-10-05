package com.flycode.mcp;

import com.flycode.tool.McpLoadingMode;
import com.flycode.tool.McpToolLike;
import com.flycode.tool.ToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Set;

/**
 * 决定 MCP 工具怎么进上下文。三条路，会话启动连上 MCP 之后定一次：
 *
 * <pre>
 *   EAGER    schema 总量不到上下文一成，全量放进 tools[]，不延迟。省下来的那点
 *            上下文不值得为它承担任何额外风险。
 *   NATIVE   官方 Anthropic 端点。工具带 defer_loading 留在 tools[] 里但服务端
 *            不给模型看，ToolSearch 回 tool_reference 让服务端展开 schema。
 *   DISPATCH 其他端点（国内厂商、各类代理网关）不支持上面两样，只能自己模拟：
 *            MCP 工具完全不进 tools[]，走 mcp_call 统一入口。
 * </pre>
 *
 * <p>为什么要分这三条：tools 渲染在 system 之后、messages 之前，数组一变，它后面
 * 的整段对话历史缓存全部失效。实测两万 token 历史下，往 tools 末尾加一个工具
 * 的命中率从 99.4% 掉到 9.5%，等于把整段历史重算一遍。
 */
public final class McpLoadingStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 低于上下文窗口这个比例就不延迟，直接全量加载。 */
    public static final int DEFAULT_EAGER_THRESHOLD_PERCENT = 10;

    /**
     * 拿不到真实 token 数时的估算比例。MCP 的 schema 是 JSON，符号密度高，每 token
     * 的字符数比自然语言低。
     */
    public static final double CHARS_PER_TOKEN = 2.5;

    /** 官方端点用的 beta header，defer_loading 和 tool_reference 都靠它开。 */
    public static final String NATIVE_TOOL_SEARCH_BETA = "advanced-tool-use-2025-11-20";

    private static final Set<String> OFFICIAL_HOSTS = Set.of("api.anthropic.com");

    private static final String ENV_OVERRIDE = "FLYCODE_MCP_LOADING";

    private McpLoadingStrategy() {}

    /** baseUrl 为空表示走 SDK 默认地址，也就是官方。 */
    public static boolean isOfficialAnthropicEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return true;
        try {
            String host = URI.create(baseUrl.trim()).getHost();
            return host != null && OFFICIAL_HOSTS.contains(host.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    public static int estimateSchemaTokens(long schemaChars) {
        return (int) (schemaChars / CHARS_PER_TOKEN);
    }

    public static McpLoadingMode decideMode(String baseUrl, int contextWindow, long mcpSchemaChars) {
        return decideMode(baseUrl, contextWindow, mcpSchemaChars, DEFAULT_EAGER_THRESHOLD_PERCENT);
    }

    public static McpLoadingMode decideMode(String baseUrl, int contextWindow, long mcpSchemaChars,
                                            int thresholdPercent) {
        McpLoadingMode override = fromEnv();
        if (override != null) return override;

        // 没有 MCP 工具，走哪条都一样，EAGER 最省事
        if (mcpSchemaChars <= 0) return McpLoadingMode.EAGER;

        double budget = (double) contextWindow * thresholdPercent / 100.0;
        if (estimateSchemaTokens(mcpSchemaChars) < budget) return McpLoadingMode.EAGER;

        return isOfficialAnthropicEndpoint(baseUrl) ? McpLoadingMode.NATIVE : McpLoadingMode.DISPATCH;
    }

    private static McpLoadingMode fromEnv() {
        String raw = System.getenv(ENV_OVERRIDE);
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "eager" -> McpLoadingMode.EAGER;
            case "native" -> McpLoadingMode.NATIVE;
            case "dispatch" -> McpLoadingMode.DISPATCH;
            default -> null;
        };
    }

    /** MCP 工具 schema 序列化后的字符数，用来跟阈值比。 */
    public static long measureSchemaChars(ToolRegistry registry) {
        long total = 0;
        for (var tool : registry.listTools()) {
            if (!tool.name().startsWith(McpManager.MCP_TOOL_PREFIX)) continue;
            try {
                total += MAPPER.writeValueAsString(tool.schema()).length();
            } catch (Exception e) {
                total += tool.name().length() + tool.description().length();
            }
        }
        return total;
    }

    /**
     * 把决定落到 registry 上。
     *
     * <p>EAGER 下要把 MCP 工具的延迟标记摘掉，它们才会出现在 tools[] 里；另外两条路
     * 保持延迟。mcp_call 不在这里注册——它必须在 MCP 连接之前就在 tools[] 里，
     * 否则连上之后再加就是一次中途改动 tools 数组，缓存照样断。
     */
    public static void applyMode(ToolRegistry registry, McpLoadingMode mode) {
        registry.setMcpLoadingMode(mode);
        boolean eager = mode == McpLoadingMode.EAGER;
        for (var tool : registry.listTools()) {
            if (tool instanceof McpToolLike mcpTool) {
                mcpTool.setDeferLoading(!eager);
            }
        }

        // 检索和分发按模式决定发不发。EAGER 下所有工具都在 tools[] 里，没有可搜的
        // 对象、也不需要分发入口。这两个开关在这里算一次就固定下来，整场会话不变，
        // 不会造成 tools[] 中途抖动。
        registry.setExposeToolSearch(!eager);
        registry.setExposeMcpCall(mode == McpLoadingMode.DISPATCH);
    }

    /** 连上 MCP 之后调一次的入口。 */
    public static McpLoadingMode decideAndApply(ToolRegistry registry, String baseUrl, int contextWindow) {
        McpLoadingMode mode = decideMode(baseUrl, contextWindow, measureSchemaChars(registry));
        applyMode(registry, mode);
        return mode;
    }
}
