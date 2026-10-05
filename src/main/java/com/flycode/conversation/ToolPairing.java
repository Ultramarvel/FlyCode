package com.flycode.conversation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Anthropic 要求每个 tool_use 都有配对的 tool_result，缺一个整条请求就会被拒。
 * 会话历史出现不配对的情况有几种来路：用户中断在工具执行途中、进程退出后从磁盘
 * 恢复会话、并发写入交错。这里在发请求前统一补齐，各前端不必各写一遍。
 */
public final class ToolPairing {

    /**
     * 用于补上没有结果的工具调用。工具可能压根没启动，也可能跑到一半被打断，
     * 所以措辞上不能断言它没有产生任何副作用。
     */
    public static final String INTERRUPTED_TOOL_RESULT =
            "Tool execution was interrupted. The tool may or may not have completed; "
            + "verify before relying on its effects.";

    /**
     * 用于用户明确拒绝授权的工具调用。这种情况可以断言什么都没改，必须讲清楚，
     * 否则模型会以为修改已经生效并继续往下走。
     */
    public static final String REJECTED_TOOL_RESULT =
            "The user rejected this tool use. Nothing was changed "
            + "(for file edits, the new content was NOT written).";

    private ToolPairing() {}

    /**
     * 返回一份修好配对关系的消息副本，输入不会被修改。
     * <p>
     * 做两件事：给没有结果的 tool_use 补一条标记为错误的 tool_result（紧跟其后），
     * 丢掉找不到对应 tool_use 的孤儿 tool_result。补出来的内容不写回对话历史：
     * 历史应当如实记录发生过什么，补位只是为了让这一次请求合法。
     */
    public static List<Message> ensure(List<Message> messages) {
        Set<String> resolved = new HashSet<>();
        Set<String> issued = new HashSet<>();
        for (Message m : messages) {
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) resolved.add(tr.toolUseId());
            }
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) issued.add(tu.toolUseId());
            }
        }

        List<Message> out = new ArrayList<>(messages.size());
        for (Message m : messages) {
            Message current = m;
            List<ToolResultBlock> results = m.getToolResults();
            if (results != null && !results.isEmpty()) {
                List<ToolResultBlock> kept = new ArrayList<>();
                for (ToolResultBlock tr : results) {
                    if (issued.contains(tr.toolUseId())) kept.add(tr);
                }
                boolean noText = m.getContent() == null || m.getContent().isEmpty();
                boolean noUses = m.getToolUses() == null || m.getToolUses().isEmpty();
                if (kept.isEmpty() && noText && noUses) {
                    continue; // 整条消息只剩空壳，丢掉以免破坏角色交替
                }
                current = copyWithResults(m, kept);
            }
            out.add(current);

            List<ToolResultBlock> missing = new ArrayList<>();
            if (m.getToolUses() != null) {
                for (ToolUseBlock tu : m.getToolUses()) {
                    if (resolved.contains(tu.toolUseId())) continue;
                    missing.add(new ToolResultBlock(tu.toolUseId(), INTERRUPTED_TOOL_RESULT, true));
                    resolved.add(tu.toolUseId());
                }
            }
            if (!missing.isEmpty()) {
                Message filler = new Message("user", "");
                filler.setToolResults(missing);
                out.add(filler);
            }
        }
        return out;
    }

    private static Message copyWithResults(Message src, List<ToolResultBlock> results) {
        Message copy = new Message(src.getRole(), src.getContent());
        copy.setThinkingBlocks(src.getThinkingBlocks());
        copy.setToolUses(src.getToolUses());
        copy.setToolResults(results);
        return copy;
    }
}
