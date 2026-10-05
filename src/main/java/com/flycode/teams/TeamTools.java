package com.flycode.teams;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolCategory;
import com.flycode.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Team coordination tools: SendMessage, TeamCreate, TeamDelete.
 */
public final class TeamTools {

    private TeamTools() {}

    // ── SendMessage ────────────────────────────────────────────────────

    public static class SendMessageTool implements Tool {
        private final TeamManager teamMgr;
        private final String senderName;

        public SendMessageTool(TeamManager teamMgr, String senderName) {
            this.teamMgr = teamMgr;
            this.senderName = senderName;
        }

        @Override public String name() { return "SendMessage"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Send a message to another named agent in the team. The recipient will see it on their next turn. "
                    + "Use SendMessage to communicate with teammates by name. Messages arrive as system reminders.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("to", Map.of("type", "string", "description", "Name of the recipient agent"));
            props.put("content", Map.of("type", "string", "description",
                    "Message content to send. For shutdown_request this is the reason; "
                            + "for plan_approval_response this is your feedback when rejecting."));
            props.put("type", Map.of(
                    "type", "string",
                    "enum", List.of(TeamProtocol.TEXT, TeamProtocol.SHUTDOWN_REQUEST,
                            TeamProtocol.SHUTDOWN_RESPONSE, TeamProtocol.PLAN_APPROVAL_RESPONSE),
                    "description", "Message kind, defaults to 'text'. Use 'shutdown_request' to ask a "
                            + "teammate to wrap up (it replies with shutdown_response). Use "
                            + "'plan_approval_response' to answer a teammate's plan, together with "
                            + "'approve' and, when rejecting, feedback in 'content'."));
            props.put("request_id", Map.of("type", "string", "description",
                    "Required for plan_approval_response: copy the requestId from the teammate's "
                            + "plan approval request so it knows which plan you are answering."));
            props.put("approve", Map.of("type", "boolean", "description",
                    "Required for plan_approval_response: true to let the teammate start executing, "
                            + "false to send it back to revise."));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("to", "content")
                    )
            );
        }

        /**
         * 投递结构化消息。它和普通文本走同一个信箱，区别只在于消息上带了
         * type / requestId / approve 三个字段，收件方按字段判断，不用解析措辞。
         */
        private ToolResult sendTyped(String to, String msgType, String requestId,
                                     String content, Boolean approve) {
            FileMailBox.MailMessage msg;
            switch (msgType) {
                case TeamProtocol.SHUTDOWN_REQUEST ->
                        msg = TeamProtocol.shutdownRequest(senderName, content);
                case TeamProtocol.SHUTDOWN_RESPONSE -> {
                    if (approve == null) {
                        return ToolResult.error("Error: shutdown_response requires 'approve'");
                    }
                    msg = TeamProtocol.shutdownResponse(senderName, requestId, approve, content);
                }
                case TeamProtocol.PLAN_APPROVAL_RESPONSE -> {
                    if (requestId == null || requestId.isEmpty() || approve == null) {
                        return ToolResult.error(
                                "Error: plan_approval_response requires both 'request_id' and 'approve'");
                    }
                    msg = TeamProtocol.planApprovalResponse(senderName, requestId, approve, content);
                }
                default -> {
                    return ToolResult.error("Error: unsupported message type " + msgType);
                }
            }

            TeamManager.Team team = senderTeam();
            if (team == null) {
                return ToolResult.error("Error: cannot find team for sender '" + senderName + "'");
            }
            team.getMailBox().send(to, msg);
            return ToolResult.success("%s sent to %s.".formatted(msgType, to));
        }

        /**
         * 找到发送者所属的团队。Lead 本身不在 members 里，
         * 它发消息时取自己名下唯一的那个团队。
         */
        private TeamManager.Team senderTeam() {
            var names = teamMgr.listTeams();
            for (String name : names) {
                TeamManager.Team team = teamMgr.getTeam(name);
                if (team != null && team.hasMember(senderName)) {
                    return team;
                }
            }
            if (TeammateRunner.LEAD_NAME.equals(senderName) && !names.isEmpty()) {
                return teamMgr.getTeam(names.get(0));
            }
            return null;
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String to = (String) args.get("to");
            String content = (String) args.get("content");
            if (to == null || to.isEmpty() || content == null || content.isEmpty()) {
                return ToolResult.error("Error: 'to' and 'content' are required");
            }

            // 结构化消息走独立通道：它们要带 requestId 和表态，拼进正文的话
            // 收件方还得从自然语言里猜，那就退回到「靠理解措辞来协调」了。
            String msgType = (String) args.get("type");
            if (msgType != null && !msgType.isEmpty() && !TeamProtocol.TEXT.equals(msgType)) {
                return sendTyped(to, msgType, (String) args.get("request_id"), content,
                        args.get("approve") instanceof Boolean b ? b : null);
            }

            // 广播：把消息发给发送者所在团队的所有其它成员
            if ("*".equals(to)) {
                for (String teamName : teamMgr.listTeams()) {
                    TeamManager.Team team = teamMgr.getTeam(teamName);
                    if (team == null || !team.hasMember(senderName)) continue;
                    int count = 0;
                    for (String member : team.memberNames()) {
                        if (member.equals(senderName)) continue;
                        team.sendMessage(senderName, member, content);
                        count++;
                    }
                    return ToolResult.success("Message broadcast to %d teammate(s).".formatted(count));
                }
                return ToolResult.error("Error: cannot find team for sender '" + senderName + "'");
            }

            // Route to lead by finding any team the sender belongs to.
            if ("lead".equals(to)) {
                for (String teamName : teamMgr.listTeams()) {
                    TeamManager.Team team = teamMgr.getTeam(teamName);
                    if (team != null && team.hasMember(senderName)) {
                        team.sendMessage(senderName, "lead", content);
                        return ToolResult.success("Message sent to lead.");
                    }
                }
                return ToolResult.error("Error: cannot find team for sender '" + senderName + "'");
            }

            // 通过全局名称注册表把收件人名字解析成投递用的标识；解析不到就按原名兜底
            String resolved = AgentNameRegistry.getInstance().resolve(to);
            String recipient = resolved != null ? resolved : to;

            for (String teamName : teamMgr.listTeams()) {
                TeamManager.Team team = teamMgr.getTeam(teamName);
                if (team == null) continue;
                if (team.hasMember(recipient)) {
                    team.sendMessage(senderName, recipient, content);
                    return ToolResult.success("Message sent to " + to + ".");
                }
                // Fallback: sender belongs to this team but recipient not in Members
                // (tmux mode — each process only knows itself). Write to mailbox directly.
                if (team.hasMember(senderName)) {
                    team.sendMessage(senderName, recipient, content);
                    return ToolResult.success("Message sent to " + to + ".");
                }
            }

            return ToolResult.error("Error: recipient '" + to + "' not found in any team");
        }
    }

    // ── TeamCreate ─────────────────────────────────────────────────────

    public static class TeamCreateTool implements Tool {

        private final TeamManager teamMgr;

        public TeamCreateTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override public String name() { return "TeamCreate"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Create a new team for coordinating multiple agents.\n\n"
                    + "## When to Use\n\n"
                    + "Use this tool proactively whenever:\n"
                    + "- The user explicitly asks to use a team, swarm, or group of agents\n"
                    + "- The user mentions wanting agents to work together, coordinate, or collaborate\n"
                    + "- A task requires sequential or parallel collaboration between multiple agents\n\n"
                    + "When in doubt about whether a task warrants a team, prefer spawning a team.\n\n"
                    + "## Team Workflow\n\n"
                    + "1. **Create a team** with TeamCreate\n"
                    + "2. **Spawn teammates** using the Agent tool with team_name and name parameters — "
                    + "this is REQUIRED to create long-running team members\n"
                    + "3. Teammates work independently and communicate via **SendMessage**\n"
                    + "4. When a teammate finishes, it sends its result to \"lead\" via SendMessage, then goes idle\n"
                    + "5. The lead collects and synthesizes all teammate results\n\n"
                    + "## CRITICAL: Spawning Teammates\n\n"
                    + "To add a member to a team, you MUST pass both team_name and name to the Agent tool:\n"
                    + "```\nAgent({\n"
                    + "  \"team_name\": \"<team name from step 1>\",\n"
                    + "  \"name\": \"<member name, e.g. reviewer>\",\n"
                    + "  \"prompt\": \"...\",\n"
                    + "  \"description\": \"...\"\n"
                    + "})\n```\n"
                    + "Without team_name, the agent runs as a one-shot sub-agent that blocks and returns inline — "
                    + "it will NOT be a team member.\n\n"
                    + "## Teammate Idle State\n\n"
                    + "Teammates go idle after every turn — this is completely normal. "
                    + "Sending a message to an idle teammate wakes them up.\n\n"
                    + "## Communication\n\n"
                    + "- Use SendMessage to talk to teammates by name\n"
                    + "- Messages from teammates arrive as system reminders at the start of each turn\n"
                    + "- Messages are delivered automatically — you do NOT need to manually check your inbox";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name for the team"));
            props.put("description", Map.of("type", "string", "description", "What this team will work on"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("team_name")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return ToolResult.error("Error: team_name is required");
            }

            String baseName = name;
            for (int i = 2; teamMgr.getTeam(name) != null; i++) {
                name = baseName + "-" + i;
            }

            TeamManager.TeamMode mode = TeamManager.detectBackend();
            String desc = args.get("description") instanceof String s ? s : "";
            TeamManager.Team team = teamMgr.createTeam(name, mode, TeammateRunner.LEAD_NAME, desc);
            return ToolResult.success(
                    "Team \"%s\" created (mode: %s). Use Agent tool with team_name=\"%s\" to add teammates.\nDescription: %s"
                            .formatted(team.getName(), team.getMode(), team.getName(), desc));
        }
    }

    // ── TeamDelete ─────────────────────────────────────────────────────

    public static class TeamDeleteTool implements Tool {
        private final TeamManager teamMgr;

        public TeamDeleteTool(TeamManager teamMgr) {
            this.teamMgr = teamMgr;
        }

        @Override public String name() { return "TeamDelete"; }
        @Override public ToolCategory category() { return ToolCategory.COMMAND; }

        @Override
        public String description() {
            return "Delete a team, stopping all its members.";
        }

        @Override
        public Map<String, Object> schema() {
            var props = new LinkedHashMap<String, Object>();
            props.put("team_name", Map.of("type", "string", "description", "Name of the team to delete"));

            return Map.of(
                    "name", name(),
                    "description", description(),
                    "input_schema", Map.of(
                            "type", "object",
                            "properties", props,
                            "required", List.of("team_name")
                    )
            );
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String name = (String) args.get("team_name");
            if (name == null || name.isEmpty()) {
                return ToolResult.error("Error: team_name is required");
            }

            TeamManager.Team team = teamMgr.getTeam(name);
            if (team == null) {
                return ToolResult.error("Error: team '%s' not found".formatted(name));
            }

            List<String> memberNames = team.memberNames();
            teamMgr.deleteTeam(name);
            return ToolResult.success(
                    "Team \"%s\" deleted. Stopped %d member(s): %s"
                            .formatted(name, memberNames.size(), String.join(", ", memberNames)));
        }
    }
}
