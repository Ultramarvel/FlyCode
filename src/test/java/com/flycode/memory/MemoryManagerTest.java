package com.flycode.memory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** MEMORY.md 索引注入前的截断保护。 */
class MemoryManagerTest {

    @Test
    void 未超限时原样返回() {
        String raw = "- [A](a.md) — 第一条\n- [B](b.md) — 第二条";
        assertEquals(raw, MemoryManager.truncateEntrypointContent(raw));
    }

    @Test
    void 首尾空白被剥掉() {
        assertEquals("- [A](a.md)",
                MemoryManager.truncateEntrypointContent("\n\n  - [A](a.md)  \n\n"));
    }

    @Test
    void 超过行数上限时截断并附警告() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MemoryManager.MAX_ENTRYPOINT_LINES + 50; i++) {
            sb.append("- [item").append(i).append("](f").append(i).append(".md)\n");
        }

        String out = MemoryManager.truncateEntrypointContent(sb.toString());

        assertTrue(out.contains("WARNING"), "截断后必须提示模型索引不完整");
        assertTrue(out.contains("lines (limit: 200)"), "警告要说明是行数超限");
        assertTrue(out.contains("item0"), "保留的应该是靠前的条目");
        assertFalse(out.contains("item249"), "超出上限的条目不应出现");

        // 正文部分（去掉警告）不超过行数上限
        String body = out.substring(0, out.indexOf("\n\n> WARNING"));
        assertEquals(MemoryManager.MAX_ENTRYPOINT_LINES, body.split("\n", -1).length);
    }

    @Test
    void 行数没超但单行极长时按字节截断() {
        // 10 行，每行 4000 字符：行数远未超限，字节数已经翻倍超出
        String longLine = "- [x](x.md) — " + "詳".repeat(1000);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(longLine).append('\n');
        }

        String out = MemoryManager.truncateEntrypointContent(sb.toString());

        assertTrue(out.contains("WARNING"));
        assertTrue(out.contains("index entries are too long"), "应报告是条目过长而非行数超限");

        String body = out.substring(0, out.indexOf("\n\n> WARNING"));
        assertTrue(body.getBytes(StandardCharsets.UTF_8).length <= MemoryManager.MAX_ENTRYPOINT_BYTES,
                "截断后的正文必须落在字节上限内");
    }

    @Test
    void 字节截断切在换行处不切碎条目() {
        String line = "- [x](x.md) — " + "a".repeat(500);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append(line).append('\n');
        }

        String out = MemoryManager.truncateEntrypointContent(sb.toString());
        String body = out.substring(0, out.indexOf("\n\n> WARNING"));

        for (String l : body.split("\n", -1)) {
            assertEquals(line, l, "每一行都应该是完整条目，不能被从中间切开");
        }
    }

    @Test
    void 行数和字节同时超限时警告两者都提() {
        String line = "- [x](x.md) — " + "a".repeat(200);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < MemoryManager.MAX_ENTRYPOINT_LINES + 20; i++) {
            sb.append(line).append('\n');
        }

        String out = MemoryManager.truncateEntrypointContent(sb.toString());

        assertTrue(out.contains("lines and"), "两个上限都超时警告要同时提到行数和体积");
    }
}
