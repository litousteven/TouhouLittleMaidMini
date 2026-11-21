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

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Says one short line to the owner.
 *
 * <p>This is the smallest module that proves the pipeline end to end: it observes, costs
 * one tick, and its only side effect is a literal chat line. Text is never interpreted.
 */
public final class SayModule implements ActionModule {

    public static final String ID = "say";
    private static final int MAX_LENGTH = 200;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Say one short line in the owner's chat.", List.of(
                ParamSpec.string("text", true, "line to say, at most 200 characters")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.OBSERVE;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return 1;
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        String text = params.string("text", "");
        if (text.isBlank()) {
            return ModuleResult.failed(FailureCodes.INVALID_PARAMS,
                    "say needs a non-empty text");
        }
        if (text.length() > MAX_LENGTH) {
            return ModuleResult.failed(FailureCodes.INVALID_PARAMS,
                    "say text is longer than " + MAX_LENGTH + " characters");
        }
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED,
                    "say needs a companion context");
        }
        if (!fabric.companion().isAlive()) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "companion is gone");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        String text = params.string("text", "");
        return new ModuleExecution() {
            private boolean done;

            @Override
            public ModuleResult tick() {
                if (done) {
                    return ModuleResult.ok("said", text, Map.of("said", text));
                }
                done = true;
                CompanionEntity companion = fabric.companion();
                if (companion.getOwner() instanceof ServerPlayer owner) {
                    owner.sendSystemMessage(Component.literal(
                            "<" + companion.companionName() + "> " + text));
                }
                return ModuleResult.ok("said", text, Map.of("said", text));
            }
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("said");
    }
}
