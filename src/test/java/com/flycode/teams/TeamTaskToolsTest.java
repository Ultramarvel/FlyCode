package com.flycode.teams;

import com.flycode.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamTaskToolsTest {

    @TempDir
    Path tempDir;

    private String origUserDir;
    private TeamManager mgr;

    @BeforeEach
    void setup() {
        // 团队目录是 <home>/.flycode/teams，把主目录指向临时目录，
        // 避免团队配置和任务库写进真实的用户主目录
        origUserDir = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        AgentNameRegistry.getInstance().clear();
        mgr = new TeamManager();
        mgr.createTeam("myteam", TeamManager.TeamMode.IN_PROCESS);
    }

    @AfterEach
    void teardown() {
        System.setProperty("user.home", origUserDir);
    }

    @Test
    void createTeamInitializesEmptySharedStore() {
        assertNotNull(mgr.getTaskStore("myteam"));
        assertEquals(0, mgr.getTaskStore("myteam").listTasks(null, null).size());
    }

    @Test
    void createListUpdateGetFlowSharesOneBoard() {
        var create = new TeamTaskTools.TaskCreateTool(mgr, "myteam", "lead");
        var list = new TeamTaskTools.TaskListTool(mgr, "myteam");
        var update = new TeamTaskTools.TaskUpdateTool(mgr, "myteam");
        var get = new TeamTaskTools.TaskGetTool(mgr, "myteam");

        ToolResult created = create.execute(Map.of("title", "build parser", "assignee", "alice"));
        assertFalse(created.isError());
        assertTrue(created.output().contains("ID: 1"));

        ToolResult listed = list.execute(Map.of());
        assertTrue(listed.output().contains("[1] build parser"));
        assertTrue(listed.output().contains("[alice]"));

        ToolResult updated = update.execute(Map.of("task_id", "1", "status", "completed"));
        assertFalse(updated.isError());
        assertTrue(updated.output().contains("status → completed"));

        ToolResult got = get.execute(Map.of("task_id", "1"));
        assertTrue(got.output().contains("Status:     completed"));

        // 过滤按状态生效
        assertTrue(list.execute(Map.of("status", "completed")).output().contains("build parser"));
        assertTrue(list.execute(Map.of("status", "pending")).output().contains("No tasks found"));
    }

    @Test
    void updateRejectsInvalidStatus() {
        new TeamTaskTools.TaskCreateTool(mgr, "myteam", "lead").execute(Map.of("title", "t"));
        var update = new TeamTaskTools.TaskUpdateTool(mgr, "myteam");
        ToolResult r = update.execute(Map.of("task_id", "1", "status", "done"));
        assertTrue(r.isError());
        assertTrue(r.output().contains("Invalid status"));
    }

    @Test
    void getMissingTaskIsError() {
        var get = new TeamTaskTools.TaskGetTool(mgr, "myteam");
        assertTrue(get.execute(Map.of("task_id", "42")).isError());
    }

    @Test
    void dependenciesAreTracked() {
        var create = new TeamTaskTools.TaskCreateTool(mgr, "myteam", "lead");
        create.execute(Map.of("title", "a"));
        create.execute(Map.of("title", "b", "blocked_by", List.of("1")));

        var get = new TeamTaskTools.TaskGetTool(mgr, "myteam");
        assertTrue(get.execute(Map.of("task_id", "2")).output().contains("Blocked by: 1"));
    }

    @Test
    void deleteTeamRemovesStoreAndUnregistersMembers() {
        AgentNameRegistry.getInstance().register("alice", "alice");
        // 让 alice 成为该团队成员，deleteTeam 才会解绑
        mgr.getTeam("myteam").members.put("alice",
                new TeamManager.Member("alice", null, null));

        mgr.deleteTeam("myteam");
        assertNull(mgr.getTeam("myteam"));
        assertNull(AgentNameRegistry.getInstance().resolve("alice"));
    }
}
