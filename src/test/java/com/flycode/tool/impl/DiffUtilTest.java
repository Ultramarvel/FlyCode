package com.flycode.tool.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiffUtilTest {

    @Test
    void testSingleLineChange() {
        var d = DiffUtil.buildDiff("a\nb\nc\nd\ne\n", "a\nb\nX\nd\ne\n");
        assertEquals(1, d.additions());
        assertEquals(1, d.removals());
        assertTrue(d.text().contains("-    3  c"));
        assertTrue(d.text().contains("+    3  X"));
        assertTrue(d.text().contains("   2  b"));
        assertTrue(d.text().contains("   4  d"));
    }

    @Test
    void testPureInsertion() {
        var d = DiffUtil.buildDiff("a\nb\n", "a\nX\nY\nb\n");
        assertEquals(0, d.removals());
        assertEquals(2, d.additions());
        assertTrue(d.text().contains("+    2  X"));
        assertTrue(d.text().contains("+    3  Y"));
    }

    @Test
    void testPureDeletion() {
        var d = DiffUtil.buildDiff("a\nb\nc\n", "a\nc\n");
        assertEquals(0, d.additions());
        assertEquals(1, d.removals());
        assertTrue(d.text().contains("-    2  b"));
    }

    @Test
    void testTrimsUnrelatedPrefixSuffix() {
        var oldLines = new StringBuilder();
        for (int i = 0; i < 20; i++) oldLines.append("line").append(i).append("\n");
        String newContent = oldLines.toString().replace("line10\n", "CHANGED\n");

        var d = DiffUtil.buildDiff(oldLines.toString(), newContent);
        assertFalse(d.text().contains("line0\n"));
        assertTrue(d.text().contains("-   11  line10"));
        assertTrue(d.text().contains("+   11  CHANGED"));
    }

    @Test
    void testCapsVeryLargeOutput() {
        var oldLines = new StringBuilder();
        var newLines = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            oldLines.append("old").append(i).append("\n");
            newLines.append("new").append(i).append("\n");
        }
        var d = DiffUtil.buildDiff(oldLines.toString(), newLines.toString());
        assertTrue(d.text().contains("truncated"));
        assertTrue(d.text().split("\n").length < 500);
    }
}
