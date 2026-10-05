package com.flycode.teams;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.flycode.config.ProviderConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 团队配置落盘与从磁盘重建。
 * 通过改 user.home 把团队根目录指到临时目录，避免测试污染真实的用户主目录。
 */
class TeamFileTest {

    @TempDir
    Path tempHome;

    private String origHome;

    @BeforeEach
    void setUp() {
        origHome = System.getProperty("user.home");
        System.setProperty("user.home", tempHome.toString());
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.home", origHome);
    }

    /** addMember 会构造真实的 Agent，需要一份最小可用的 ProviderConfig。 */
    private static ProviderConfig cfg() {
        return new ProviderConfig();
    }

    @Test
    void 团队配置写盘后可以被新的_TeamManager_读回来() {
        TeamManager tm = new TeamManager();
        TeamManager.Team team = tm.createTeam("Refactor Auth", TeamManager.TeamMode.IN_PROCESS,
                "lead", "重构认证模块");
        team.addMember("alice", null, null, "anthropic", cfg());
        team.setMemberMeta("alice", "worker", "claude-sonnet-4-6", "/tmp/wt/alice");

        // 换一个全新的 manager，模拟队员进程或下一次会话
        TeamManager fresh = new TeamManager();
        TeamManager.Team got = fresh.getTeam("Refactor Auth");

        assertNotNull(got, "期望从磁盘重建出团队");
        assertEquals("lead", got.leadAgentId);
        assertEquals("重构认证模块", got.description);

        TeamManager.Member m = got.getMember("alice");
        assertNotNull(m, "成员 alice 应该被恢复出来");
        assertEquals("worker", m.agentType);
        assertEquals("claude-sonnet-4-6", m.model);
        assertEquals("/tmp/wt/alice", m.worktreePath);
    }

    @Test
    void 团队目录名做过_slug_化() {
        TeamManager tm = new TeamManager();
        tm.createTeam("Refactor Auth!", TeamManager.TeamMode.TMUX, "lead", "");

        Path expected = TeamManager.teamsBaseDir().resolve("refactor-auth-").resolve("config.json");
        assertTrue(Files.exists(expected), "期望配置落在 " + expected);
    }

    @Test
    void 拆团队会清掉整个团队目录() {
        TeamManager tm = new TeamManager();
        tm.createTeam("gone", TeamManager.TeamMode.IN_PROCESS, "lead", "");
        assertTrue(Files.exists(TeamManager.teamDir("gone")), "建团队后目录应存在");

        tm.deleteTeam("gone");
        assertFalse(Files.exists(TeamManager.teamDir("gone")), "拆团队后目录应被清掉");
        assertNull(new TeamManager().getTeam("gone"), "拆掉的团队不该还能从磁盘捞回来");
    }

    @Test
    void 不存在的团队返回_null() {
        assertNull(new TeamManager().getTeam("never-existed"));
    }

    @Test
    void 成员的活跃状态会写进配置() {
        TeamManager tm = new TeamManager();
        TeamManager.Team team = tm.createTeam("t", TeamManager.TeamMode.IN_PROCESS, "lead", "");
        team.addMember("bob", null, null, "anthropic", cfg());
        team.stopMember("bob");

        TeamFile tf = TeamFile.read(TeamManager.teamConfigPath("t"));
        assertNotNull(tf);
        assertEquals(1, tf.members.size());
        assertEquals(Boolean.FALSE, tf.members.get(0).isActive);
    }
}
