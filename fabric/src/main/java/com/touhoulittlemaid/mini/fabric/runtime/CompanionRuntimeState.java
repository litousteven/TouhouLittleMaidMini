package com.touhoulittlemaid.mini.fabric.runtime;

import com.touhoulittlemaid.mini.mcp.EventBus;

import java.util.UUID;

/**
 * Process-wide runtime state shared by the MCP endpoint, companion entities and the
 * client-visible synced flags.
 */
public final class CompanionRuntimeState {

    private static volatile EventBus eventBus = new EventBus();
    private static volatile boolean mcpConnected;
    private static final com.touhoulittlemaid.mini.mcp.AgentPresence PRESENCE =
            new com.touhoulittlemaid.mini.mcp.AgentPresence();

    private CompanionRuntimeState() {
    }

    public static EventBus eventBus() {
        return eventBus;
    }

    /** Called once when a server starts so the endpoint and entities share one log. */
    public static void install(EventBus bus) {
        eventBus = java.util.Objects.requireNonNull(bus, "bus");
        PRESENCE.clear();
    }

    public static com.touhoulittlemaid.mini.mcp.AgentPresence presence() {
        return PRESENCE;
    }

    public static void publish(UUID companionId, String kind, String data) {
        eventBus.publish(companionId.toString(), kind, data);
    }

    public static boolean mcpConnected() {
        return mcpConnected;
    }

    public static void setMcpConnected(boolean connected) {
        mcpConnected = connected;
    }
}
