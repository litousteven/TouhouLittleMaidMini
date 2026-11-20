package com.touhoulittlemaid.mini.fabric;

import com.touhoulittlemaid.mini.core.PlatformState;
import com.touhoulittlemaid.mini.fabric.command.CompanionCommands;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.fabric.persistence.M0SavedData;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.McpServer;
import com.touhoulittlemaid.mini.mcp.McpServerConfig;

import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TlmmFabric implements ModInitializer {

    public static final String MOD_ID = "tlmm";
    public static final String MOD_VERSION = "0.1.0-alpha.1";
    public static final String MINECRAFT_VERSION = "26.3";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final AtomicReference<PlatformState> state =
            new AtomicReference<>(PlatformState.starting(MOD_VERSION, MINECRAFT_VERSION));
    private McpServer mcpServer;

    @Override
    public void onInitialize() {
        FabricContent.register();
        CompanionCommands.register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            int saveVersion = M0SavedData.get(server).value();
            LOGGER.info("[m0] saved-data baseline value={}", saveVersion);
            state.set(state.get().ready());
            startMcp(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            stopMcp();
            state.set(PlatformState.starting(MOD_VERSION, MINECRAFT_VERSION));
        });
        LOGGER.info("Touhou Little Maid Mini {} initialized on Fabric for Minecraft {}",
                MOD_VERSION, MINECRAFT_VERSION);
    }

    private void startMcp(net.minecraft.server.MinecraftServer server) {
        if (mcpServer != null) {
            return;
        }
        if (!Boolean.getBoolean("tlmm.mcp.enabled")) {
            LOGGER.info("[mcp] disabled (set -Dtlmm.mcp.enabled=true for the development endpoint)");
            return;
        }
        String token = System.getenv("TLMM_MCP_TOKEN");
        if (token == null || token.isBlank()) {
            LOGGER.warn("[mcp] development endpoint not started: TLMM_MCP_TOKEN is not set");
            return;
        }

        int port = Integer.getInteger("tlmm.mcp.port", 8765);
        McpServer candidate = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, port, token),
                CompanionTools.registry(new FabricCompanionBackend(server)));
        try {
            candidate.start();
            mcpServer = candidate;
            LOGGER.info("[mcp] listening on http://{}:{}{}", McpServerConfig.LOOPBACK,
                    candidate.port(), McpServer.PATH);
        } catch (Exception exception) {
            candidate.close();
            LOGGER.error("[mcp] failed to start loopback endpoint", exception);
        }
    }

    private void stopMcp() {
        McpServer running = mcpServer;
        mcpServer = null;
        if (running != null) {
            running.close();
            LOGGER.info("[mcp] stopped");
        }
    }
}
