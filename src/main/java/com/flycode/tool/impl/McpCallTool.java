package com.flycode.tool.impl;

import com.flycode.mcp.McpManager;
import com.flycode.tool.McpToolLike;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * MCP 工具的统一调用入口。
 *
 * <p>MCP 工具不进入 tools[]，模型先用 ToolSearch 读到 schema，再通过 mcp_call
 * 把工具名和参数传进来。这样 tools 数组在整场会话里字节不变，prompt cache
 * 的前缀不会被打断——工具排在 system 之后、messages 之前，数组一变，它后面
 * 的整段历史都要重算。
 *
 * <p>代价是参数由模型自由生成，没有接口层的 schema 约束，偶尔会写错 JSON 类型。
 * {@link #coerceBySchema} 按目标工具的完整 schema 逐层修正，修正规则四个语言
 * 必须逐条一致：
 *
 * <pre>
 *   schema 声明        模型给的                修正为
 *   string            数字（非 boolean）       "8891"
 *   integer / number  数字形字符串             5 / 5.0
 *   boolean           "true" / "false"        true / false
 *   array             单键对象且值是数组        拆出内层数组
 *   array             逗号分隔字符串           按逗号切分去空白
 *   object            对象                     按 properties 递归
 *   array             数组                     按 items 递归每个元素
 * </pre>
 *
 * <p>修正不了的原样往下传，交给 MCP 服务器报它自己的错——服务器的域内错误比本地
 * 类型错误对模型更有指导性。
 */
public class McpCallTool implements Tool {

    /** 分发工具的名字，权限规则里也用它。 */
    public static final String MCP_CALL_TOOL_NAME = "mcp_call";

    /**
     * 数字形状：整串都得是合法的 JSON 数字，integer 还不许有小数和指数部分。
     * 这两条正则四个语言必须一致。
     */
    private static final Pattern INT_SHAPE = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern NUM_SHAPE =
            Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    private static final String DESCRIPTION =
            "Invoke a tool on a connected MCP server. Call ToolSearch first to load the "
                    + "tool's schema, then pass its arguments here exactly as that schema requires, "
                    + "using the same JSON types.";

    private final ToolRegistry registry;

    public McpCallTool(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override public String name() { return MCP_CALL_TOOL_NAME; }

    @Override public String description() { return DESCRIPTION; }

    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    // 自己必须留在 tools[] 里，否则模型没有入口
    @Override public boolean shouldDefer() { return false; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "server", Map.of(
                                        "type", "string",
                                        "description", "MCP server name, e.g. 'linear'."
                                ),
                                "tool", Map.of(
                                        "type", "string",
                                        "description", "Full tool name as returned by ToolSearch, "
                                                + "e.g. 'mcp__linear__create_issue'."
                                ),
                                "arguments", Map.of(
                                        "type", "object",
                                        "description", "The target tool's arguments. Must match that "
                                                + "tool's input_schema exactly, including JSON types: bare "
                                                + "numbers for integer fields, bare true/false for boolean "
                                                + "fields, quoted strings for string fields, and plain JSON "
                                                + "arrays for array fields."
                                )
                        ),
                        "required", List.of("server", "tool", "arguments")
                )
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> args) {
        String server = stringArg(args, "server");
        String tool = stringArg(args, "tool");
        if (tool.isEmpty()) {
            return ToolResult.error("mcp_call requires a 'tool' name");
        }

        Tool target = resolve(server, tool);
        if (target == null) {
            var names = availableNames();
            String hint = names.isEmpty() ? "(none connected)" : String.join(", ", names);
            return ToolResult.error("Unknown MCP tool '" + tool + "' on server '" + server
                    + "'. Available tools: " + hint);
        }

        Map<String, Object> inner = args.get("arguments") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();

        if (target instanceof McpToolLike mcpTool) {
            var schema = mcpTool.mcpInputSchema();
            if (schema != null && !schema.isEmpty()) {
                Object fixed = coerceBySchema(inner, schema);
                if (fixed instanceof Map<?, ?> fixedMap) {
                    inner = (Map<String, Object>) fixedMap;
                }
            }
        }

        return target.execute(inner);
    }

    /**
     * 全名 / server+短名 / 短名后缀唯一匹配，依次尝试。
     *
     * <p>模型很常只传短名（实测约三成调用），所以这里必须容错，否则会白白换来
     * 一轮重试。
     */
    private Tool resolve(String server, String tool) {
        Tool direct = registry.get(tool);
        if (direct != null) return direct;

        direct = registry.get(McpManager.buildMcpToolName(server, tool));
        if (direct != null) return direct;

        String suffix = McpManager.MCP_NAME_SEP + McpManager.sanitizeName(tool);
        Tool hit = null;
        for (var t : registry.listTools()) {
            if (!t.name().startsWith(McpManager.MCP_TOOL_PREFIX)) continue;
            if (!t.name().endsWith(suffix)) continue;
            // 后缀有歧义时不猜，报错让模型看到全名再挑
            if (hit != null) return null;
            hit = t;
        }
        return hit;
    }

    private List<String> availableNames() {
        var names = new ArrayList<String>();
        for (var t : registry.listTools()) {
            if (t.name().startsWith(McpManager.MCP_TOOL_PREFIX)) names.add(t.name());
        }
        names.sort(null);
        return names;
    }

    /**
     * 权限规则匹配用的 content，归一化成 {@code server__tool}。
     *
     * <p>不带 mcp__ 前缀，也不受各语言 wrapper 命名差异影响，四个语言的
     * permissions.yaml 写法因此完全一致：{@code mcp_call(linear__create_issue)}。
     *
     * <p>两段都要过一遍 sanitize。模型可能传短名也可能传全名，全名里的段是 wrapper
     * 已经处理过的，短名是模型原样给的——不统一处理的话，同一个调用传短名和传全名
     * 会算出不同的 content，规则就会漏匹配。
     */
    public static String permissionContent(String server, String tool) {
        if (server == null) server = "";
        if (tool == null) tool = "";
        if (tool.startsWith(McpManager.MCP_TOOL_PREFIX)) {
            String rest = tool.substring(McpManager.MCP_TOOL_PREFIX.length());
            int idx = rest.indexOf(McpManager.MCP_NAME_SEP);
            if (idx >= 0) {
                // 全名里已经带了服务器段，用它，避免拼出 linear__linear__x
                return McpManager.sanitizeName(rest.substring(0, idx))
                        + McpManager.MCP_NAME_SEP
                        + McpManager.sanitizeName(rest.substring(idx + McpManager.MCP_NAME_SEP.length()));
            }
        }
        return McpManager.sanitizeName(server) + McpManager.MCP_NAME_SEP + McpManager.sanitizeName(tool);
    }

    // ── schema 强转 ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static Object coerceBySchema(Object value, Object schema) {
        if (!(schema instanceof Map<?, ?> schemaMap)) return value;
        Object typeObj = schemaMap.get("type");
        String want = typeObj instanceof String s ? s : "";

        if ("object".equals(want) && value instanceof Map<?, ?> valueMap) {
            Object propsObj = schemaMap.get("properties");
            Map<String, Object> props = propsObj instanceof Map<?, ?> p
                    ? (Map<String, Object>) p
                    : Map.of();
            var out = new LinkedHashMap<String, Object>();
            for (var e : valueMap.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, props.containsKey(key)
                        ? coerceBySchema(e.getValue(), props.get(key))
                        : e.getValue());
            }
            return out;
        }

        if ("array".equals(want)) {
            Object itemSchema = schemaMap.get("items");
            Object working = value;
            // 模型常把数组包成 {"item": [...]} 这类单键对象
            if (working instanceof Map<?, ?> wrapper) {
                if (wrapper.size() == 1) {
                    Object only = wrapper.values().iterator().next();
                    if (only instanceof List<?>) working = only;
                }
            } else if (working instanceof String text) {
                // 也常拼成逗号分隔的字符串
                var parts = new ArrayList<Object>();
                for (String p : text.split(",")) {
                    String trimmed = p.trim();
                    if (!trimmed.isEmpty()) parts.add(trimmed);
                }
                working = parts;
            }
            if (working instanceof List<?> list) {
                var out = new ArrayList<Object>(list.size());
                for (Object item : list) out.add(coerceBySchema(item, itemSchema));
                return out;
            }
            return working;
        }

        if (!want.isEmpty()) return coerceScalar(value, want);
        return value;
    }

    private static Object coerceScalar(Object value, String want) {
        // boolean 要先排掉：Java 里 Boolean 不是 Number，但别的语言里 bool 是 int
        // 的子类，四个语言统一按「boolean 不参与数字转换」处理
        if ("string".equals(want) && value instanceof Number num && !(value instanceof Boolean)) {
            return numberToString(num);
        }
        if (("integer".equals(want) || "number".equals(want)) && value instanceof String raw) {
            String text = raw.trim();
            // 先用正则挡一道：Java 的 Double.parseDouble 认 "5d"、"Infinity" 这类写法，
            // 不挡的话会转出别的语言转不出来的值
            Pattern shape = "integer".equals(want) ? INT_SHAPE : NUM_SHAPE;
            if (!shape.matcher(text).matches()) return value;
            try {
                // "5.7" 配 integer 不做截断，原样交给 MCP 服务器报它的域内错误
                return "integer".equals(want)
                        ? (Object) Long.valueOf(text)
                        : (Object) Double.valueOf(text);
            } catch (NumberFormatException e) {
                return value;
            }
        }
        if ("boolean".equals(want) && value instanceof String raw) {
            String low = raw.trim().toLowerCase();
            if ("true".equals(low)) return Boolean.TRUE;
            if ("false".equals(low)) return Boolean.FALSE;
        }
        return value;
    }

    /** 整数不要带小数点：schema 声明 string 时，8891 该变成 "8891" 而不是 "8891.0"。 */
    private static String numberToString(Number num) {
        if (num instanceof Integer || num instanceof Long || num instanceof Short
                || num instanceof Byte || num instanceof java.math.BigInteger) {
            return num.toString();
        }
        double d = num.doubleValue();
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return num.toString();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        var v = args.get(key);
        return v instanceof String s ? s : "";
    }
}
