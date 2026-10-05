package com.flycode.teams;

import com.flycode.agent.AgentEvent;
import com.flycode.permission.PermissionMode;
import com.flycode.tui.SpinnerVerbs;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Main loop for in-process teammates.
 */
public final class TeammateRunner {

    public static final String LEAD_NAME = "lead";
    public static final String SHUTDOWN_PREFIX = "[shutdown]";

    public static final long IDLE_POLL_MS = 500;

    private TeammateRunner() {}

    /**
     * Runs a teammate agent loop in the current thread. Blocks until shutdown
     * or context cancellation (thread interrupt).
     */
    public static void runInProcessTeammate(
            TeamManager.Team team,
            TeamManager.Member member,
            String initialPrompt,
            String addendum
    ) {
        BlockingQueue<AgentEvent> eventOut = new LinkedBlockingQueue<>(32);

        // Create progress tracker and attach to member
        var progress = new TeammateProgress(member.getName(), team.getName(), SpinnerVerbs.random());
        member.progress = progress;

        if (addendum != null && !addendum.isEmpty()) {
            member.conv.addSystemReminder(addendum);
        }

        // Inject any pending mailbox messages
        injectPendingMessages(team, member.getName(), member.conv);

        // 首轮任务：只有初始 prompt 非空时才作为用户消息注入。
        // 被 tmux/iTerm 拉起的队友进程首轮任务已在邮箱里（上面 injectPendingMessages 已折叠成
        // system reminder），此时初始 prompt 为空，跳过以免注入一条重复的空用户消息。
        if (initialPrompt != null && !initialPrompt.isEmpty()) {
            member.conv.addUserMessage(initialPrompt);
        }

        // Run agent
        var agentQueue = member.agent.run(member.conv);
        drainAgentEvents(agentQueue, eventOut, progress);

        // 计划模式的队友：一轮跑完意味着它调了 ExitPlanMode，计划已经落到磁盘。
        // 把计划交给 Lead 审批，通过了才解除只读限制开始动手。
        // 放在 idle 通知之前：这时候队友不是闲着等派活，而是卡在审批上，
        // 发一条 idle 会让 Lead 误以为可以塞新任务过来。
        if (!planModeActive(member)) {
            team.sendMessage(member.getName(), LEAD_NAME,
                    createIdleNotification(member.getName(), "available"));
        }

        // Subsequent turns: wait for mailbox messages
        while (!Thread.currentThread().isInterrupted()) {
            if (planModeActive(member)) {
                String next = runPlanApproval(team, member, progress);
                if (next == null) break;
                member.conv.addUserMessage(next);
                agentQueue = member.agent.run(member.conv);
                drainAgentEvents(agentQueue, eventOut, progress);
                continue;
            }

            var result = waitForNextPromptOrShutdown(team, member.getName());
            if (result.shutdown() != null) {
                // 收工前先给 Lead 一个明确答复，让它知道可以回收窗格了。
                // 队友这里一律同意：它已经处在空闲轮询里，手上没有干到一半的活。
                if (TeamProtocol.SHUTDOWN_REQUEST.equals(result.shutdown().type())) {
                    team.getMailBox().send(LEAD_NAME, TeamProtocol.shutdownResponse(
                            member.getName(), result.shutdown().requestId(), true,
                            "acknowledged, shutting down"));
                }
                break;
            }
            if (result.prompt() == null) break;

            member.conv.addUserMessage(result.prompt());
            agentQueue = member.agent.run(member.conv);
            drainAgentEvents(agentQueue, eventOut, progress);

            if (!planModeActive(member)) {
                team.sendMessage(member.getName(), LEAD_NAME,
                        createIdleNotification(member.getName(), "available"));
            }
        }

        member.active = false;
        progress.setStatus("completed");

        // 队友退出时持久化对话记录，用于调试
        try {
            Transcript.saveTranscript(team.getName(), member.getName(), member.conv);
        } catch (Exception ignored) {
            // best-effort：持久化失败不影响正常退出
        }
    }

    /**
     * Drains lead's mailbox across all teams, returning formatted notification strings.
     * Called by the Lead's NotificationFn each iteration.
     */
    public static List<String> drainLeadMailbox(TeamManager teamMgr) {
        if (teamMgr == null) return List.of();
        var result = new java.util.ArrayList<String>();
        for (String teamName : teamMgr.listTeams()) {
            var team = teamMgr.getTeam(teamName);
            if (team == null) continue;
            var messages = team.getMailBox().readUnread(LEAD_NAME);
            if (messages.isEmpty()) continue;

            var sb = new StringBuilder();
            sb.append("<team-notification team=\"").append(teamName).append("\">\n");
            for (var msg : messages) {
                sb.append("from=").append(msg.from()).append(": ").append(msg.text()).append("\n");
            }
            sb.append("</team-notification>");
            result.add(sb.toString());

            team.getMailBox().markAllRead(LEAD_NAME);
        }
        return result;
    }

    /**
     * Builds the system reminder addendum for a teammate.
     */
    public static String buildTeammateAddendum(String teamName, String memberName, List<String> otherMembers) {
        var sb = new StringBuilder();
        sb.append("You are a member of team \"").append(teamName).append("\". ");
        sb.append("Your name is \"").append(memberName).append("\".\n\n");
        if (otherMembers != null && !otherMembers.isEmpty()) {
            sb.append("Other team members: ").append(String.join(", ", otherMembers)).append("\n\n");
        }
        sb.append("You can communicate with teammates using the SendMessage tool.\n");
        sb.append("Messages from teammates arrive as system reminders at the start of each turn.\n");
        sb.append("When you finish your current task, simply stop calling tools — ");
        sb.append("an idle notification will be sent to the lead automatically.");
        return sb.toString();
    }

