package com.touhoulittlemaid.mini.fabric.runtime;

import com.touhoulittlemaid.mini.core.CompanionServerConfig;
import com.touhoulittlemaid.mini.fabric.config.CompanionConfigs;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;

/**
 * Applies the configured safe mode when an agent stops talking to a companion.
 *
 * <p>Only an active body operation triggers this; read-only polling keeps the session
 * alive. With {@code continue_operation_after_disconnect = true}, the operation is left
 * running but the disconnect event is still emitted.
 */
public final class AgentWatchdog {

    private AgentWatchdog() {
    }

    public static void tick(MinecraftServer server) {
        CompanionServerConfig config = CompanionConfigs.current();
        long graceMillis = config.disconnectGraceSeconds() * 1_000L;
        for (UUID companionId : CompanionRuntimeState.presence().dueForDisconnect(graceMillis)) {
            CompanionEntity companion = find(server, companionId);
            if (companion != null && !config.continueOperationAfterDisconnect()) {
                companion.getNavigation().stop();
                companion.setTarget(null);
                companion.setBehavior(config.safeModeAfterDisconnect());
            }
            CompanionRuntimeState.publish(companionId, "mcp_disconnected",
                    "{\"grace_seconds\":" + config.disconnectGraceSeconds()
                            + ",\"safe_mode\":\""
                            + config.safeModeAfterDisconnect().id() + "\"}");
            CompanionRuntimeState.presence().markDisconnected(companionId);
        }
    }

    private static CompanionEntity find(MinecraftServer server, UUID companionId) {
        for (var level : server.getAllLevels()) {
            if (level.getEntityInAnyDimension(companionId) instanceof CompanionEntity companion) {
                return companion;
            }
        }
        return null;
    }
}
