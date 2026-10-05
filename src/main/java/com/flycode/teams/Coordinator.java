package com.flycode.teams;

import java.util.Set;

/**
 * Coordinator 模式把 Lead 的工具集收窄到纯调度。
 *
 * <p>划线的标准不是「读」和「写」，而是这个工具会不会把大段内容灌进 Lead 的上下文。
 * Lead 的上下文要装任务分解、队员状态和消息记录，一旦它能直接读文件、跑命令，
 * 模型就会忍不住自己去查，几千行代码进来，真正该留给调度的空间就没了。
 * 所以 ReadFile / Glob / Grep / Bash 都不在这里：需要看代码就派队员去看，
 * 队员把结论带回来，Lead 消化结论、写下一步的规格。
 *
 * <p>队员的任务分派靠 Agent 的 prompt 写清楚，不靠共享任务表，因此 TaskCreate /
 * TaskGet / TaskList / TaskUpdate 也不给 Lead，它们是队员之间协调用的。
 * Lead 掌握进度靠队员完成时回传的 task-notification。
 *
 * <p>TeamDelete 留着是为了收尾：队员挂在 Team 上，活干完得有办法停掉它们、清理团队目录。
 * TeamCreate 不在这里，因为 Agent 工具在指定的 Team 不存在时会自己建，
 * Lead 直接派人就行，不必先走一步建团队。
 *
 * <p>四阶段工作流：
 * 1. Research: 队员并行调查，Lead 不下场
 * 2. Synthesis: Lead 消化调查结果，写出实施规格
 * 3. Implementation: 队员按规格改代码并提交
 * 4. Verification: 队员验证改动是否正确
 */
public final class Coordinator {

    private Coordinator() {}

    public static final Set<String> ALLOWED_TOOLS = Set.of(
            "Agent",
            "SendMessage",
            "TaskStop",
            "SyntheticOutput",
            "TeamDelete"
    );

    public static boolean isCoordinatorTool(String name) {
        return ALLOWED_TOOLS.contains(name);
    }

    /**
     * 判断 coordinator 模式当前是否生效，判定条件与工具过滤保持一致。
     *
     * <p>只看配置，不看团队是否存在：模式在会话中途切换会留下麻烦，
     * 已经发出去的调度指引留在对话历史里撤不回来，模型会照着过期的约束继续做事。
     * 配置说了算，从第一轮到最后一轮都是同一套规则。
     *
     * <p>调度指引和工具收窄必须同时出现：只收窄工具不给指引，
     * Lead 只会发现自己读不了文件，却不知道该派队员去读。
     */
    public static boolean isActive(boolean enabled) {
        return enabled;
    }
}
