package com.flycode.tool.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * 对比编辑前后的文件内容，生成一段带行号的 diff。
 * 利用"编辑只改动中间一小段"的特点，从两端找公共前缀/后缀行，
 * 避免跑通用的 LCS/Myers diff 算法（对大文件更快，实现也更简单）。
 * 输出格式固定，便于终端渲染和测试断言。
 */
public final class DiffUtil {

    private static final int CONTEXT_LINES = 3;
    // 防止超大文件产出天量 diff 文本拖垮 TUI 渲染和上下文占用
    private static final int MAX_DIFF_LINES = 200;

    private DiffUtil() {}

    public record DiffResult(String text, int additions, int removals) {}

    public static DiffResult buildDiff(String oldContent, String newContent) {
        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        int prefixLen = 0;
        int maxPrefix = Math.min(oldLines.length, newLines.length);
        while (prefixLen < maxPrefix && oldLines[prefixLen].equals(newLines[prefixLen])) {
            prefixLen++;
        }

        int suffixLen = 0;
        int maxSuffix = maxPrefix - prefixLen;
        while (suffixLen < maxSuffix
                && oldLines[oldLines.length - 1 - suffixLen].equals(newLines[newLines.length - 1 - suffixLen])) {
            suffixLen++;
        }

        String[] removedLines = slice(oldLines, prefixLen, oldLines.length - suffixLen);
        String[] addedLines = slice(newLines, prefixLen, newLines.length - suffixLen);

        int contextStart = Math.max(0, prefixLen - CONTEXT_LINES);
        String[] contextBefore = slice(oldLines, contextStart, prefixLen);
        int contextEnd = Math.min(oldLines.length, oldLines.length - suffixLen + CONTEXT_LINES);
        String[] contextAfter = slice(oldLines, oldLines.length - suffixLen, contextEnd);

        List<String> out = new ArrayList<>();
        int[] oldLineNo = {contextStart + 1};
        int[] newLineNo = {contextStart + 1};
        boolean[] truncated = {false};

        for (String l : contextBefore) {
            push(out, truncated, " ", oldLineNo[0], l);
            oldLineNo[0]++;
            newLineNo[0]++;
        }
        for (String l : removedLines) {
            push(out, truncated, "-", oldLineNo[0], l);
            oldLineNo[0]++;
        }
        for (String l : addedLines) {
            push(out, truncated, "+", newLineNo[0], l);
            newLineNo[0]++;
        }
        for (String l : contextAfter) {
            push(out, truncated, " ", oldLineNo[0], l);
            oldLineNo[0]++;
            newLineNo[0]++;
        }

        if (truncated[0]) {
            out.add("  … (diff truncated at %d lines)".formatted(MAX_DIFF_LINES));
        }

        return new DiffResult(String.join("\n", out), addedLines.length, removedLines.length);
    }

    private static void push(List<String> out, boolean[] truncated, String prefix, int lineNo, String content) {
        if (out.size() >= MAX_DIFF_LINES) {
            truncated[0] = true;
            return;
        }
        out.add("%s %4d  %s".formatted(prefix, lineNo, content));
    }

    private static String[] slice(String[] arr, int from, int to) {
        if (to <= from) return new String[0];
        String[] out = new String[to - from];
        System.arraycopy(arr, from, out, 0, to - from);
        return out;
    }
}
