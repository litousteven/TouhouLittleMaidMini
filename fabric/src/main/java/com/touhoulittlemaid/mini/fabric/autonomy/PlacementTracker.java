package com.touhoulittlemaid.mini.fabric.autonomy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Remembers what the owner has been placing, which is the only honest signal for "the owner is
 * building and is running out of material".
 *
 * <p>Fabric has no block-placed event. This listens to {@code UseBlockCallback} and records a
 * block item used on a position that vanilla would let it replace — the same condition the
 * placement itself uses. It is a signal, not a proof: a false positive only matters if it
 * happens thirty-two times within ten minutes, and then the opportunity is still gated by the
 * chest stock and the tool check.
 */
public final class PlacementTracker {

    public static final long WINDOW_MILLIS = 10 * 60 * 1_000L;
    private static final int MAX_ENTRIES_PER_PLAYER = 512;

    private static final Map<UUID, Deque<Placement>> PLACEMENTS = new ConcurrentHashMap<>();

    private record Placement(long at, String blockId) {
    }

    private PlacementTracker() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() || player == null || hit == null) {
                return InteractionResult.PASS;
            }
            if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                return InteractionResult.PASS;
            }
            var state = level.getBlockState(hit.getBlockPos());
            if (!state.canBeReplaced()) {
                return InteractionResult.PASS;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                return InteractionResult.PASS;
            }
            record(player.getUUID(), id.toString(), System.currentTimeMillis());
            return InteractionResult.PASS;
        });
    }

    /** Also a test hook: GameTest drives placement history without a real block interaction. */
    public static void record(UUID playerId, String itemId, long now) {
        Deque<Placement> queue = PLACEMENTS.computeIfAbsent(playerId,
                key -> new ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new Placement(now, itemId));
            while (queue.size() > MAX_ENTRIES_PER_PLAYER) {
                queue.pollFirst();
            }
        }
    }

    /** Placements per item id inside the window, newest first is irrelevant for a count. */
    public static Map<String, Integer> counts(UUID playerId, long now) {
        Map<String, Integer> counts = new HashMap<>();
        Deque<Placement> queue = PLACEMENTS.get(playerId);
        if (queue == null) {
            return counts;
        }
        synchronized (queue) {
            Iterator<Placement> iterator = queue.iterator();
            while (iterator.hasNext()) {
                Placement placement = iterator.next();
                if (now - placement.at() > WINDOW_MILLIS) {
                    iterator.remove();
                    continue;
                }
                counts.merge(placement.blockId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The most placed id inside the window, or empty when nothing was placed. */
    public static String mostPlaced(UUID playerId, long now) {
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> row : counts(playerId, now).entrySet()) {
            if (row.getValue() > bestCount) {
                best = row.getKey();
                bestCount = row.getValue();
            }
        }
        return best;
    }

    public static int total(UUID playerId, long now) {
        return counts(playerId, now).values().stream().mapToInt(Integer::intValue).sum();
    }

    public static void forget(UUID playerId) {
        PLACEMENTS.remove(playerId);
    }

    public static void clear() {
        PLACEMENTS.clear();
    }
}
