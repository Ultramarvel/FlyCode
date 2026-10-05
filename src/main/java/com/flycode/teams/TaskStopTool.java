package com.flycode.teams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;

/**
 * 中止一个在跑的队员。
 * Coordinator 派错方向时用它及时止损，不用等队员把错的活干完。
 *
 * <p>接的是 TeamManager 而不是后台任务表：coordinator 模式下 Lead 通过 Agent 工具
 * 加 team_name 派出去的是队员，由 Team 持有它们的生命周期，后台任务表里没有它们。
 */
public class TaskStopTool implements Tool {
    private final TeamManager teamMgr;

    public TaskStopTool(TeamManager teamMgr) {
        this.teamMgr = teamMgr;
    }

    @Override public String name() { return "TaskStop"; }
    @Override public ToolCategory category() { return ToolCategory.COMMAND; }

    @Override
    public String description() {
        return "Stop a running teammate. Pass the teammate name as it appears in the from= field of a team-notification. "
                + "Use this when you sent a teammate in the wrong direction — for example when "
                + "the user changes requirements after you launched it.";
    }

    @Override
    public Map<String, Object> schema() {
        var props = new LinkedHashMap<String, Object>();
        props.put("teammate", Map.of(
                "type", "string",
                "description", "Name of the teammate to stop, exactly as it appears in the from= field of a team-notification"));

        return Map.of(
                "name", name(),
                "description", description(),
                "input_schema", Map.of(
                        "type", "object",
                        "properties", props,
                        "required", List.of("teammate")
                )
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        String name = (String) args.get("teammate");
        if (name == null || name.isEmpty()) {
            return ToolResult.error("Error: teammate is required");
        }
        if (teamMgr == null) {
            return ToolResult.error("Error: team manager unavailable");
        }

        // 队员名在团队之间可能重名，只在存在该成员的团队里停，避免误杀同名队员
        for (String teamName : teamMgr.listTeams()) {
            TeamManager.Team team = teamMgr.getTeam(teamName);
            if (team == null) {
                continue;
            }
            TeamManager.Member member = team.getMember(name);
            if (member == null) {
                continue;
            }
            if (!member.isActive()) {
                return ToolResult.success(
                        "Teammate '%s' in team '%s' is not running, nothing to stop"
                                .formatted(name, teamName));
            }
            team.stopMember(name);
            return ToolResult.success(
                    "Teammate '%s' in team '%s' stopped.".formatted(name, teamName));
        }

        return ToolResult.error(
                "Error: teammate '%s' not found. Known teammates: %s".formatted(name, knownMembers()));
    }

    /** 把当前所有队员名列给模型，省得它照着记错的名字反复重试 */
    private String knownMembers() {
        List<String> names = new ArrayList<>();
        for (String teamName : teamMgr.listTeams()) {
            TeamManager.Team team = teamMgr.getTeam(teamName);
            if (team != null) {
                names.addAll(team.memberNames());
            }
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }
}
