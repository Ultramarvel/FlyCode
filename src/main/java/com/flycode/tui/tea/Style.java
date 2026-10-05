package com.flycode.tui.tea;

import java.util.ArrayList;

// 终端文本样式（前景色、背景色、粗体、内边距），用 ANSI 转义序列渲染
public final class Style {

    private Integer fg;
    private Integer bg;
    private boolean bold;
    private int padLeft;
    private int padRight;

    private Style() {}

    public static Style newStyle() {
        return new Style();
    }

    public Style foreground(ANSI256Color color) {
        this.fg = color.index();
        return this;
    }

    public Style background(ANSI256Color color) {
        this.bg = color.index();
        return this;
    }

    public Style bold(boolean b) {
        this.bold = b;
        return this;
    }

    // padding(vertical, horizontal) — 只用水平方向的左右空格填充
    public Style padding(int vertical, int horizontal) {
        this.padLeft = horizontal;
        this.padRight = horizontal;
        return this;
    }

    public String render(String text) {
        var codes = new ArrayList<String>();
        if (bold) codes.add("1");
        if (fg != null) codes.add("38;5;" + fg);
        if (bg != null) codes.add("48;5;" + bg);

        var sb = new StringBuilder();
        if (padLeft > 0) sb.append(" ".repeat(padLeft));

        if (!codes.isEmpty()) {
            sb.append("\033[").append(String.join(";", codes)).append("m");
            sb.append(text);
            sb.append("\033[0m");
        } else {
            sb.append(text);
        }

        if (padRight > 0) sb.append(" ".repeat(padRight));
        return sb.toString();
    }
}
