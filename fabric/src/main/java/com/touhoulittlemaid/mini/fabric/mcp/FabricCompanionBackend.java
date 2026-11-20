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
    private final com.touhoulittlemaid.mini.mcp.EventBus events =
            new com.touhoulittlemaid.mini.mcp.EventBus();

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

    /**
     * Right-clicks a block as the companion.
     *
     * <p>{@code useItemOn} / {@code useWithoutItem} require a Player, but vanilla already
     * gives non-player mobs a supported path: {@code ServerLevel.destroyBlock(pos, true,
     * entity)} harvests a fully grown crop and drops the yield, exactly as
     * {@code HarvestFarmland} does for villagers. That is the interaction this exposes.
     */
    @Override
    public ToolResult interact(UUID companionId, int x, int y, int z) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            var level = companion.level();
            var pos = new net.minecraft.core.BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return ToolResult.rejected("not_loaded", "block is not loaded");
            }

            var state = level.getBlockState(pos);
            String blockId = state.getBlock().builtInRegistryHolder().key().identifier().toString();

            if (state.getBlock() instanceof net.minecraft.world.level.block.CropBlock crop) {
                if (!crop.isMaxAge(state)) {
                    return ToolResult.rejected("not_ready", "crop is not fully grown: " + blockId);
                }
                boolean harvested = level.destroyBlock(pos, true, companion);
                if (!harvested) {
                    return ToolResult.rejected("internal_error", "harvest failed: " + blockId);
                }
                events.publish(companionId.toString(), "operation_completed",
                        "{\"action\":\"harvest\",\"block\":\"" + blockId + "\"}");
                return ToolResult.ok("harvested", "harvested " + blockId);
            }

            return ToolResult.rejected("unsupported_interaction",
                    "No supported block interaction for " + blockId
                            + "; only fully grown crops are handled in M2");
        }));
    }

    @Override
    public ToolResult context(UUID companionId, int radius) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            var level = companion.level();
            var origin = companion.blockPosition();
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            int scanned = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        var pos = origin.offset(dx, dy, dz);
                        if (!level.isLoaded(pos)) {
                            continue;
                        }
                        scanned++;
                        var state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        String id = state.getBlock().builtInRegistryHolder().key()
                                .identifier().toString();
                        counts.merge(id, 1, Integer::sum);
                    }
                }
            }
            StringBuilder json = new StringBuilder("{\"scanned\":").append(scanned)
                    .append(",\"blocks\":{");
            boolean first = true;
            for (var entry : counts.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
            }
            return ToolResult.ok("context", json.append("}}").toString());
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
            return ToolResult.ok("item", itemJson(stack));
        }));
    }

    private static String itemJson(ItemStack stack) {
        return "{\"item\":\"" + stack.getItem().builtInRegistryHolder().key().identifier()
                + "\",\"count\":" + stack.getCount() + '}';
    }

    @Override
    public ToolResult setBehavior(UUID companionId, LocalBehavior behavior) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            LocalBehavior previous = companion.behavior();
            companion.setBehavior(behavior);
            if (previous != behavior) {
                events.publish(companionId.toString(), "behavior_changed",
                        "{\"previous\":\"" + previous.id() + "\",\"current\":\""
                                + behavior.id() + "\"}");
            }
            return ToolResult.ok("behavior_set", behavior.id());
        }));
    }

    @Override
    public ToolResult waitEvents(UUID companionId, long afterSequence,
            int timeoutSeconds, int maxEvents) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            if (events.cursorExpired(afterSequence)) {
                return ToolResult.rejected("event_cursor_expired",
                        "Cursor is older than the retained window; call companion_state first");
            }
            List<com.touhoulittlemaid.mini.core.CompanionEvent> pending =
                    events.after(afterSequence, maxEvents);
            StringBuilder json = new StringBuilder("{\"events\":[");
            for (int index = 0; index < pending.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                json.append(pending.get(index).toJson());
            }
            json.append("],\"next_seq\":").append(events.lastSequence())
                    .append(",\"timed_out\":").append(pending.isEmpty()).append('}');
            return ToolResult.ok("events", json.toString());
        }));
    }

    @Override
    public ToolResult say(UUID companionId, String text) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            var owner = companion.getOwner();
            if (owner instanceof net.minecraft.server.level.ServerPlayer player) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
            }
            return ToolResult.ok("said", "said to owner");
        }));
    }

    @Override
    public ToolResult stop(UUID companionId) {
        return runOnServer(ignored -> withCompanion(companionId, companion -> {
            companion.getNavigation().stop();
            events.publish(companionId.toString(), "operation_stopped",
                    "{\"reason\":\"agent_requested\"}");
            return ToolResult.ok("stopped", "stopped " + companionId);
        }));
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
