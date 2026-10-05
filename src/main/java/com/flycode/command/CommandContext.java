package com.flycode.command;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Runtime context passed to command handlers, providing access to the current
 * application state (permission mode, token counts, model, etc.).
 */
public record CommandContext(
        String args,
        String workDir,
        String model,
        Supplier<String> permissionMode,
        IntSupplier toolCount,
        Supplier<int[]> tokenCount,
        Supplier<List<String>> memoryList,
        Runnable memoryClear,
        Supplier<String> sessionInfo,
        Supplier<List<String>> skillList,
        IntSupplier skillReload,
        Supplier<String> mcpInfo,
        Supplier<String> sandboxStatus,
        Consumer<Integer> sandboxSwitch
) {}

