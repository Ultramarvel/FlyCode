package com.flycode.tool.impl;

import com.flycode.skill.SkillCatalog;
import com.flycode.skill.SkillExecutor;
import com.flycode.skill.SkillForkHost;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 按名称激活 Skill，将完整 SOP 注入对话上下文。
 * <p>
 * 系统提示词里只列出 Skill 的名称和一句话描述（渐进式披露），
 * 模型根据用户意图调用本工具加载完整指令。
 */
public class LoadSkillTool implements Tool {

    private static final String DESCRIPTION =
            "Activate a Skill by name. Returns the full SOP body so you can follow its "
            + "instructions. Call this when the user's request matches one of the available "
            + "Skills listed in the available-skills section. Pass the Skill name without a leading slash.";

    private SkillCatalog catalog;
    // 运行隔离子 Agent 的宿主，mode: fork 的 Skill 依赖它；
    // 为 null 时（宿主未接入子 Agent 运行时）回退成 inline，保证工具始终可用
    private SkillForkHost forkHost;

    public void setCatalog(SkillCatalog catalog) { this.catalog = catalog; }

    public void setForkHost(SkillForkHost forkHost) { this.forkHost = forkHost; }

    @Override
    public String name() { return "LoadSkill"; }

    @Override
    public String description() { return DESCRIPTION; }

    @Override
    public ToolCategory category() { return ToolCategory.READ; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of(
                                        "type", "string",
                                        "description",
                                        "The Skill name to activate (e.g. \"commit\", \"backend-interview\")."
                                )
                        ),
                        "required", List.of("name")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = args.getOrDefault("name", "").toString();
        if (name.isEmpty()) {
            return ToolResult.error("name is required");
        }
        if (catalog == null) {
            return ToolResult.error("LoadSkill not initialized (catalog is null)");
        }

        var skill = catalog.getFull(name);
        if (skill.isEmpty()) {
            return ToolResult.error("unknown skill: " + name);
        }

        String body = skill.get().promptBody();
        if (body == null || body.isEmpty()) {
            return ToolResult.error("skill \"" + name + "\" has empty body — cannot activate");
        }

        // fork 模式：SOP 正文不进主对话，丢给隔离的子 Agent 执行，只把最终结果带回。
        // 这样模型自己加载 Skill 和用户敲斜杠命令遵循同一套 mode 语义，声明的隔离
        // 意图在两条路径上都生效。
        if ("fork".equals(skill.get().meta().mode()) && forkHost != null) {
            try {
                return ToolResult.success(SkillExecutor.executeFork(skill.get(), "", forkHost));
            } catch (Exception e) {
                return ToolResult.error(
                        "skill \"" + name + "\" fork execution failed: " + e.getMessage());
            }
        }

        return ToolResult.success("# Skill: " + name + "\n\n" + body);
    }
}
