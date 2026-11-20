package com.touhoulittlemaid.mini.fabric.entity.goal;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Returns a non-following companion to its home when it drifts outside the work radius.
 *
 * <p>{@code stay} companions sit, so this mainly serves {@code guard}, which keeps
 * fighting nearby but must not chase targets across the world.
 */
public final class ReturnHomeGoal extends Goal {

    private final CompanionEntity companion;

    public ReturnHomeGoal(CompanionEntity companion) {
        this.companion = companion;
        setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (companion.behavior() == LocalBehavior.FOLLOW || companion.isOrderedToSit()) {
            return false;
        }
        BlockPos home = companion.homeBlockPos().orElse(null);
        if (home == null || !companion.level().dimension().equals(
                companion.homeDimension().orElse(null))) {
            return false;
        }
        return companion.distanceToSqr(Vec3.atCenterOf(home))
                > (double) companion.workRadius() * companion.workRadius();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        BlockPos home = companion.homeBlockPos().orElse(null);
        if (home == null) {
            return;
        }
        if (companion.getNavigation().isDone()) {
            companion.getNavigation().moveTo(home.getX() + 0.5D, home.getY(),
                    home.getZ() + 0.5D, 0.9D);
        }
    }
}
