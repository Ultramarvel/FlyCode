package com.flycode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileMailBoxTest {

    @TempDir
    Path tempDir;

    @Test
    void sendCreatesFileWithMessage() throws Exception {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        mb.send("agent-b", new FileMailBox.MailMessage("agent-a", "Hello from A"));

        Path inbox = tempDir.resolve("inboxes/agent-b.json");
        assertTrue(Files.exists(inbox), "Inbox file should be created");

        String content = Files.readString(inbox);
        assertTrue(content.contains("\"from\" : \"agent-a\""));
        assertTrue(content.contains("\"text\" : \"Hello from A\""));
        assertTrue(content.contains("\"read\" : false"));
    }

    @Test
    void readUnreadReturnsOnlyUnread() {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        mb.send("bob", new FileMailBox.MailMessage("alice", "msg1"));
        mb.send("bob", new FileMailBox.MailMessage("carol", "msg2"));

        List<FileMailBox.MailMessage> unread = mb.readUnread("bob");
        assertEquals(2, unread.size());
        assertEquals("alice", unread.get(0).from());
        assertEquals("carol", unread.get(1).from());
    }

    @Test
    void markAllReadMakesUnreadEmpty() {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        mb.send("bob", new FileMailBox.MailMessage("alice", "msg1"));
        mb.send("bob", new FileMailBox.MailMessage("carol", "msg2"));

        mb.markAllRead("bob");

        List<FileMailBox.MailMessage> unread = mb.readUnread("bob");
        assertTrue(unread.isEmpty(), "Should have no unread after markAllRead");
    }

    @Test
    void nonexistentAgentReturnsEmpty() {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        List<FileMailBox.MailMessage> unread = mb.readUnread("nobody");
        assertTrue(unread.isEmpty());
    }

    @Test
    void teamSendMessageIntegration() {
        // 直接验证 FileMailBox 的收发流程，不构造 Team：Team 的构造函数会在
        // 用户主目录下建团队目录，而这个用例并不需要它
        var testMb = new FileMailBox(tempDir.resolve("inboxes"));
        testMb.send("worker", new FileMailBox.MailMessage("leader", "do task X"));

        List<FileMailBox.MailMessage> unread = testMb.readUnread("worker");
        assertEquals(1, unread.size());
        assertEquals("leader", unread.get(0).from());
        assertEquals("do task X", unread.get(0).text());
    }

    /** 并发写同一个收件箱时不能丢消息，写失败也必须抛出来而不是静默吞掉。 */
    @Test
    void 并发发送不丢消息() throws Exception {
        var mb = new FileMailBox(tempDir.resolve("inboxes"));
        int n = 20;
        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        var threads = new java.util.ArrayList<Thread>();

        for (int i = 0; i < n; i++) {
            var t = new Thread(() -> {
                try {
                    mb.send("dest", new FileMailBox.MailMessage("sender", "msg"));
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (var t : threads) {
            t.join();
        }

        assertTrue(errors.isEmpty(), () -> "send failed: " + errors);
        assertEquals(n, mb.readUnread("dest").size());
    }
}
