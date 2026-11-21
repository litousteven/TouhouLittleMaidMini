package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.PlanRunner;
import com.touhoulittlemaid.mini.core.plan.PredicateContext;

import java.util.List;
import java.util.Map;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * Answers the whitelisted predicates from live world state.
 *
 * <p>Step and binding answers come from the runner; world answers come from the companion.
 * Nothing here is cached between ticks, so a plan cannot act on stale "the crop is mature"
 * information.
 */
public final class FabricPredicates implements PredicateContext {

    private final FabricModuleContext context;
    private PlanRunner runner;

    public FabricPredicates(FabricModuleContext context) {
        this.context = context;
    }

    /** Wired after construction because the runner needs these predicates itself. */
    public void attach(PlanRunner runner) {
        this.runner = runner;
    }

    @Override
    public boolean stepOk(String stepId) {
        return runner != null && runner.stepOk(stepId);
    }

    @Override
    public boolean stepFailed(String stepId) {
        return runner != null && runner.stepFailed(stepId);
    }

    @Override
    public boolean boundEmpty(String name) {
        return boundCount(name) == 0;
    }

    @Override
    public int boundCount(String name) {
        if (runner == null) {
            return 0;
        }
        Map<String, Object> bound = runner.bindings().get(name);
        if (bound == null || bound.isEmpty()) {
            return 0;
        }
        Object count = bound.get("count");
        if (count instanceof Number number) {
            return number.intValue();
        }
        if (bound.size() == 1) {
            Object only = bound.values().iterator().next();
            if (only instanceof Number number) {
                return number.intValue();
            }
            if (only instanceof List<?> list) {
                return list.size();
            }
        }
        return bound.size();
    }

    @Override
    public int inventoryCount(String item) {
        return context.carriedCount(item);
    }

    @Override
    public int inventorySpace() {
        return context.freeSlots();
    }

    @Override
    public int hostileCount(int radius) {
        var companion = context.companion();
        AABB box = companion.getBoundingBox().inflate(radius);
        List<Monster> hostiles = context.level().getEntitiesOfClass(Monster.class, box,
                monster -> monster.isAlive() && !monster.isRemoved());
        return hostiles.size();
    }

    @Override
    public double ownerDistance() {
        var companion = context.companion();
        LivingEntity owner = companion.getOwner();
        return owner == null ? Double.MAX_VALUE : companion.distanceTo(owner);
    }

    @Override
    public boolean isDay() {
        // Minecraft 26.3 exposes a world clock rather than Level#isDay; the overworld day
        // runs from tick 0 to 12000 of a 24000 tick cycle.
        long time = context.level().getOverworldClockTime() % 24000L;
        return time >= 0 && time < 12000L;
    }

    @Override
    public boolean isRain() {
        return context.level().isRaining();
    }
}
