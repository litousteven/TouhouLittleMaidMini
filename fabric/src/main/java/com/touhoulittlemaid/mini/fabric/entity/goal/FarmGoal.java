package com.touhoulittlemaid.mini.fabric.entity.goal;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.semantic.SemanticResolution;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.fabric.semantic.CropShape;
import com.touhoulittlemaid.mini.fabric.semantic.SemanticService;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Deterministic baseline farming, driven by the semantic resolver instead of a hard-coded
 * crop class.
 *
 * <p>Anything with an {@code age} property is a candidate. If the resolver cannot classify
 * it, the companion does not guess and does not silently skip: it publishes
 * {@code unknown_category} once per block id so the player (or a later model pass) can add
 * the missing knowledge.
 */
public final class FarmGoal extends Goal {

    private static final BlockPos[] SIDES = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };

    private final CompanionEntity companion;
    private final Set<String> reportedUnknown = new HashSet<>();
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
        if (!SemanticService.resolve(state).has(SemanticTag.CROP)) {
            target = null;
            return;
        }
        Block crop = state.getBlock();
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
                    if (!companion.level().isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = companion.level().getBlockState(pos);
                    if (!CropShape.looksLikeCrop(state)) {
                        continue;
                    }
                    SemanticResolution resolution = SemanticService.resolve(state);
                    if (!resolution.has(SemanticTag.CROP)) {
                        reportUnknown(resolution);
                        continue;
                    }
                    if (!CropShape.isMature(state)) {
                        continue;
                    }
                    double distance = companion.distanceToSqr(pos.getX() + 0.5D,
                            pos.getY(), pos.getZ() + 0.5D);
                    if (distance < bestDistance && standPosition(pos) != null) {
                        best = pos.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private void reportUnknown(SemanticResolution resolution) {
        String id = resolution.id();
        if (id == null || id.isEmpty() || !reportedUnknown.add(id)) {
            return;
        }
        com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService.noteUnknown(id,
                com.touhoulittlemaid.mini.core.semantic.SemanticKind.BLOCK);
        CompanionRuntimeState.publish(companion.getUUID(), "unknown_category",
                "{\"block\":\"" + id + "\",\"source\":\"local_farm\"}");
    }

    private boolean isMature(BlockPos pos) {
        BlockState state = companion.level().getBlockState(pos);
        return SemanticService.resolve(state).has(SemanticTag.CROP)
                && CropShape.isMature(state);
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

    private void replant(Block crop, BlockPos pos) {
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
