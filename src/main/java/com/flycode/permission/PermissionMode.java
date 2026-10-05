package com.flycode.permission;

import com.flycode.tool.ToolCategory;

public enum PermissionMode {

    DEFAULT,
    ACCEPT_EDITS,
    PLAN,
    BYPASS;

    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    public enum Decision {
        ALLOW, DENY, ASK
    }
}

