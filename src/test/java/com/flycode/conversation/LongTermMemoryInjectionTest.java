package com.flycode.conversation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 项目指令、自动记忆、Skill 清单三样都跟着项目走，必须待在首条 system-reminder
 * 里，不能进系统提示词，否则每个项目各有一份系统提示词，跨项目缓存全部失效。
 */
class LongTermMemoryInjectionTest {

    @Test
    void 三样内容都放进首条消息() {
        var conv = new ConversationManager();
        conv.addUserMessage("hello");
        conv.injectLongTermMemory("my instructions", "my memories", "- /pdf: fill forms");

        var msgs = conv.getMessages();
        assertEquals(2, msgs.size());

        // 注入的那条必须排在最前面，位置固定前缀才稳
        var first = msgs.get(0);
        assertEquals("user", first.getRole());
        assertTrue(first.getContent().startsWith("<system-reminder>"),
                "注入内容应包在 system-reminder 里");
        assertTrue(first.getContent().contains("my instructions"));
        assertTrue(first.getContent().contains("my memories"));
        assertTrue(first.getContent().contains("- /pdf: fill forms"));
        assertTrue(first.getContent().contains("availableSkills"));

        assertEquals("hello", msgs.get(1).getContent(), "原有消息应排在注入内容之后");
    }

    @Test
    void 一次会话只注入一条() {
        var conv = new ConversationManager();
        conv.injectLongTermMemory("a", "b", "c");
        conv.injectLongTermMemory("a", "b", "c");

        assertEquals(1, conv.getMessages().size());
    }

    @Test
    void 三样都为空时不产生噪音消息() {
        var conv = new ConversationManager();
        conv.injectLongTermMemory("", "", "");

        assertEquals(0, conv.getMessages().size());
    }

    @Test
    void 只有Skill清单时同样注入() {
        // 项目可能没写 FLYCODE.md 也没有记忆
        var conv = new ConversationManager();
        conv.injectLongTermMemory("", "", "- /review: review code");

        var msgs = conv.getMessages();
        assertEquals(1, msgs.size());
        assertTrue(msgs.get(0).getContent().contains("- /review: review code"));
    }
}
