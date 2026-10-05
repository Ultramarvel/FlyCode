package com.flycode.teams;

import com.flycode.llm.LlmClient;
import com.flycode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * Unified dispatcher for teammate spawning across all backends.
 */
public final class SpawnDispatcher {

    public record SpawnConfig(
            TeamManager.Team team,
            String memberName,
            String task,
            String addendum,
            LlmClient client,
            ToolRegistry registry,
            String protocol,
            com.flycode.config.ProviderConfig providerConfig,
            String workdir,
            /**
             * 队友的权限检查器。Lead 派人时标了 plan_mode_required，
             * 这里就是一个 PLAN 模式的 checker，队友只能读不能改，直到计划获批。
             */
            com.flycode.permission.PermissionChecker checker
    ) {}

    public record SpawnResult(
            TeamManager.TeamMode mode,
            String paneId
    ) {}

    private SpawnDispatcher() {}

    public static SpawnResult spawnTeammate(SpawnConfig config) throws Exception {
        var team = config.team();
        var mode = team.getMode();

        // 把成员名字登记到全局名称注册表，供 SendMessage 按名字解析投递
        AgentNameRegistry.getInstance().register(config.memberName(), config.memberName());

        switch (mode) {
            case IN_PROCESS -> {
                var member = team.addMember(config.memberName(), config.client(),
                        config.registry(), config.protocol(), config.providerConfig());
                member.agent.setChecker(config.checker());
                if (config.workdir() != null) {
                    member.agent.setWorkDir(config.workdir());
                }
                member.active = true;
                member.thread = Thread.startVirtualThread(() ->
                        TeammateRunner.runInProcessTeammate(team, member, config.task(), config.addendum()));
                return new SpawnResult(mode, null);
            }
            case TMUX -> {
                // 任务写入邮箱，新进程首次轮询即可获取
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String cliCommand = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String paneId = TmuxBackend.spawnTmuxTeammate(team.getName(), config.memberName(), cliCommand);
                recordExternalMember(team, config.memberName(), paneId);
                return new SpawnResult(mode, paneId);
            }
            case ITERM -> {
                // iTerm2 后端：在新标签页中启动队友进程
                if (config.task() != null && !config.task().isEmpty()) {
                    team.sendMessage(TeammateRunner.LEAD_NAME, config.memberName(), config.task());
                }
                String itermCmd = buildTeammateCLI(team.getName(), config.memberName(), config.workdir());
                String tabId = ITermBackend.spawnITermTeammate(team.getName(), config.memberName(), itermCmd);
                recordExternalMember(team, config.memberName(), tabId);
                return new SpawnResult(mode, tabId);
            }
            default -> throw new IllegalStateException("Unsupported team mode: " + mode);
        }
    }

    /**
     * Builds the shell command for a worker process.
     * Format: cd '<workdir>' && '<flycode>' --teammate --team-name <t> --agent-name <n>
     */
    public static String buildTeammateCLI(String teamName, String memberName, String workdir) {
        String wd = workdir != null ? workdir : System.getProperty("user.dir");
        // Find flycode executable (assume it's the current JAR or on PATH)
        String flycode = ProcessHandle.current().info().command().orElse("flycode");
        return "cd %s && %s --teammate --team-name %s --agent-name %s".formatted(
                shellQuote(wd), shellQuote(flycode), shellQuote(teamName), shellQuote(memberName));
    }

    static String shellQuote(String s) {
        if (s.matches("[a-zA-Z0-9_./-]+")) return s;
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void recordExternalMember(TeamManager.Team team, String name, String paneId) {
        // For external backends, create a placeholder member
        var member = new TeamManager.Member(name, null, null);
        member.active = true;
        // Store paneId via field access (simple approach)
        synchronized (team) {
            team.members.put(name, member);
        }
    }
}
