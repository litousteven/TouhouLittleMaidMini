package com.touhoulittlemaid.mini.fabric.plan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Small container helpers shared by the item modules. */
final class Containers {

    private Containers() {
    }

    static Container at(FabricModuleContext context, BlockPos pos) {
        BlockEntity entity = context.level().getBlockEntity(pos);
        return entity instanceof Container container ? container : null;
    }

    /**
     * Inserts as much of {@code stack} as fits, merging same item and components first.
     *
     * @return the number of items that did not fit
     */
    static int insert(Container container, ItemStack stack) {
        int remaining = stack.getCount();
        int max = Math.min(64, stack.getMaxStackSize());
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int space = Math.min(max, existing.getMaxStackSize()) - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            existing.grow(moved);
            container.setItem(slot, existing);
            remaining -= moved;
        }
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            if (!container.getItem(slot).isEmpty()) {
                continue;
            }
            int moved = Math.min(max, remaining);
            ItemStack copy = stack.copy();
            copy.setCount(moved);
            container.setItem(slot, copy);
            remaining -= moved;
        }
        if (remaining != stack.getCount()) {
            container.setChanged();
        }
        return remaining;
    }

    /** Removes up to {@code wanted} matching items from a container. */
    static ItemStack extract(Container container, boolean tag, String id, int wanted) {
        ItemStack result = ItemStack.EMPTY;
        int left = wanted;
        for (int slot = 0; slot < container.getContainerSize() && left > 0; slot++) {
            ItemStack existing = container.getItem(slot);
            if (!ItemMatch.matches(existing, tag, id)) {
                continue;
            }
            int taken = Math.min(left, existing.getCount());
            ItemStack piece = existing.copyWithCount(taken);
            existing.shrink(taken);
            container.setItem(slot, existing.isEmpty() ? ItemStack.EMPTY : existing);
            if (result.isEmpty()) {
                result = piece;
            } else {
                result.grow(taken);
            }
            left -= taken;
        }
        if (!result.isEmpty()) {
            container.setChanged();
        }
        return result;
    }

    static int countMatching(Container container, boolean tag, String id) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (ItemMatch.matches(stack, tag, id)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
