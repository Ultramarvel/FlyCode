package com.flycode.subagent;

import com.flycode.tool.Tool;
import com.flycode.tool.ToolRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * Filters a {@link ToolRegistry} to produce a restricted registry suitable
 * for a sub-agent. The filtering layers (applied in order) are:
 * <ul>
 *   <li>Layer 1: MCP tools (prefixed with "mcp__") always pass through.</li>
 *   <li>Layer 2: {@code ALWAYS_DISALLOWED} — globally blocked tools
 *       (TaskOutput, ExitPlanMode, EnterPlanMode, Agent, AskUserQuestion,
 *       TaskStop, Workflow).</li>
 *   <li>Layer 3: If the agent is a custom agent, also block
 *       {@code CUSTOM_AGENT_DISALLOWED}.</li>
 *   <li>Layer 4: In async mode, only permit {@code ASYNC_ALLOWED} tools.
 *       However, if the agent is an in-process teammate, also allow "Agent"
 *       and the {@code IN_PROCESS_TEAMMATE_ALLOWED} tools.</li>
 *   <li>Layer 5: Per-spec {@code disallowedTools} exclusion.</li>
 *   <li>Layer 6: Per-spec {@code tools} whitelist intersection
 *       (skipped if null/empty or contains only "*").</li>
 * </ul>
 */
public final class ToolFilter {

    /** Tools that are never available to any sub-agent. */
    private static final Set<String> ALWAYS_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    /** 自定义 Agent 额外挡掉的工具，目前与全局禁用集相同，独立维护以便将来收得更紧。 */
    private static final Set<String> CUSTOM_AGENT_DISALLOWED = Set.of(
            "TaskOutput", "ExitPlanMode", "EnterPlanMode",
            "Agent", "AskUserQuestion", "TaskStop", "Workflow"
    );

    /** Tools permitted for async (background) sub-agents. */
    private static final Set<String> ASYNC_ALLOWED = Set.of(
            "ReadFile", "WebSearch", "TodoWrite", "Grep", "WebFetch", "Glob",
            "Bash", "EditFile", "WriteFile", "NotebookEdit", "Skill", "LoadSkill",
            "SyntheticOutput", "ToolSearch", "EnterWorktree", "ExitWorktree",
            // ToolSearch 只负责把 schema 读出来，真正调用要靠 mcp_call，
            // 两个得一起放开，否则后台 Agent 读到 schema 也调不动
            "mcp_call"
    );

    /** Extra tools allowed when the agent is an in-process teammate. */
    private static final Set<String> IN_PROCESS_TEAMMATE_ALLOWED = Set.of(
            "TaskCreate", "TaskGet", "TaskList", "TaskUpdate", "SendMessage",
            "CronCreate", "CronDelete", "CronList"
    );

    /**
     * 队友在协作工具之外额外被挡掉的工具。组建和解散团队由 Lead 负责，队友只管
     * 干活和相互协调，不参与团队成员管理。
     */
    public static final Set<String> TEAMMATE_DISALLOWED = Set.of("TeamCreate", "TeamDelete");

    private ToolFilter() {}

    /**
     * 队友专用过滤：在常规子 Agent 规则之上，再挡掉团队成员管理工具。
     */
    public static ToolRegistry filterForTeammate(ToolRegistry source, SubAgentSpec spec) {
        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : filterForAgent(source, spec).listTools()) {
            if (TEAMMATE_DISALLOWED.contains(tool.name())) continue;
            filtered.register(tool);
        }
        return filtered;
    }

    /**
     * Convenience overload that delegates to the full method with
     * {@code isAsync=false}, {@code isCustom=false}, {@code isInProcessTeammate=false}.
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec) {
        return filterForAgent(source, spec, false, false, false);
    }

    /**
     * 按子 Agent 的 spec 过滤出一份新的 {@link ToolRegistry}，只留它有权使用的工具。
     *
     * @param source              the parent registry to filter from
     * @param spec                the sub-agent specification whose disallowed/allowed tools to honour
     * @param isAsync             if {@code true}, restrict to the async allow-list
     * @param isCustom            if {@code true}, also block {@code CUSTOM_AGENT_DISALLOWED} tools
     * @param isInProcessTeammate if {@code true} (and async), additionally allow "Agent"
     *                            and {@code IN_PROCESS_TEAMMATE_ALLOWED} tools
     * @return a new filtered registry
     */
    public static ToolRegistry filterForAgent(ToolRegistry source, SubAgentSpec spec,
                                              boolean isAsync, boolean isCustom,
                                              boolean isInProcessTeammate) {
        Set<String> disallowed = new HashSet<>(spec.disallowedTools());

        boolean hasWhitelist = spec.tools() != null && !spec.tools().isEmpty()
                && !(spec.tools().size() == 1 && "*".equals(spec.tools().get(0)));
        Set<String> allowed = hasWhitelist ? new HashSet<>(spec.tools()) : Set.of();

        ToolRegistry filtered = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            String name = tool.name();

            // Layer 1: MCP tools always pass through
            if (isMcpTool(name)) {
                filtered.register(tool);
                continue;
            }

            // Layer 2: Globally blocked tools
            if (ALWAYS_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 3: Custom-agent specific blocks
            if (isCustom && CUSTOM_AGENT_DISALLOWED.contains(name)) {
                continue;
            }

            // Layer 4: In async mode, only permit the allow-listed tools
            if (isAsync) {
                boolean asyncAllowed = ASYNC_ALLOWED.contains(name);
                if (!asyncAllowed) {
                    // In-process teammates get extra tools even in async mode
                    if (isInProcessTeammate
                            && ("Agent".equals(name) || IN_PROCESS_TEAMMATE_ALLOWED.contains(name))) {
                        // fall through — permitted
                    } else {
                        continue;
                    }
                }
            }

            // Layer 5: Per-spec disallowed tools
            if (disallowed.contains(name)) {
                continue;
            }

            // Layer 6: Per-spec whitelist intersection
            if (hasWhitelist && !allowed.contains(name)) {
                continue;
            }

            filtered.register(tool);
        }
        return filtered;
    }

    /**
     * Fork 专用：复制父注册表的全部工具，不做任何过滤。
     * 遇到 AgentTool 时浅复制并标记 querySource，
     * 确保 fork 子 Agent 不能再次 fork（运行时拦截），
     * 同时保持工具定义与父 Agent 字节一致以命中 prompt cache。
     */
    public static ToolRegistry cloneForFork(ToolRegistry source) {
        ToolRegistry forked = new ToolRegistry();
        for (Tool tool : source.listTools()) {
            if (tool instanceof AgentTool at) {
                forked.register(at.cloneWithQuerySource("agent:builtin:fork"));
            } else {
                forked.register(tool);
            }
        }
        return forked;
    }

    private static boolean isMcpTool(String name) {
        return name.startsWith("mcp__");
    }
}
