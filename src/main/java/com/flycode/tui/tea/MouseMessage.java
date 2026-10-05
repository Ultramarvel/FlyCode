package com.flycode.tui.tea;

// 鼠标事件，目前只关心滚轮上下
public class MouseMessage implements Message {

    public enum MouseButton {
        MouseButtonWheelUp,
        MouseButtonWheelDown,
        OTHER
    }

    private final MouseButton button;

    public MouseMessage(MouseButton button) {
        this.button = button;
    }

    public MouseButton getButton() {
        return button;
    }
}
