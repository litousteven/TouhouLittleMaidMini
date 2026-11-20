package com.touhoulittlemaid.mini.fabric.entity.goal;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;

/** Follow only while the local behaviour is {@code follow}. */
public final class BehaviorFollowOwnerGoal extends FollowOwnerGoal {

    private final CompanionEntity companion;

    public BehaviorFollowOwnerGoal(CompanionEntity companion, double speedModifier,
            float startDistance, float stopDistance) {
        super(companion, speedModifier, startDistance, stopDistance);
        this.companion = companion;
    }

    @Override
    public boolean canUse() {
        return companion.behavior() == LocalBehavior.FOLLOW && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return companion.behavior() == LocalBehavior.FOLLOW && super.canContinueToUse();
    }
}
