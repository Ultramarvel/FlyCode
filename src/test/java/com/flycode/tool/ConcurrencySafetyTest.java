package com.flycode.tool;

import com.flycode.tool.impl.BashTool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发安全按这一次调用的实际参数算，不是只看工具类别。
 *
 * <p>ls 和 rm 都是 Bash，前者跟 ReadFile 一样不动外部状态、可以一起并发，后者一旦跟
 * 别人并发，执行顺序就不再是模型给出的那个顺序。
 */
class ConcurrencySafetyTest {

    private static Map<String, Object> cmd(Object value) {
        var m = new HashMap<String, Object>();
        m.put("command", value);
        return m;
    }

    @Test
    @DisplayName("只读命令算并发安全")
    void readOnlyCommandsAreSafe() {
        var bash = new BashTool();
        for (var c : List.of("ls", "ls -la", "cat a.txt", "git status", "wc -l f", "pwd")) {
            assertTrue(bash.isConcurrencySafe(cmd(c)), c);
        }
    }

    @Test
    @DisplayName("会改东西的命令不算并发安全")
    void mutatingCommandsAreNotSafe() {
        var bash = new BashTool();
        var unsafe = List.of(
                "rm -rf build", "mv a b", "npm install", "git commit -m x",
                "echo hi > f", "ls | wc -l", "ls; rm x", "ls && rm x",
                "echo $(rm x)", "ls `rm x`");
        for (var c : unsafe) {
            assertFalse(bash.isConcurrencySafe(cmd(c)), c);
        }
    }

    @Test
    @DisplayName("参数缺失或类型不对时按不安全处理")
    void badArgsAreNotSafe() {
        var bash = new BashTool();
        assertFalse(bash.isConcurrencySafe(new HashMap<>()));
        assertFalse(bash.isConcurrencySafe(cmd(null)));
        assertFalse(bash.isConcurrencySafe(cmd(123)));
        assertFalse(bash.isConcurrencySafe(null));
    }

    @Test
    @DisplayName("没覆写的工具按类别兜底")
    void defaultFollowsCategory() {
        assertTrue(stub(ToolCategory.READ).isConcurrencySafe(Map.of()));
        assertFalse(stub(ToolCategory.WRITE).isConcurrencySafe(Map.of()));
        assertFalse(stub(ToolCategory.COMMAND).isConcurrencySafe(Map.of()));
    }

    private static Tool stub(ToolCategory cat) {
        return new Tool() {
            @Override public String name() { return "Stub"; }
            @Override public String description() { return "stub"; }
            @Override public ToolCategory category() { return cat; }
            @Override public Map<String, Object> schema() { return Map.of("name", "Stub"); }
            @Override public ToolResult execute(Map<String, Object> args) {
                return ToolResult.success("");
            }
        };
    }

    @Test
    @DisplayName("跟权限层用的是同一份白名单")
    void sharesTheWhitelistWithPermissionLayer() {
        // 两边口径必须一致，否则会出现「权限放行但被当成不安全串行」这种自相矛盾
        var bash = new BashTool();
        for (var c : List.of("ls", "rm -rf x", "cat f", "ls | wc")) {
            assertTrue(bash.isConcurrencySafe(cmd(c)) == SafeCommands.isSafe(c), c);
        }
    }
}
