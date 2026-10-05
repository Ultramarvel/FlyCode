package com.flycode.conversation;

import java.util.ArrayList;
import java.util.List;

public class ConversationManager {

    private final List<Message> history = new ArrayList<>();

    private boolean ltmInjected = false;

    private int baselineTokens = 0;
    private int anchorCount = 0;

    public void addUserMessage(String content) {
        history.add(new Message("user", content));
    }

    public void addAssistantMessage(String content) {
        history.add(new Message("assistant", content));
    }

    public void addAssistantFull(String text, List<ThinkingBlock> thinking, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setThinkingBlocks(thinking);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addAssistantMessageWithTools(String text, List<ToolUseBlock> toolUses) {
        var msg = new Message("assistant", text);
        msg.setToolUses(toolUses);
        history.add(msg);
    }

    public void addToolResultsMessage(List<ToolResultBlock> results) {
        var msg = new Message("user", "");
        msg.setToolResults(results);
        history.add(msg);
    }

    public void injectLongTermMemory(String instructions, String memories, String skills) {
        if (ltmInjected) return;
        var sections = new ArrayList<String>();
        if (instructions != null && !instructions.isEmpty()) {
            sections.add("# flycodeMd\nCodebase and user instructions are shown below. Be sure to adhere to these instructions. IMPORTANT: These instructions OVERRIDE any default behavior and you MUST follow them exactly as written.\n\n" + instructions);
        }
        if (memories != null && !memories.isEmpty()) {
            sections.add("# autoMemory\n" + memories);
        }
        // Skill 清单跟着项目走，放系统提示词会让每个项目各有一份、跨项目缓存全失效，
        // 所以和指令、记忆一样放在这条消息里
        if (skills != null && !skills.isEmpty()) {
            sections.add("# availableSkills\n" + skills);
        }
        if (sections.isEmpty()) return;
        sections.add("# currentDate\nToday's date is " + java.time.LocalDate.now() + ".");
        String body = String.join("\n\n", sections);
        String wrapped = "<system-reminder>\nAs you answer the user's questions, you can use the following context:\n" +
            body +
            "\n\n      IMPORTANT: this context may or may not be relevant to your tasks. You should not respond to this context unless it is highly relevant to your task.\n</system-reminder>";
        history.add(0, new Message("user", wrapped));
        ltmInjected = true;
    }

    public void resetLtmInjected() {
        ltmInjected = false;
    }

    public void addSystemReminder(String content) {
        history.add(new Message("user", "<system-reminder>\n" + content + "\n</system-reminder>"));
    }

    /**
     * 历史里还有没有包含 marker 的提醒。
     *
     * <p>用来判断一条「只需要说一次」的提醒是否还在上下文里。compact 会把历史压成摘要，
     * 原来那条提醒随之消失，这时候必须重发，否则模型再也看不到。调用方拿这个结果决定
     * 重发，就不用在 compact 那边额外挂钩子。
     */
    public boolean hasReminderContaining(String marker) {
        for (var msg : history) {
            if ("user".equals(msg.getRole()) && msg.getContent() != null
                    && msg.getContent().contains(marker)) {
                return true;
            }
        }
        return false;
    }

    public List<Message> getMessages() {
        return List.copyOf(history);
    }

    public List<Message> getMessagesMutable() {
        return history;
    }

    public int size() {
        return history.size();
    }

    public void truncateTo(int index) {
        if (index >= 0 && index < history.size()) {
            history.subList(index, history.size()).clear();
        }
    }

    public void recordUsageAnchor(int input, int output, int cacheRead, int cacheCreation) {
        int baseline = input + cacheRead + cacheCreation + output;
        if (baseline <= 0) return;
        this.baselineTokens = baseline;
        this.anchorCount = history.size();
    }

    public void clearUsageAnchor() {
        this.baselineTokens = 0;
        this.anchorCount = 0;
    }

    public int getBaselineTokens() {
        return baselineTokens;
    }

    public int getAnchorCount() {
        return anchorCount;
    }

    public boolean hasUsageAnchor() {
        return baselineTokens > 0;
    }

}
