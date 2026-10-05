package com.flycode.tui.tea;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

// 异步命令：Program 在后台执行，执行结果以 Message 形式回送给 update()
public sealed interface Command
        permits Command.Simple, Command.Tick, Command.CheckWindowSize, Command.Batch, Command.PrintLine {

    // 包装一个 Supplier<Message> 为 Command（如 QuitMessage::new）
    static Command of(Supplier<Message> fn) {
        return new Simple(fn);
    }

    // 延时后生产一个 Message（用于 spinner、轮询等定时场景）
    static Command tick(Duration delay, Function<Instant, Message> fn) {
        return new Tick(delay, fn);
    }

    // 查询终端窗口大小，产生 WindowSizeMessage
    static Command checkWindowSize() {
        return new CheckWindowSize();
    }

    // 合并多个 Command 并发执行
    static Command batch(Command... cmds) {
        return new Batch(List.of(cmds));
    }

    // 在 TUI 渲染之外打印一行文本
    static Command println(String text) {
        return new PrintLine(text);
    }

    record Simple(Supplier<Message> fn) implements Command {}
    record Tick(Duration delay, Function<Instant, Message> fn) implements Command {}
    record CheckWindowSize() implements Command {}
    record Batch(List<Command> commands) implements Command {}
    record PrintLine(String text) implements Command {}
}
