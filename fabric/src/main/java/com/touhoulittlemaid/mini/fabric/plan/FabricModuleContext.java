package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.ModuleContext;
import com.touhoulittlemaid.mini.core.plan.ModuleParams;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What a module may touch while running: one live companion and its level.
 *
 * <p>Modules run on the server thread inside the plan tick, so touching the level here is
 * safe. Anything that needs the HTTP thread goes through the existing backend instead.
 */
public final class FabricModuleContext implements ModuleContext {

    private final CompanionEntity companion;
    private final String planId;

    public FabricModuleContext(CompanionEntity companion, String planId) {
        this.companion = companion;
        this.planId = planId;
    }

    public CompanionEntity companion() {
        return companion;
    }

    public String planId() {
        return planId;
    }

    public ServerLevel level() {
        return (ServerLevel) companion.level();
    }

    @Override
    public String companionId() {
        return companion.getUUID().toString();
    }

    /** Closest container block entity to a position, or null when there is none. */
    public Container containerAt(BlockPos position, int radius) {
        ServerLevel level = level();
        BlockEntity closest = null;
        double best = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(position.getX() + dx, position.getY() + dy,
                            position.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockEntity entity = level.getBlockEntity(cursor);
                    if (!(entity instanceof Container)) {
                        continue;
                    }
                    double distance = cursor.distSqr(position);
                    if (distance < best) {
                        best = distance;
                        closest = entity;
                    }
                }
            }
        }
        return closest instanceof Container container ? container : null;
    }

    /** Inventory view used by predicates: backpack, equipment, both hands. */
    public List<ItemStack> carriedStacks() {
        java.util.ArrayList<ItemStack> stacks = new java.util.ArrayList<>();
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            stacks.add(companion.backpack().getItem(slot));
        }
        for (int slot = 0; slot < companion.equipment().getContainerSize(); slot++) {
            stacks.add(companion.equipment().getItem(slot));
        }
        stacks.add(companion.getMainHandItem());
        stacks.add(companion.getOffhandItem());
        return stacks;
    }

    public int carriedCount(String item) {
        boolean tag = item.startsWith("#");
        String id = tag ? item.substring(1) : item;
        int total = 0;
        for (ItemStack stack : carriedStacks()) {
            if (stack.isEmpty()) {
                continue;
            }
            if (ItemMatch.matches(stack, tag, id)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public int freeSlots() {
        int free = 0;
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            if (companion.backpack().getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /** Snapshot fields a step may bind. */
    public Map<String, Object> positionOutput() {
        return Map.of(
                "x", companion.getBlockX(),
                "y", companion.getBlockY(),
                "z", companion.getBlockZ());
    }
}
