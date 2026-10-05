package com.flycode.teams;

import java.util.Map;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import com.flycode.prompt.CoordinatorPrompt;
import com.flycode.tool.SyntheticOutputTool;
import com.flycode.tool.ToolResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coordinator 模式的工具边界：Lead 只做调度，看代码和改代码都派给队员。
 */
class CoordinatorTest {
    // 团队配置默认落在用户主目录，测试里改指临时目录，避免污染真实的 ~/.flycode/teams
    @TempDir
    Path teamsHome;
    private String origTeamsHome;

    @BeforeEach
    void isolateTeamsDir() {
        origTeamsHome = System.getProperty("user.home");
        System.setProperty("user.home", teamsHome.toString());
    }

    @AfterEach
    void restoreTeamsDir() {
        System.setProperty("user.home", origTeamsHome);
    }


    @Test
    void blocksToolsThatWouldFloodLeadContext() {
        for (String name : new String[] {"ReadFile", "WriteFile", "EditFile", "Glob", "Grep", "Bash"}) {
            assertFalse(Coordinator.isCoordinatorTool(name), name + " 不该出现在 coordinator 工具集里");
        }
    }

    @Test
    void blocksSharedTaskBoardWhichBelongsToTeammates() {
        for (String name : new String[] {"TaskCreate", "TaskGet", "TaskList", "TaskUpdate"}) {
            assertFalse(Coordinator.isCoordinatorTool(name), name + " 属于队员的协调工具");
        }
    }

    @Test
    void allowsSchedulingTools() {
        for (String name : new String[] {"Agent", "SendMessage", "TaskStop", "SyntheticOutput"}) {
            assertTrue(Coordinator.isCoordinatorTool(name), name + " 是调度必需的工具");
        }
    }

    /** 只看配置：开了就从第一轮起一直生效，不看有没有团队 */
    @Test
    void activeIsStaticOnceEnabled() {
        assertTrue(Coordinator.isActive(true));
        assertFalse(Coordinator.isActive(false));
    }

    /**
     * coordinator 模式下 TeamCreate 不在白名单里，Agent 工具必须能自己把团队建起来，
     * 否则 Lead 想派第一个队员就卡住了。
     */
    @Test
    void teamCreateNotNeededUnderCoordinator() {
        assertFalse(Coordinator.isCoordinatorTool("TeamCreate"));
        assertTrue(Coordinator.isCoordinatorTool("TeamDelete"), "收尾要靠 TeamDelete");
    }

    @Test
    void taskStopReportsUnknownTeammate() {
        TeamManager mgr = new TeamManager();
        mgr.createTeam("squad", TeamManager.TeamMode.IN_PROCESS);

        ToolResult res = new TaskStopTool(mgr).execute(Map.of("teammate", "ghost"));
        assertTrue(res.isError(), "停一个不存在的队员应该报错");
    }

    /** 已经停下的队员再停一次不该报错，避免模型拿着报错反复重试 */
    @Test
    void taskStopOnIdleTeammateIsNotAnError() {
        TeamManager mgr = new TeamManager();
        TeamManager.Team team = mgr.createTeam("squad", TeamManager.TeamMode.IN_PROCESS);
        // 直接放一个成员，绕开 addMember 构造真实 Agent 所需的 provider 配置
        team.members.put("scout", new TeamManager.Member("scout", null, null));

        ToolResult res = new TaskStopTool(mgr).execute(Map.of("teammate", "scout"));
        assertFalse(res.isError());
        assertTrue(res.output().contains("nothing to stop"));
    }

    @Test
    void taskStopRequiresTaskId() {
        ToolResult res = new TaskStopTool(new TeamManager()).execute(Map.of("teammate", ""));
        assertTrue(res.isError());
    }

    @Test
    void syntheticOutputReturnsStringsUntouched() {
        ToolResult res = new SyntheticOutputTool().execute(Map.of("output", "done"));
        assertFalse(res.isError());
        assertEquals("done", res.output());
    }

