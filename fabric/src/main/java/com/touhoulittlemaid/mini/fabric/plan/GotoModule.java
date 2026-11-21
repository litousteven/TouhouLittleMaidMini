package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.ActionModule;
import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ModuleContext;
import com.touhoulittlemaid.mini.core.plan.ModuleExecution;
import com.touhoulittlemaid.mini.core.plan.ModuleParams;
import com.touhoulittlemaid.mini.core.plan.ModuleResult;
import com.touhoulittlemaid.mini.core.plan.ModuleSpec;
import com.touhoulittlemaid.mini.core.plan.ParamSpec;
import com.touhoulittlemaid.mini.core.plan.RiskClass;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;

/**
 * Walks to a block position and reports arrival.
 *
 * <p>Navigation is inherently multi-tick, which is exactly why the module reports
 * {@code RUNNING} instead of blocking: the plan advances only when the body gets there.
 */
public final class GotoModule implements ActionModule {

    public static final String ID = "goto";
    private static final int MAX_REPATH = 2;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Walk to a block position and stop within range.", List.of(
                ParamSpec.intRange("x", -30_000_000, 30_000_000, null, "target block x"),
                ParamSpec.intRange("y", -64, 320, null, "target block y"),
                ParamSpec.intRange("z", -30_000_000, 30_000_000, null, "target block z"),
                ParamSpec.number("range", 0.0D, 8.0D, 1.5D, "stop distance in blocks"),
                ParamSpec.intRange("timeout_seconds", 1, 60, 20,
                        "give up after this many seconds")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.MOVE;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return params.integer("timeout_seconds", 20) * 20;
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED,
                    "goto needs a companion context");
        }
        CompanionEntity companion = fabric.companion();
        if (!companion.isAlive()) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "companion is gone");
        }
        if (companion.isSafetyStopped()) {
            return ModuleResult.failed(FailureCodes.UNSAFE_AREA,
                    "health is too low to walk");
        }
        BlockPos target = target(params);
        if (!companion.level().isLoaded(target)) {
            return ModuleResult.failed(FailureCodes.UNREACHABLE,
                    "target chunk is not loaded");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        BlockPos target = target(params);
        double range = params.number("range", 1.5D);
        int timeoutTicks = Math.max(20, params.integer("timeout_seconds", 20) * 20);
        double speed = 1.0D;

        return new ModuleExecution() {
            private int elapsed;
            private int repaths;

            @Override
            public ModuleResult tick() {
                elapsed++;
                double distance = Math.sqrt(companion.distanceToSqr(
                        target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D));
                if (distance <= range) {
                    companion.getNavigation().stop();
                    return ModuleResult.ok("arrived", "arrived within " + range + " blocks",
                            Map.of("x", target.getX(), "y", target.getY(), "z", target.getZ()));
                }
                if (elapsed > timeoutTicks) {
                    companion.getNavigation().stop();
                    return ModuleResult.timedOut(FailureCodes.UNREACHABLE,
                            "did not reach " + target.toShortString() + " within "
                                    + (timeoutTicks / 20) + " seconds");
                }
                if (elapsed == 1) {
                    companion.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                            target.getZ() + 0.5D, speed);
                } else if (companion.getNavigation().isDone()) {
                    if (repaths >= MAX_REPATH) {
                        return ModuleResult.failed(FailureCodes.UNREACHABLE,
                                "no path to " + target.toShortString());
                    }
                    repaths++;
                    companion.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                            target.getZ() + 0.5D, speed);
                }
                return ModuleResult.running();
            }

            @Override
            public void cancel() {
                companion.getNavigation().stop();
            }
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("x", "y", "z");
    }

    private static BlockPos target(ModuleParams params) {
        return new BlockPos(params.integer("x", 0), params.integer("y", 0),
                params.integer("z", 0));
    }
}
