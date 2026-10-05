package com.flycode.tui.tea;

// 按键事件：key 是标准化名称（如 "enter"、"ctrl+c"、"up"），runes 是 Unicode 字符数组
public record KeyPressMessage(String key, char[] runes) implements Message {
    public String key() { return key; }
    public char[] runes() { return runes; }
}
