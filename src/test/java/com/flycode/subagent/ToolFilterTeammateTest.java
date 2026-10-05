package com.flycode.subagent;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolRegistry;
import com.flycode.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 进程内队友的工具集从 Lead 那份过滤而来，除了常规子 Agent 规则，还要挡掉团队
 * 成员管理工具，否则队友会连建团队删团队一起继承过去。
 */
class ToolFilterTeammateTest {

    /** 只为过滤测试用的占位工具，execute 不会被调用。 */
    private record StubTool(String toolName) implements Tool {
        @Override public String name() { return toolName; }
        @Override public String description() { return toolName; }
        @Override public ToolCategory category() { return ToolCategory.READ; }
        @Override public Map<String, Object> schema() { return Map.of("name", toolName); }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.success("");
        }
    }

    private static ToolRegistry registryOf(String... names) {
        ToolRegistry r = new ToolRegistry();
        for (String n : names) r.register(new StubTool(n));
        return r;
    }

    @Test
    void teammateFilterBlocksTeamManagement() {
        ToolRegistry source = registryOf(
                "ReadFile", "Bash", "EditFile", "Agent", "TeamCreate", "TeamDelete", "SendMessage");
        var spec = new SubAgentSpec(
                "worker", "test worker", List.of(), List.of(), "", 10, "");

        ToolRegistry filtered = ToolFilter.filterForTeammate(source, spec);

        // 派人和建团队是 Lead 的职责，队友拿不到
        for (String name : List.of("Agent", "TeamCreate", "TeamDelete")) {
            assertNull(filtered.get(name), "队友工具集不应包含 " + name);
        }
        // 干活的工具照常保留
        for (String name : List.of("ReadFile", "Bash", "EditFile")) {
            assertNotNull(filtered.get(name), "队友工具集缺少 " + name);
        }
    }
}
