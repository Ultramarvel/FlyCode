package com.flycode.diag;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashLogTest {

    // 崩溃日志固定写在工作目录下，JVM 起来之后工作目录不可改，
    // 所以用例自己负责写前清空、写后清理
    private static final Path LOG = Path.of(".flycode", "crash.log");

    @Test
    void recordAppendsWithTimestamp() throws IOException {
        Files.deleteIfExists(LOG);
        try {
            CrashLog.record("start pid=1");
            try {
                throw new RuntimeException("boom");
            } catch (RuntimeException e) {
                CrashLog.recordThrowable("tui", e);
            }

            String content = Files.readString(LOG, StandardCharsets.UTF_8);
            // 时间戳精确到秒，整分钟时也不能少掉秒位
            assertTrue(content.matches("(?s)^\\[\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}] .*"), content);
            assertTrue(content.contains("start pid=1"), content);
            assertTrue(content.contains("crash [tui] java.lang.RuntimeException: boom"), content);
            assertTrue(content.contains("at com.flycode.diag.CrashLogTest"), content);
            // 追加写：后一条不能把前一条冲掉
            assertTrue(content.indexOf("start pid=1") < content.indexOf("crash [tui]"), content);
        } finally {
            Files.deleteIfExists(LOG);
        }
    }
}
