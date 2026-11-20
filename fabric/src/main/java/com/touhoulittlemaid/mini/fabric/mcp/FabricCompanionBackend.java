package com.touhoulittlemaid.mini.fabric.mcp;

import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.mcp.CompanionTools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/**
 * Bridges MCP tool calls onto live Fabric companions.
 *
 * <p>MCP requests arrive on HTTP virtual threads. Every world-touching step is submitted
 * to the server thread and the caller waits for the result, so the transport never
 * mutates the world directly.
 */
public final class FabricCompanionBackend implements CompanionTools.CompanionBackend {

    private static final long REQUEST_TIMEOUT_SECONDS = 10L;

    private final MinecraftServer server;

    public FabricCompanionBackend(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public List<CompanionSnapshot> listCompanions() {
        return runOnServer(ignored -> allCompanions().stream()
                .map(CompanionEntity::snapshot)
                .toList());
    }

    @Override
    public CompanionSnapshot state(UUID companionId) {
        return runOnServer(ignored -> requireCompanion(companionId).snapshot());
    }

    @Override
    public ToolResult move(UUID companionId, int x, int y, int z, double speed) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            companion.getNavigation().moveTo(x + 0.5D, y, z + 0.5D, speed);
            return ToolResult.ok("moving",
                    "moving " + companionId + " to " + x + "," + y + "," + z);
        }));
    }

    @Override
    public ToolResult interact(UUID companionId, int x, int y, int z) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            var level = companion.level();
            var pos = new net.minecraft.core.BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return ToolResult.rejected("not_loaded", "block is not loaded");
            }
            var state = level.getBlockState(pos);
            return ToolResult.ok("observed_block", state.toString());
        }));
    }

    @Override
    public ToolResult inventory(UUID companionId, int slot) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            if (slot < 0 || slot >= companion.backpack().getContainerSize()) {
                return ToolResult.rejected("invalid_params", "slot out of range");
            }
            ItemStack stack = companion.backpack().getItem(slot);
            if (stack.isEmpty()) {
                return ToolResult.ok("empty_slot", "slot " + slot + " is empty");
            }
            return ToolResult.ok("item", stack.getItem().toString() + " x" + stack.getCount());
        }));
    }

    @Override
    public ToolResult setBehavior(UUID companionId, LocalBehavior behavior) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            companion.setBehavior(behavior);
            return ToolResult.ok("behavior_set", behavior.id());
        }));
    }

    @Override
    public ToolResult stop(UUID companionId) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            companion.getNavigation().stop();
            return ToolResult.ok("stopped", "stopped " + companionId);
        }));
    }

    @Override
    public ToolResult wait(UUID companionId, int ticks) {
        return runOnServer(ignored -> withCompanion(companionId,
                companion -> ToolResult.ok("waited", "waited " + ticks + " ticks")));
    }

    private CompanionEntity requireCompanion(UUID companionId) {
        return allCompanions().stream()
                .filter(companion -> companion.getUUID().equals(companionId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown companion: " + companionId));
    }

    /** Wraps a companion lookup so a missing companion becomes a structured refusal. */
    private ToolResult withCompanion(UUID companionId,
            java.util.function.Function<CompanionEntity, ToolResult> action) {
        CompanionEntity companion = allCompanions().stream()
                .filter(candidate -> candidate.getUUID().equals(companionId))
                .findFirst()
                .orElse(null);
        if (companion == null) {
            return ToolResult.rejected("unknown_companion", "No such companion: " + companionId);
        }
        return action.apply(companion);
    }

    private List<CompanionEntity> allCompanions() {
        List<CompanionEntity> companions = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion) {
                    companions.add(companion);
                }
            }
        }
        return companions;
    }

    private <T> T runOnServer(Function<MinecraftServer, T> work) {
        if (server.isSameThread()) {
            return work.apply(server);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(work.apply(server));
            } catch (RuntimeException exception) {
                future.completeExceptionally(exception);
            }
        });
        try {
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("server thread did not answer the MCP request in time",
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server thread",
                    exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    /** Convenience for callers that only need a count and must not keep Minecraft types. */
    public Map<String, Object> serverIdentity() {
        return Map.of("minecraftVersion", server.getServerVersion());
    }

    static Optional<UUID> parseCompanionId(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