    @Test
    void syntheticOutputSerializesObjects() {
        ToolResult res = new SyntheticOutputTool().execute(
                Map.of("output", Map.of("status", "ok")));
        assertFalse(res.isError());
        assertTrue(res.output().contains("\"status\""));
    }

    @Test
    void syntheticOutputRejectsMissingRequiredField() {
        var tool = new SyntheticOutputTool(Map.of("type", "object", "required", java.util.List.of("status")));
        ToolResult res = tool.execute(Map.of("output", Map.of("other", 1)));
        assertTrue(res.isError());
        assertTrue(res.output().contains("status"));
    }

    @Test
    void syntheticOutputRejectsWrongTopLevelType() {
        var tool = new SyntheticOutputTool(Map.of("type", "array"));
        ToolResult res = tool.execute(Map.of("output", Map.of("a", 1)));
        assertTrue(res.isError());
    }

    /**
     * 指引描述的回传格式必须和 drainLeadMailbox 真正投递的一致，
     * 否则 Lead 会照着一个不存在的字段去找队员名。
     */
    @Test
    void promptMatchesTeamNotificationFormat() {
        String p = CoordinatorPrompt.buildReminder(1);
        assertTrue(p.contains("<team-notification"), "指引应描述 <team-notification> 格式");
        assertTrue(p.contains("from="), "指引应说明 from= 字段");
        assertFalse(p.contains("<task_id>"), "那是后台子 agent 的通道，不是队员回传的通道");
    }

    /**
     * 这份指引 8KB 出头，而 system-reminder 是逐条追加的，
     * 每轮原样重发会把这个模式省下来的上下文又填回去。
     */
    @Test
    void reminderGoesSparseAfterFirstTurn() {
        String full = CoordinatorPrompt.buildReminder(1);
        String second = CoordinatorPrompt.buildReminder(2);
        assertTrue(second.length() < full.length(), "第二轮应发精简版");
        for (String must : new String[] {"cannot read files", "TaskStop", "from="}) {
            assertTrue(second.contains(must), "精简版丢了关键约束：" + must);
        }
        boolean sawFull = false;
        for (int i = 2; i <= 12; i++) {
            if (CoordinatorPrompt.buildReminder(i).equals(full)) { sawFull = true; break; }
        }
        assertTrue(sawFull, "长会话中应周期性复述全文");
    }

    /** FlyCode 的内建类型是 general-purpose / plan / explore，没有 worker */
    @Test
    void promptDoesNotReferenceMissingSubagentType() {
        String p = CoordinatorPrompt.buildReminder(1);
        assertFalse(p.contains("subagent_type: \"worker\""));
        assertFalse(p.contains("subagent_type `worker`"));
    }

    /** 提示词列出的工具必须就是白名单放行的那几个 */
    @Test
    void promptListsExactlyTheWhitelistedTools() {
        String p = CoordinatorPrompt.buildReminder(1);
        String section = p.substring(p.indexOf("## 2. Your Tools"), p.indexOf("### Worker Results"));
        for (String name : Coordinator.ALLOWED_TOOLS) {
            assertTrue(section.contains("**" + name + "**"), name + " 没出现在提示词的工具清单里");
        }
        for (String name : new String[] {"ReadFile", "Bash", "Grep", "TaskCreate", "TeamCreate"}) {
            assertFalse(section.contains("**" + name + "**"), "提示词列了被过滤掉的 " + name);
        }
    }

    /**
     * 提示词是从源端移植过来的，源端用反引号拼接字符串，
     * 移植时若没还原就会把 ` + "..." + ` 这种噪音留在正文里，
     * 而且专挑最关键的几行。
     */
    @Test
    void promptCarriesNoPortingArtifacts() {
        String p = CoordinatorPrompt.buildReminder(1);
        assertFalse(p.contains("\" + \""), "提示词残留了 Go 的字符串拼接语法");
        assertFalse(p.contains("+ `"), "提示词残留了 Go 的反引号拼接");
    }
}
