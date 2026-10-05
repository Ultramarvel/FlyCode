package com.flycode.tui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlyCodeModelDiffRenderTest {

    @Test
    void testRenderDiffLinesColorsByPrefix() {
        String input = "Updated foo.java with 1 addition and 1 removal\n"
                + "   10  unchanged\n"
                + "-   11  old line\n"
                + "+   11  new line";

        String got = FlyCodeModel.renderDiffLines(input);
        String[] lines = got.split("\n", -1);
        assertEquals(4, lines.length);

        assertEquals(Styles.toolDetail.render("Updated foo.java with 1 addition and 1 removal"), lines[0]);
        assertEquals(Styles.toolDetail.render("   10  unchanged"), lines[1]);
        assertEquals(Styles.red("-   11  old line"), lines[2]);
        assertEquals(Styles.green("+   11  new line"), lines[3]);
    }

    @Test
    void testAppendEditDiffOnlyForEditFile() {
        var sb = new StringBuilder();
        FlyCodeModel.appendEditDiff(sb, List.of(
                new ChatMessage.ToolBlockInfo("Bash", Map.of(), "+ should not colorize", false, 0.1, true, false)));
        assertEquals(0, sb.length());

        sb.setLength(0);
        FlyCodeModel.appendEditDiff(sb, List.of(
                new ChatMessage.ToolBlockInfo("EditFile", Map.of(), "+    1  hello", false, 0.1, true, false)));
        assertTrue(sb.toString().contains("hello"));

        sb.setLength(0);
        FlyCodeModel.appendEditDiff(sb, List.of(
                new ChatMessage.ToolBlockInfo("EditFile", Map.of(), "", false, 0.1, true, false)));
        assertEquals(0, sb.length());
    }
}
