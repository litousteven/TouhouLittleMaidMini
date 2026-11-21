package com.touhoulittlemaid.mini.fabric;

import com.touhoulittlemaid.mini.core.PlatformState;
import com.touhoulittlemaid.mini.fabric.command.CompanionCommands;
import com.touhoulittlemaid.mini.fabric.config.CompanionConfigs;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.fabric.persistence.M0SavedData;
import com.touhoulittlemaid.mini.fabric.persistence.TokenSavedData;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.mcp.CompanionTokenStore;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.EventBus;
import com.touhoulittlemaid.mini.mcp.McpServer;
import com.touhoulittlemaid.mini.mcp.McpServerConfig;
import com.touhoulittlemaid.mini.mcp.RollingEventLog;
import com.touhoulittlemaid.mini.mcp.TokenAuthenticator;
import com.touhoulittlemaid.mini.mcp.TokenScope;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TlmmFabric implements ModInitializer {

    public static final String MOD_ID = "tlmm";
    public static final String MOD_VERSION = "0.1.0-alpha.1";
    public static final String MINECRAFT_VERSION = "26.3";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final CompanionTokenStore TOKEN_STORE = new CompanionTokenStore();

    private final AtomicReference<PlatformState> state =
            new AtomicReference<>(PlatformState.starting(MOD_VERSION, MINECRAFT_VERSION));
    private McpServer mcpServer;
    private RollingEventLog eventLog;

    @Override
    public void onInitialize() {
        FabricContent.register();
        CompanionCommands.register();
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                com.touhoulittlemaid.mini.fabric.runtime.AgentWatchdog::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            int saveVersion = M0SavedData.get(server).value();
            LOGGER.info("[m0] saved-data baseline value={}", saveVersion);
            CompanionConfigs.load();
            TOKEN_STORE.restore(TokenSavedData.get(server).toPersisted());
            EventBus events = new EventBus();
            Path logDirectory = FabricLoader.getInstance().getGameDir().resolve("logs");
            try {
                eventLog = new RollingEventLog(logDirectory, "tlmm-events",
                        CompanionConfigs.current().eventMaxFileMb() * 1024L * 1024L,
                        CompanionConfigs.current().eventRetentionDays());
                events.setListener(eventLog::append);
            } catch (RuntimeException exception) {
                LOGGER.warn("[events] rotating log disabled ({})",
                        exception.getClass().getSimpleName());
                eventLog = null;
            }
            CompanionRuntimeState.install(events);
            CompanionRuntimeState.setMcpConnected(false);
            state.set(state.get().ready());
            startMcp(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            TOKEN_STORE.pruneExpired();
            TokenSavedData.get(server).replace(TOKEN_STORE.export());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            stopMcp();
            if (eventLog != null) {
                eventLog.close();
                eventLog = null;
            }
            CompanionConfigs.reset();
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
        TokenAuthenticator environmentToken = TokenAuthenticator.staticToken(
                token, scopeFromEnvironment());
        TokenAuthenticator authenticator = bearer -> TOKEN_STORE.authenticate(bearer)
                .or(() -> environmentToken.authenticate(bearer));
        McpServer candidate = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, port, token),
                CompanionTools.registry(new FabricCompanionBackend(server,
                        CompanionRuntimeState.eventBus())),
                authenticator,
                event -> LOGGER.info(event.format()));
        try {
            candidate.start();
            mcpServer = candidate;
            CompanionRuntimeState.setMcpConnected(true);
            LOGGER.info("[mcp] listening on http://{}:{}{}", McpServerConfig.LOOPBACK,
                    candidate.port(), McpServer.PATH);
        } catch (Exception exception) {
            candidate.close();
            CompanionRuntimeState.setMcpConnected(false);
            LOGGER.error("[mcp] failed to start loopback endpoint", exception);
        }
    }

    /**
     * A read-only token is opt-in via {@code TLMM_MCP_READ_ONLY=1}; the development
     * default keeps full access because the token is already scoped to loopback.
     */
    private static TokenScope scopeFromEnvironment() {
        return "1".equals(System.getenv("TLMM_MCP_READ_ONLY"))
                ? TokenScope.READ_ONLY
                : TokenScope.FULL;
    }

    public static CompanionTokenStore tokenStore() {
        return TOKEN_STORE;
    }

    /** Mirrors the in-memory token digests into world data so a restart keeps them valid. */
    public static void persistTokens(net.minecraft.server.MinecraftServer server) {
        TokenSavedData.get(server).replace(TOKEN_STORE.export());
    }

    private void stopMcp() {
        McpServer running = mcpServer;
        mcpServer = null;
        CompanionRuntimeState.setMcpConnected(false);
        if (running != null) {
            running.close();
            LOGGER.info("[mcp] stopped");
        }
    }
}
