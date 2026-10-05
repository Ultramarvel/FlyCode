package com.flycode.tool;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    ToolCategory category();

    Map<String, Object> schema();

    ToolResult execute(Map<String, Object> args);

    /**
     * 工具要不要延迟加载。延迟的工具不出现在初始 tool list 里，
     * 模型得先用 ToolSearch 把 schema 捞出来才能调。
     *
     * <p>只有 MCP 工具覆盖成 {@code true}。MCP 是按项目配的，一个服务器动辄几十个工具，
     * schema 又长，全塞进初始 tool list 会把上下文占掉一大块，而且大部分工具这次会话
     * 根本用不上。内建工具是固定的那几十个，数量可控，藏起来只会让模型多绕一次
     * ToolSearch，所以一律不延迟，直接给全量 schema。
     */
    default boolean shouldDefer() {
        return false;
    }

    /**
     * 这一次调用能不能跟别的并发跑，按实际参数判断而不是只看类别。
     *
     * <p>不覆写就按类别走：只读的可以并发，写和命令类不行。覆写它的目前只有 Bash：
     * 一条命令是不是只读要看命令本身，ls 和 rm 都是 Bash，并发安全性完全不同。
     */
    default boolean isConcurrencySafe(Map<String, Object> args) {
        return category() == ToolCategory.READ;
    }
}

