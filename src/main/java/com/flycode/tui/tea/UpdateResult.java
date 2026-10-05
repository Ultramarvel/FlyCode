package com.flycode.tui.tea;

import java.util.function.Supplier;

// update() 返回值：更新后的 Model + 可选的异步 Command
public record UpdateResult<M extends Model>(M model, Command command) {

    public static <M extends Model> UpdateResult<M> from(M model) {
        return new UpdateResult<>(model, null);
    }

    public static <M extends Model> UpdateResult<M> from(M model, Command cmd) {
        return new UpdateResult<>(model, cmd);
    }

    // 支持 UpdateResult.from(this, QuitMessage::new) 写法
    public static <M extends Model> UpdateResult<M> from(M model, Supplier<Message> fn) {
        return new UpdateResult<>(model, Command.of(fn));
    }
}
