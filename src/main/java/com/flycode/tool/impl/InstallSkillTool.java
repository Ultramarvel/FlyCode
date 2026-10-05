package com.flycode.tool.impl;

import com.flycode.skill.InstallReport;
import com.flycode.skill.SkillCatalog;
import com.flycode.skill.SkillInstaller;
import com.flycode.skill.SkillSource;
import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 从 URL 下载并安装 skill 到用户全局目录 (~/.flycode/skills/)。
 * <p>
 * 支持 skills.sh、GitHub tree、raw.githubusercontent.com 三种 URL 格式。
 * 安装完成后自动刷新 catalog，新 skill 可通过 /{@code <name>} 或 LoadSkill 直接使用。
 */
public class InstallSkillTool implements Tool {

    private static final String DESCRIPTION =
            "Download and install a Skill from a URL into the user-global skills directory "
            + "(~/.flycode/skills/). Supports skills.sh URLs (https://www.skills.sh/<owner>/<repo>/<name>), "
            + "GitHub tree URLs (https://github.com/<owner>/<repo>/tree/<ref>/<path>), and raw "
            + "SKILL.md URLs. After install the Skill becomes available via /<name> and LoadSkill. "
            + "Call this when the user pastes a Skill URL and asks to install it.";

    private SkillCatalog catalog;
    private Consumer<String> onInstalled;
    private String installRoot;   // 为空时自动取 ~/.flycode/skills

    // ── 外部注入 ──────────────────────────────────────────────────────

    public void setCatalog(SkillCatalog catalog) { this.catalog = catalog; }

    /** 安装完成回调，TUI 用来重新注册斜杠命令。 */
    public void setOnInstalled(Consumer<String> callback) { this.onInstalled = callback; }

    /** 测试用：覆盖默认安装目录。 */
    public void setInstallRoot(String root) { this.installRoot = root; }

    // ── Tool 接口实现 ──────────────────────────────────────────────────

    @Override
    public String name() { return "InstallSkill"; }

    @Override
    public String description() { return DESCRIPTION; }

    @Override
    public ToolCategory category() { return ToolCategory.WRITE; }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "url", Map.of(
                                        "type", "string",
                                        "description",
                                        "The Skill URL to fetch. Examples: "
                                        + "\"https://www.skills.sh/anthropics/skills/frontend-design\", "
                                        + "\"https://github.com/anthropics/skills/tree/main/skills/pdf\"."
                                )
                        ),
                        "required", List.of("url")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String rawURL = stringArg(args, "url", "");
        if (rawURL.isEmpty()) {
            return ToolResult.error("url is required");
        }

        // 解析 URL
        SkillSource src;
        try {
            src = SkillInstaller.parseSkillURL(rawURL);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }

        // 确定安装目录
        String root = installRoot;
        if (root == null || root.isEmpty()) {
            try {
                root = SkillInstaller.userSkillsRoot();
            } catch (Exception e) {
                return ToolResult.error(e.getMessage());
            }
        }

        // 执行安装
        InstallReport report;
        try {
            var installer = new SkillInstaller();
            report = installer.install(src, root);
        } catch (Exception e) {
            return ToolResult.error("install failed: " + e.getMessage());
        }

        // 刷新 catalog，让新 skill 立即可用
        if (catalog != null) {
            catalog.reload(catalog.getWorkDir());
        }
        if (onInstalled != null) {
            onInstalled.accept(report.skillName());
        }

        return ToolResult.success(
                "Installed skill \"%s\" from %s into %s (%d files, %d bytes). "
                        .formatted(report.skillName(), src.original(), report.targetDir(),
                                report.fileCount(), report.totalBytes())
                + "Now available — call LoadSkill({name: \"%s\"}) or invoke /%s directly."
                        .formatted(report.skillName(), report.skillName())
        );
    }

    private static String stringArg(Map<String, Object> args, String key, String def) {
        var v = args.get(key);
        return v instanceof String s ? s : def;
    }
}
