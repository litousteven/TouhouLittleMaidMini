package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * Outcome of a tool invocation.
 *
 * <p>Every action must be able to report success, refusal, timeout and interruption
 * explicitly rather than collapsing them into one failure.
 */
public record ToolResult(Status status, String code, String message) {

    public enum Status {
        OK,
        REJECTED,
        TIMEOUT,
        INTERRUPTED,
        UNKNOWN_TOOL
    }

    public ToolResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    public static ToolResult ok(String code, String message) {
        return new ToolResult(Status.OK, code, message);
    }

    public static ToolResult rejected(String code, String message) {
        return new ToolResult(Status.REJECTED, code, message);
    }

    public static ToolResult timeout(String code, String message) {
        return new ToolResult(Status.TIMEOUT, code, message);
    }

    public static ToolResult interrupted(String code, String message) {
        return new ToolResult(Status.INTERRUPTED, code, message);
    }

    public static ToolResult unknownTool(String name) {
        return new ToolResult(Status.UNKNOWN_TOOL, "unknown_tool", "Unknown tool: " + name);
    }

    public boolean isError() {
        return status != Status.OK;
    }
}
