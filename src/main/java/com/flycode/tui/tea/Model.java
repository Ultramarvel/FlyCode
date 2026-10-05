package com.flycode.tui.tea;

// Bubble Tea 风格的 TUI 模型接口：init 初始化、update 处理消息、view 渲染界面
public interface Model {
    Command init();
    UpdateResult<? extends Model> update(Message msg);
    String view();
    // 退出时输出干净的对话历史（无 TUI chrome），用于终端 scrollback
    default String dumpHistory() { return ""; }
}
