package com.flycode.llm;

import java.util.Map;

public sealed interface StreamEvent {

    record TextDelta(String text) implements StreamEvent {}

    record ThinkingDelta(String text) implements StreamEvent {}

    record ThinkingComplete(String thinking, String signature) implements StreamEvent {}

    record ToolCallStart(String toolId, String toolName) implements StreamEvent {}

    record ToolCallDelta(String text) implements StreamEvent {}

    record ToolCallComplete(String toolId, String toolName, Map<String, Object> arguments) implements StreamEvent {}

    record StreamEnd(String stopReason, int inputTokens, int outputTokens,
                     int cacheReadTokens, int cacheCreationTokens) implements StreamEvent {

        /** Cold-start / non-cache providers: usage carries no cache breakdown. */
        public StreamEnd(String stopReason, int inputTokens, int outputTokens) {
            this(stopReason, inputTokens, outputTokens, 0, 0);
        }
    }

    record Error(String message) implements StreamEvent {}
}
