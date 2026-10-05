package com.flycode.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

/**
 * 让 Agent 以结构化数据交付最终结果。
 *
 * <p>非交互模式和 coordinator 模式下，调用方要的是能直接解析的 JSON，
 * 而不是夹在自然语言里的一段文字。
 */
public class SyntheticOutputTool implements Tool {
    private static final ObjectWriter WRITER = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** 可选的结构约定，设置后校验 output 是否符合调用方要求。 */
    private final Map<String, Object> jsonSchema;

    public SyntheticOutputTool() {
        this(null);
    }

    public SyntheticOutputTool(Map<String, Object> jsonSchema) {
        this.jsonSchema = jsonSchema;
    }

    @Override public String name() { return "SyntheticOutput"; }
    @Override public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public String description() {
        return "Return structured output in JSON format. Use this tool to return your final "
                + "response as structured data in non-interactive or coordinator mode sessions.";
    }

    @Override
    public Map<String, Object> schema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("output", Map.of(
                "description", "The structured result: an object, an array, or a plain string"));

        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", props,
                        "required", List.of("output")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        if (!args.containsKey("output")) {
            return ToolResult.error("Error: output is required");
        }
        Object output = args.get("output");

        String err = validateSchema(output);
        if (!err.isEmpty()) {
            return ToolResult.error("Output does not match required schema: " + err);
        }

        // 字符串原样返回，不做二次 JSON 包装
        if (output instanceof String s) {
            return ToolResult.success(s);
        }

        try {
            return ToolResult.success(WRITER.writeValueAsString(output));
        } catch (JsonProcessingException e) {
            return ToolResult.error("Error: output is not serializable: " + e.getMessage());
        }
    }

    /**
     * 只覆盖顶层类型和必填字段，返回空字符串表示通过。
     * 完整的 JSON Schema 校验没有必要，这里挡的是模型交付结构明显走样的情况。
     */
    private String validateSchema(Object data) {
        if (jsonSchema == null) {
            return "";
        }

        if (jsonSchema.get("type") instanceof String expected) {
            String actual = data == null ? "null" : data.getClass().getSimpleName();
            switch (expected) {
                case "object" -> {
                    if (!(data instanceof Map)) {
                        return "Expected object, got " + actual;
                    }
                }
                case "array" -> {
                    if (!(data instanceof List)) {
                        return "Expected array, got " + actual;
                    }
                }
                case "string" -> {
                    if (!(data instanceof String)) {
                        return "Expected string, got " + actual;
                    }
                }
                default -> { }
            }
        }

        if (jsonSchema.get("required") instanceof List<?> required && data instanceof Map<?, ?> obj) {
            List<String> missing = new ArrayList<>();
            for (Object key : required) {
                if (key instanceof String field && !obj.containsKey(field)) {
                    missing.add(field);
                }
            }
            if (!missing.isEmpty()) {
                return "Missing required fields: " + String.join(", ", missing);
            }
        }

        return "";
    }
}
