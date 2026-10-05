package com.flycode.conversation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolPairingTest {

    private Message assistantWithTool(String id) {
        Message m = new Message("assistant", "let me check");
        m.setToolUses(List.of(new ToolUseBlock(id, "ReadFile", null)));
        return m;
    }

    private Message resultFor(String id, String content) {
        Message m = new Message("user", "");
        m.setToolResults(List.of(new ToolResultBlock(id, content, false)));
        return m;
    }

    @Test
    void 配对完整时不做改动() {
        var in = List.of(new Message("user", "hi"), assistantWithTool("t1"), resultFor("t1", "content"));
        var got = ToolPairing.ensure(in);
        assertEquals(3, got.size());
        assertEquals("content", got.get(2).getToolResults().get(0).content());
    }

    @Test
    void 悬空调用补上错误结果() {
        var in = List.of(new Message("user", "hi"), assistantWithTool("t1"));
        var got = ToolPairing.ensure(in);
        assertEquals(3, got.size());
        var filled = got.get(2).getToolResults().get(0);
        assertEquals("t1", filled.toolUseId());
        assertTrue(filled.isError());
        assertEquals(ToolPairing.INTERRUPTED_TOOL_RESULT, filled.content());
    }

    @Test
    void 孤儿结果被丢弃() {
        var in = List.of(new Message("user", "hi"), resultFor("ghost", "leftover"),
                new Message("assistant", "ok"));
        var got = ToolPairing.ensure(in);
        assertEquals(2, got.size());
        for (Message m : got) {
            if (m.getToolResults() != null) {
                for (ToolResultBlock tr : m.getToolResults()) {
                    assertNotEquals("ghost", tr.toolUseId());
                }
            }
        }
    }

    @Test
    void 不重复补同一个调用() {
        var in = List.of(assistantWithTool("t1"), new Message("assistant", "still going"));
        var got = ToolPairing.ensure(in);
        long count = got.stream()
                .filter(m -> m.getToolResults() != null)
                .flatMap(m -> m.getToolResults().stream())
                .filter(tr -> "t1".equals(tr.toolUseId()))
                .count();
        assertEquals(1, count);
    }

    @Test
    void 落盘记录能还原工具块() {
        Message assistant = assistantWithTool("t1");
        var record = new com.flycode.session.SessionManager.SessionMessage(
                "assistant", null, "let me check", 0L,
                List.of(new com.flycode.session.SessionManager.ToolUseRecord("t1", "ReadFile", null)),
                List.of());
        Message restored = com.flycode.session.SessionManager.toConversationMessage(record);
        assertEquals(assistant.getContent(), restored.getContent());
        assertEquals(1, restored.getToolUses().size());
        assertEquals("ReadFile", restored.getToolUses().get(0).toolName());
    }
}
