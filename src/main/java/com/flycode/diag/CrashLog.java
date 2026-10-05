package com.flycode.diag;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 记录进程的启动、退出和崩溃现场，供异常退出后追查。
 */
public final class CrashLog {

    private static final Path LOG_DIR = Path.of(".flycode");
    private static final Path LOG_PATH = LOG_DIR.resolve("crash.log");
    // 固定到秒，整分钟时 LocalDateTime 自身的格式会把秒省掉，行首长度就不齐了
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private CrashLog() {
    }

    /**
     * 往崩溃日志追加一行带时间戳的记录。
     * 诊断本身不能反过来把进程搞挂，所以写失败一律静默跳过。
     */
    public static void record(String text) {
        try {
            Files.createDirectories(LOG_DIR);
            String line = "[" + LocalDateTime.now().format(TIMESTAMP) + "] " + text + "\n";
            Files.writeString(LOG_PATH, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** 记录一次异常，带完整调用栈。context 用来区分现场来自哪一层。 */
    public static void recordThrowable(String context, Throwable error) {
        StringWriter buf = new StringWriter();
        error.printStackTrace(new PrintWriter(buf));
        record("crash [" + context + "] " + error.getClass().getName()
                + ": " + error.getMessage() + "\n" + buf);
    }

    /**
     * 安装崩溃诊断，进程启动时调用一次。
     *
     * <p>留下三类痕迹：start 行标记本次运行开始；exit 行由 JVM 关闭钩子在进程
     * 自行退出时写出；未捕获异常处理器兜住任意线程里漏出来的异常，这类异常默认
     * 只把栈打到终端，终端一关就什么都不剩。三者组合即可判定退出方式：有 crash
     * 有 exit 是崩溃退出，只有 start 和 exit 是正常退出，只有 start 说明进程是被
     * 外部强制结束的。JVM 自身的致命错误另有 hs_err_pid*.log 落在工作目录。
     */
    public static void install() {
        long pid = ProcessHandle.current().pid();
        record("start pid=" + pid);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            recordThrowable("thread " + thread.getName(), error);
            // 处理器接管后 JVM 不再自己打印，这里补上，保持终端输出行为不变
            error.printStackTrace();
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> record("exit pid=" + pid)));
    }
}