    /**
     * Injects unread mailbox messages as a system reminder.
     */
    public static void injectPendingMessages(
            TeamManager.Team team, String memberName,
            com.flycode.conversation.ConversationManager conv
    ) {
        var messages = team.getMailBox().readUnread(memberName);
        if (messages.isEmpty()) return;

        var sb = new StringBuilder("You have new messages:\n\n");
        for (var msg : messages) {
            sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
        }
        conv.addSystemReminder(sb.toString());
        team.getMailBox().markAllRead(memberName);
    }

    public static boolean isShutdownRequest(String message) {
        return message != null && message.strip().startsWith(SHUTDOWN_PREFIX);
    }

    public static String createIdleNotification(String memberName, String reason) {
        return "[idle] %s (reason: %s)".formatted(memberName, reason);
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** shutdown 非 null 表示收到了关闭请求，字段本身用于取 requestId 做应答。 */
    private record WaitResult(String prompt, FileMailBox.MailMessage shutdown) {}

    private static WaitResult waitForNextPromptOrShutdown(TeamManager.Team team, String memberName) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WaitResult(null, null);
            }

            var messages = team.getMailBox().readUnread(memberName);
            if (messages.isEmpty()) continue;

            for (var msg : messages) {
                if (TeamProtocol.isShutdownRequest(msg)) {
                    team.getMailBox().markAllRead(memberName);
                    return new WaitResult(null, msg);
                }
            }

            // Format as prompt
            var sb = new StringBuilder("You have new messages from your team:\n\n");
            for (var msg : messages) {
                sb.append("From ").append(msg.from()).append(": ").append(msg.text()).append("\n\n");
            }
            team.getMailBox().markAllRead(memberName);
            return new WaitResult(sb.toString(), null);
        }
        return new WaitResult(null, null);
    }

    /**
     * 队友是否处在计划模式。只有被 Lead 标了 planModeRequired 的队友才会进这个模式。
     */
    private static boolean planModeActive(TeamManager.Member member) {
        return member.agent != null
                && member.agent.getChecker() != null
                && member.agent.getChecker().getMode() == PermissionMode.PLAN;
    }

    /**
     * 把队友写好的计划发给 Lead，阻塞等待批复，返回下一轮该喂给模型的 prompt。
     *
     * <p>队友这时候手上是只读权限，等多久都不会造成破坏，所以这里不设超时：
     * 与其超时后自作主张开始改文件，不如一直等着，由用户从 Lead 那边推进。
     * 返回 null 表示被中断，调用方应退出主循环。
     */
    private static String runPlanApproval(TeamManager.Team team, TeamManager.Member member,
                                          TeammateProgress progress) {
        var req = TeamProtocol.planApprovalRequest(member.getName(), readPlanForReview(member));
        team.getMailBox().send(LEAD_NAME, req);
        progress.setStatus("awaiting plan approval");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(IDLE_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            var messages = team.getMailBox().readUnread(member.getName());
            for (var m : messages) {
                // 只认对应这次请求的批复，别的消息留到下一轮再处理
                if (TeamProtocol.PLAN_APPROVAL_RESPONSE.equals(m.type())
                        && req.requestId().equals(m.requestId())) {
                    team.getMailBox().markAllRead(member.getName());
                    if (TeamProtocol.approved(m)) {
                        // 批准后切回正常权限，队友可以改文件了
                        member.agent.getChecker().setMode(PermissionMode.DEFAULT);
                        return "Lead 已批准你的计划，现在按计划开始执行。";
                    }
                    return "Lead 驳回了你的计划，修改意见：" + m.text() + LINE_SEP
                            + "请据此修订计划后再次提交。";
                }
            }
        }
        return null;
    }

    private static final String LINE_SEP = System.lineSeparator();

    /** 读出队友写好的计划全文，交给 Lead 审阅。 */
    private static String readPlanForReview(TeamManager.Member member) {
        try {
            String path = com.flycode.plan.PlanFile.getOrCreatePlanPath(
                    member.agent != null ? member.agent.getWorkDir() : null);
            String text = java.nio.file.Files.readString(java.nio.file.Path.of(path));
            return text.isBlank() ? "（计划文件为空，队友可能未按要求写入计划）" : text;
        } catch (Exception e) {
            return "（计划文件为空，队友可能未按要求写入计划）";
        }
    }

    private static void drainAgentEvents(BlockingQueue<AgentEvent> source, BlockingQueue<AgentEvent> sink,
                                         TeammateProgress progress) {
        while (true) {
            AgentEvent event;
            try {
                event = source.poll(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.setStatus("failed");
                return;
            }
            if (event == null) return;
            sink.offer(event);

            // Record progress from agent events
            if (event instanceof AgentEvent.ToolUseEvent tue) {
                progress.recordToolUse(tue.toolName(), tue.args());
            } else if (event instanceof AgentEvent.UsageEvent ue) {
                progress.recordTokens(ue.inputTokens(), ue.outputTokens());
            } else if (event instanceof AgentEvent.ErrorEvent) {
                progress.setStatus("failed");
                return;
            } else if (event instanceof AgentEvent.LoopComplete) {
                return;
            }
        }
    }
}
