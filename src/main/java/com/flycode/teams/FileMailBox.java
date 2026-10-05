package com.flycode.teams;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

public class FileMailBox {

    /**
     * 一条信箱消息。后三个字段服务于结构化消息，普通文本消息留空。
     *
     * <p>type 见 {@link TeamProtocol} 里的常量；requestId 让应答能对上请求；
     * approve 用包装类型是为了区分「没表态」和「明确拒绝」。
     */
    public record MailMessage(String from, String text, String timestamp,
                              boolean read, String color,
                              String type, String requestId, Boolean approve) {
        public MailMessage(String from, String text) {
            this(from, text, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), false, "",
                    null, null, null);
        }

        public MailMessage(String from, String text, String timestamp, boolean read, String color) {
            this(from, text, timestamp, read, color, null, null, null);
        }

        /** 复制一份并改写已读状态，其余字段原样保留。 */
        public MailMessage withRead(boolean value) {
            return new MailMessage(from, text, timestamp, value, color, type, requestId, approve);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 等待文件锁的总时限，到点抛出异常交给调用方处理，不能悄悄把消息扔掉。 */
    private static final long LOCK_ACQUIRE_TIMEOUT_MS = 5_000;
    /** 超过这个时长的锁文件视为持有者已经崩溃，可以强行接管。 */
    private static final long STALE_LOCK_AGE_SECONDS = 10;
    private static final long MIN_BACKOFF_MS = 5;
    /** 退避上限，避免高并发下越退越久。 */
    private static final long MAX_BACKOFF_MS = 80;

    private final Path baseDir;
    /**
     * 同进程内的并发直接用内存锁串行化，文件锁只负责隔离独立进程的 teammate。
     * 省掉一轮文件系统争抢，也避免同进程的线程互相把重试预算耗光。
     */
    private final ReentrantLock mu = new ReentrantLock();

    public FileMailBox(Path baseDir) {
        this.baseDir = baseDir;
        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {}
    }

    private Path inboxPath(String agentId) {
        return baseDir.resolve(agentId + ".json");
    }

    private Path lockPath(String agentId) {
        return baseDir.resolve(agentId + ".json.lock");
    }

    public void send(String recipient, MailMessage msg) {
        withLock(recipient, messages -> {
            var m = msg.withRead(false);
            messages.add(m);
            return messages;
        });
    }

    public List<MailMessage> readUnread(String agentId) {
        List<MailMessage> messages = readInbox(agentId);
        List<MailMessage> unread = new ArrayList<>();
        for (var m : messages) {
            if (!m.read()) unread.add(m);
        }
        return unread;
    }

    public void markAllRead(String agentId) {
        withLock(agentId, messages -> {
            List<MailMessage> updated = new ArrayList<>();
            for (var m : messages) {
                updated.add(m.withRead(true));
            }
            return updated;
        });
    }

    private interface MutationFn {
        List<MailMessage> apply(List<MailMessage> messages);
    }

    private void withLock(String agentId, MutationFn fn) {
        Path lock = lockPath(agentId);

        mu.lock();
        try {
            // 抢文件锁：退避时间指数增长并带抖动，避免多个进程醒在同一时刻反复对撞。
            // 总时限内抢不到就抛出异常，让调用方知道这条消息没写进去。
            long deadline = System.currentTimeMillis() + LOCK_ACQUIRE_TIMEOUT_MS;
            long backoff = MIN_BACKOFF_MS;
            while (true) {
                try {
                    Files.createFile(lock);
                    break;
                } catch (FileAlreadyExistsException e) {
                    // 锁被别人持有，先看它是不是已经陈旧到可以接管
                    try {
                        var modTime = Files.getLastModifiedTime(lock).toInstant();
                        if (Instant.now().minusSeconds(STALE_LOCK_AGE_SECONDS).isAfter(modTime)) {
                            Files.deleteIfExists(lock);
                            continue;
                        }
                    } catch (IOException ignored) {}
                    if (System.currentTimeMillis() >= deadline) {
                        throw new IllegalStateException(
                                "mailbox " + agentId + "：等待文件锁超过 "
                                        + LOCK_ACQUIRE_TIMEOUT_MS + "ms，消息未写入");
                    }
                    long jitter = ThreadLocalRandom.current().nextLong(backoff + 1);
                    try {
                        Thread.sleep(backoff + jitter);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("mailbox " + agentId + "：等待文件锁被中断", ie);
                    }
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
                } catch (IOException e) {
                    throw new UncheckedIOException("mailbox " + agentId + "：创建锁文件失败", e);
                }
            }

            try {
                List<MailMessage> messages = readInbox(agentId);
                messages = fn.apply(messages);
                writeInbox(agentId, messages);
            } finally {
                try { Files.deleteIfExists(lock); } catch (IOException ignored) {}
            }
        } finally {
            mu.unlock();
        }
    }

    private List<MailMessage> readInbox(String agentId) {
        Path path = inboxPath(agentId);
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            byte[] data = Files.readAllBytes(path);
            return MAPPER.readValue(data, new TypeReference<List<MailMessage>>() {});
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private void writeInbox(String agentId, List<MailMessage> messages) {
        Path path = inboxPath(agentId);
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            Files.writeString(path, json);
        } catch (IOException ignored) {}
    }
}
