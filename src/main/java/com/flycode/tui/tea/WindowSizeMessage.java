package com.flycode.tui.tea;

// 终端窗口大小变化事件
public record WindowSizeMessage(int width, int height) implements Message {}
