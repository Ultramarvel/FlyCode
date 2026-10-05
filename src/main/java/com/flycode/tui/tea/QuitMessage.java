package com.flycode.tui.tea;

// 退出信号，收到此消息后 Program 终止主循环
public record QuitMessage() implements Message {}
