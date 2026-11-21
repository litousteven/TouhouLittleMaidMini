package com.touhoulittlemaid.mini.fabric.entity.goal;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic baseline farming without a model or external agent.
 *
 * <p>Only vanilla {@link CropBlock} semantics are hard coded. One new compatible crop
 * needs no new Task; other modded crops are handled by data/Skill or a model request.
 */
public final class FarmGoal extends Goal {

    private static final BlockPos[] SIDES = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };

    private final CompanionEntity companion;
    private BlockPos target;

    public FarmGoal(CompanionEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (companion.behavior() != LocalBehavior.FARM || companion.isSafetyStopped()
                || !companion.isWorkTime() || companion.tickCount % 40 != 0
                || !companion.getNavigation().isDone()) {
            return false;
        }
        target = findMatureCrop();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && companion.behavior() == LocalBehavior.FARM
                && !companion.isSafetyStopped() && isMature(target);
    }

    @Override
    public void stop() {
        target = null;
    }

    @Override
    public void tick() {
        if (target == null || !isMature(target)) {
            target = null;
            return;
        }
        if (companion.distanceToSqr(target.getX() + 0.5D, target.getY(),
                target.getZ() + 0.5D) > 6.25D) {
            BlockPos stand = standPosition(target);
            if (stand != null && companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(stand.getX() + 0.5D, stand.getY(),
                        stand.getZ() + 0.5D, 1.0D);
            }
            return;
        }
        companion.getNavigation().stop();
        BlockState state = companion.level().getBlockState(target);
        if (!(state.getBlock() instanceof CropBlock crop)) {
            target = null;
            return;
        }
        if (companion.level().destroyBlock(target, true, companion)) {
            CompanionRuntimeState.publish(companion.getUUID(), "harvest_completed",
                    "{\"source\":\"local_farm\"}");
            replant(crop, target);
        }
        target = null;
    }

    private BlockPos findMatureCrop() {
        BlockPos origin = companion.blockPosition();
        int radius = Math.min(companion.workRadius(), 12);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!companion.level().isLoaded(pos) || !isMature(pos)) {
                        continue;
                    }
                    double distance = companion.distanceToSqr(pos.getX() + 0.5D,
                            pos.getY(), pos.getZ() + 0.5D);
                    if (distance < bestDistance && standPosition(pos) != null) {
                        best = pos;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean isMature(BlockPos pos) {
        BlockState state = companion.level().getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    private BlockPos standPosition(BlockPos crop) {
        for (BlockPos side : SIDES) {
            BlockPos pos = crop.offset(side);
            if (companion.level().isLoaded(pos)
                    && companion.level().getBlockState(pos).isAir()) {
                return pos;
            }
        }
        return null;
    }

    private void replant(CropBlock crop, BlockPos pos) {
        if (!companion.level().getBlockState(pos).isAir()
                || !(companion.level().getBlockState(pos.below()).getBlock()
                instanceof FarmlandBlock)) {
            return;
        }
        var backpack = companion.backpack();
        for (int slot = 0; slot < backpack.getContainerSize(); slot++) {
            ItemStack stack = backpack.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                    || blockItem.getBlock() != crop) {
                continue;
            }
            companion.level().setBlockAndUpdate(pos, crop.defaultBlockState());
            stack.shrink(1);
            backpack.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            backpack.setChanged();
            CompanionRuntimeState.publish(companion.getUUID(), "replant_completed",
                    "{\"source\":\"local_farm\"}");
            return;
        }
    }
}
