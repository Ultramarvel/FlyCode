package com.flycode.teams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 后端检测逻辑单测：只有身处 tmux / iTerm2 会话时才用窗格后端。
 * 直接给 detectBackendFromEnv 传入环境值，避免依赖运行平台的真实环境变量。
 */
class TeamBackendDetectionTest {

    @Test
    void tmuxEnvSelectsTmux() {
        // TMUX 非空 → TMUX
        assertEquals(TeamManager.TeamMode.TMUX,
                TeamManager.detectBackendFromEnv("/tmp/tmux-1000/default,1234,0", null));
    }

    @Test
    void itermEnvSelectsIterm() {
        // TMUX 为空、ITERM_SESSION_ID 非空 → ITERM
        assertEquals(TeamManager.TeamMode.ITERM,
                TeamManager.detectBackendFromEnv(null, "w0t0p0:UUID"));
    }

    @Test
    void tmuxTakesPrecedenceOverIterm() {
        // 两者都在时优先 tmux
        assertEquals(TeamManager.TeamMode.TMUX,
                TeamManager.detectBackendFromEnv("has-tmux", "has-iterm"));
    }

    @Test
    void noEnvFallsBackToInProcess() {
        // 都无（null 或空串）→ 进程内
        assertEquals(TeamManager.TeamMode.IN_PROCESS,
                TeamManager.detectBackendFromEnv(null, null));
        assertEquals(TeamManager.TeamMode.IN_PROCESS,
                TeamManager.detectBackendFromEnv("", ""));
    }

    @Test
    void detectBackendIsInProcessOnWindows() {
        // Windows 护栏：无论环境变量如何，Windows 上 detectBackend 恒为进程内
        String os = System.getProperty("os.name");
        assumeTrue(os != null && os.toLowerCase().contains("windows"),
                "该断言只在 Windows 平台校验");
        assertEquals(TeamManager.TeamMode.IN_PROCESS, TeamManager.detectBackend());
    }
}
