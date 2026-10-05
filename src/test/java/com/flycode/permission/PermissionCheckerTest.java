package com.flycode.permission;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCheckerTest {

    private static final Tool BASH_TOOL = new Tool() {
        @Override public String name() { return "Bash"; }
        @Override public String description() { return ""; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public Map<String, Object> schema() { return Map.of(); }
        @Override public com.flycode.tool.ToolResult execute(Map<String, Object> args) { return new com.flycode.tool.ToolResult("", false); }
    };

    private static final Tool MCP_CALL_TOOL = new com.flycode.tool.impl.McpCallTool(
            new com.flycode.tool.ToolRegistry());

    /**
     * mcp_call 的规则粒度：pattern 匹配的是归一化后的 server__tool，
     * 四个语言的 permissions.yaml 写法因此完全一致。
     */
    @Test
    void mcpCallRulesMatchServerAndToolGlob(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"mcp_call(linear__*)\"\n  effect: allow\n"
                        + "- rule: \"mcp_call(infra__deploy_service)\"\n  effect: deny\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);

        assertEquals(PermissionMode.Decision.ALLOW,
                checker.check(MCP_CALL_TOOL, Map.of(
                        "server", "linear", "tool", "mcp__linear__create_issue")).decision());
        // 短名也要命中同一条规则
        assertEquals(PermissionMode.Decision.ALLOW,
                checker.check(MCP_CALL_TOOL, Map.of(
                        "server", "linear", "tool", "create_issue")).decision());
        assertEquals(PermissionMode.Decision.DENY,
                checker.check(MCP_CALL_TOOL, Map.of(
                        "server", "infra", "tool", "deploy_service")).decision());
        // 没有规则命中时退回按 category 裁决，command 类在 default 模式下问一下
        assertEquals(PermissionMode.Decision.ASK,
                checker.check(MCP_CALL_TOOL, Map.of(
                        "server", "sentry", "tool", "list_issues")).decision());
    }

    @Test
    void sandboxAutoAllowRespectsCompoundDeny(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(rm -rf /)\"\n  effect: deny\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.setSandboxEnabled(true);

        var result = checker.check(BASH_TOOL, Map.of("command", "echo ok && rm -rf /"));
        assertEquals(PermissionMode.Decision.DENY, result.decision(),
                "compound command with denied subcommand should be deny");
    }

    @Test
    void sandboxAutoAllowAllowsSafeCommand(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(rm -rf /)\"\n  effect: deny\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.setSandboxEnabled(true);

        var result = checker.check(BASH_TOOL, Map.of("command", "go test ./..."));
        assertEquals(PermissionMode.Decision.ALLOW, result.decision(),
                "safe command with sandbox should be allow");
    }

    @Test
    void sandboxAutoAllowRespectsAskRule(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"),
                "- rule: \"Bash(git push origin main)\"\n  effect: ask\n");

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.setSandboxEnabled(true);

        var result = checker.check(BASH_TOOL, Map.of("command", "git push origin main"));
        assertEquals(PermissionMode.Decision.ASK, result.decision(),
                "ask rule should not be overridden by sandbox");
    }

    private static final Tool WRITE_TOOL = new Tool() {
        @Override public String name() { return "WriteFile"; }
        @Override public String description() { return ""; }
        @Override public ToolCategory category() { return ToolCategory.WRITE; }
        @Override public Map<String, Object> schema() { return Map.of(); }
        @Override public com.flycode.tool.ToolResult execute(Map<String, Object> args) { return new com.flycode.tool.ToolResult("", false); }
    };

    @Test
    void protectedPathsDeniedEvenInBypass(@TempDir Path tmpDir) {
        PermissionChecker checker = new PermissionChecker(PermissionMode.BYPASS, tmpDir);
        for (String rel : new String[]{
                ".flycode/permissions.local.yaml",
                ".flycode/config.yaml",
                ".flycode/skills/evil/SKILL.md"}) {
            var result = checker.check(WRITE_TOOL,
                    Map.of("file_path", tmpDir.resolve(rel).toString(), "content", "x"));
            assertEquals(PermissionMode.Decision.DENY, result.decision(),
                    rel + " should be denied even under bypass");
        }

        var ordinary = checker.check(WRITE_TOOL,
                Map.of("file_path", tmpDir.resolve("a.txt").toString(), "content", "x"));
        assertNotEquals(PermissionMode.Decision.DENY, ordinary.decision());
    }

    /** 写好项目级和本地级两份规则文件，用于验证跨文件合并 */
    private static PermissionChecker checkerWithTiers(
            Path tmpDir, String projectRules, String localRules) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"), projectRules);
        Files.writeString(rulesDir.resolve("permissions.local.yaml"), localRules);
        return new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
    }

    private static final String ALLOW_GIT = "- rule: \"Bash(git *)\"\n  effect: allow\n";
    private static final String DENY_GIT = "- rule: \"Bash(git *)\"\n  effect: deny\n";
    private static final String ASK_GIT = "- rule: \"Bash(git *)\"\n  effect: ask\n";

    @Test
    void denyInProjectBeatsAllowInLocal(@TempDir Path tmpDir) throws IOException {
        var checker = checkerWithTiers(tmpDir, DENY_GIT, ALLOW_GIT);
        var result = checker.check(BASH_TOOL, Map.of("command", "git push origin main"));
        assertEquals(PermissionMode.Decision.DENY, result.decision());
    }

    @Test
    void denyInLocalBeatsAllowInProject(@TempDir Path tmpDir) throws IOException {
        var checker = checkerWithTiers(tmpDir, ALLOW_GIT, DENY_GIT);
        var result = checker.check(BASH_TOOL, Map.of("command", "git push origin main"));
        assertEquals(PermissionMode.Decision.DENY, result.decision());
    }

    @Test
    void askBeatsAllow(@TempDir Path tmpDir) throws IOException {
        var checker = checkerWithTiers(tmpDir, ALLOW_GIT, ASK_GIT);
        var result = checker.check(BASH_TOOL, Map.of("command", "git push origin main"));
        assertEquals(PermissionMode.Decision.ASK, result.decision());
    }

    @Test
    void allowExtraRootOpensPathOutsideProject(@TempDir Path tmpDir, @TempDir Path outside) {
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        Map<String, Object> args = Map.of(
                "file_path", outside.resolve("MEMORY.md").toString(), "content", "x");

        var before = checker.check(WRITE_TOOL, args);
        assertTrue(before.reason().contains("outside allowed sandbox"),
                "放开之前应被路径沙箱拦下，实际: " + before.reason());

        checker.allowExtraRoot(outside);

        var after = checker.check(WRITE_TOOL, args);
        assertFalse(after.reason().contains("outside allowed sandbox"),
                "放开之后不应再被路径沙箱拦，实际: " + after.reason());
    }

    @Test
    void appendLocalRuleKeepsExistingEffects(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.local.yaml"), ASK_GIT);

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        checker.appendLocalRule("ReadFile", "foo*");

        // 追加规则会整份重写文件，原有的 ask 规则不能在重写中被改成别的效果
        assertEquals(PermissionMode.Decision.ASK,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision(),
                "原有 ask 规则应保持 ask");

        String written = Files.readString(rulesDir.resolve("permissions.local.yaml"));
        assertTrue(written.contains("ask"), "写回的文件里应仍有 ask 效果: " + written);
        assertTrue(written.contains("allow"), "新追加的规则应为 allow: " + written);
    }

    @Test
    void reusesParsedRulesWhenFileLooksUnchanged(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Path rulesFile = rulesDir.resolve("permissions.yaml");

        // 与 ALLOW_GIT 等长，用尾随空格补齐，YAML 解析时会被忽略
        String denySameSize = "- rule: \"Bash(git *)\"\n  effect: deny \n";
        assertEquals(ALLOW_GIT.length(), denySameSize.length(), "两段规则长度必须一致");

        Files.writeString(rulesFile, ALLOW_GIT);
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        assertEquals(PermissionMode.Decision.ALLOW,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());

        // 偷偷把内容换成 deny，同时把 size 和 mtime 都还原成原样：
        // 引擎看不出文件动过，应当继续用缓存里的解析结果
        FileTime original = Files.getLastModifiedTime(rulesFile);
        Files.writeString(rulesFile, denySameSize);
        Files.setLastModifiedTime(rulesFile, original);

        assertEquals(PermissionMode.Decision.ALLOW,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision(),
                "文件看起来没变动时应复用缓存");
    }

    @Test
    void reparsesWhenOnlyMtimeMoves(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Path rulesFile = rulesDir.resolve("permissions.yaml");

        Files.writeString(rulesFile, ALLOW_GIT);
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        assertEquals(PermissionMode.Decision.ALLOW,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());

        Files.writeString(rulesFile, "- rule: \"Bash(git *)\"\n  effect: deny \n");
        // 把修改时间显式前移，模拟低精度时间戳文件系统上的一次真实改动
        Files.setLastModifiedTime(rulesFile,
                FileTime.fromMillis(System.currentTimeMillis() + 2000));

        assertEquals(PermissionMode.Decision.DENY,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision(),
                "修改时间变了应重新解析");
    }

    @Test
    void dropsCacheWhenRuleFileRemoved(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Path rulesFile = rulesDir.resolve("permissions.yaml");

        Files.writeString(rulesFile, DENY_GIT);
        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        assertEquals(PermissionMode.Decision.DENY,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());

        Files.delete(rulesFile);
        // 规则没了就落到模式兜底，default 下命令类是 ask
        assertEquals(PermissionMode.Decision.ASK,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());
    }

    @Test
    void picksUpRuleFileChangesWithoutRestart(@TempDir Path tmpDir) throws IOException {
        Path rulesDir = tmpDir.resolve(".flycode");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("permissions.yaml"), ALLOW_GIT);

        PermissionChecker checker = new PermissionChecker(PermissionMode.DEFAULT, tmpDir);
        assertEquals(PermissionMode.Decision.ALLOW,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());

        // 同一个 checker 实例，改完规则文件后立即生效
        Files.writeString(rulesDir.resolve("permissions.yaml"), DENY_GIT);
        assertEquals(PermissionMode.Decision.DENY,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());
    }

    @Test
    void denyBeatsAllowRegardlessOfOrderInSameFile(@TempDir Path tmpDir) throws IOException {
        var checker = checkerWithTiers(tmpDir, ALLOW_GIT + DENY_GIT, "");
        assertEquals(PermissionMode.Decision.DENY,
                checker.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());

        Path reversed = tmpDir.resolve("reversed");
        var checker2 = checkerWithTiers(reversed, DENY_GIT + ALLOW_GIT, "");
        assertEquals(PermissionMode.Decision.DENY,
                checker2.check(BASH_TOOL, Map.of("command", "git push origin main")).decision());
    }
}
