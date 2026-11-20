package com.touhoulittlemaid.mini.neoforge;

import com.touhoulittlemaid.mini.core.PlatformState;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.McpServer;
import com.touhoulittlemaid.mini.mcp.McpServerConfig;
import com.touhoulittlemaid.mini.mcp.TokenScope;
import com.touhoulittlemaid.mini.neoforge.content.TlmmContent;
import com.touhoulittlemaid.mini.neoforge.mcp.NeoForgeCompanionBackend;
import com.touhoulittlemaid.mini.neoforge.persistence.M0SavedData;

import java.util.concurrent.atomic.AtomicReference;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Tlmm.MOD_ID)
public final class Tlmm {

    public static final String MOD_ID = "tlmm";
    public static final String MOD_VERSION = "0.1.0-alpha.1";
    public static final String MINECRAFT_VERSION = "26.3";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final AtomicReference<PlatformState> state =
            new AtomicReference<>(PlatformState.starting(MOD_VERSION, MINECRAFT_VERSION));
    private McpServer mcpServer;

    public Tlmm(IEventBus modBus) {
        TlmmContent.register(modBus);
        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(TlmmContent.DEBUG_COMPANION.get(),
                        net.minecraft.world.entity.Mob.createMobAttributes().build()));
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopped);
        LOGGER.info("Touhou Little Maid Mini {} initialized for Minecraft {}",
                MOD_VERSION, MINECRAFT_VERSION);
    }

    private void onServerStarted(ServerStartedEvent event) {
        int saveVersion = M0SavedData.get(event.getServer()).value();
        LOGGER.info("[m0] saved-data baseline value={}", saveVersion);
        state.set(state.get().ready());
        startMcp();
    }

    private void onServerStopped(ServerStoppedEvent event) {
        stopMcp();
        state.set(PlatformState.starting(MOD_VERSION, MINECRAFT_VERSION));
    }

    private void startMcp() {
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
                CompanionTools.registry(new NeoForgeCompanionBackend()),
                TokenScope.FULL,
                event -> LOGGER.info(event.format()));
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
