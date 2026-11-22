package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.ModuleRegistry;

/**
 * The module catalogue available to plans.
 *
 * <p>One place to see everything a plan can do. A new module is added here after it has a
 * spec, failure codes and tests — never by registering one more task per gameplay idea.
 */
public final class FabricModules {

    private FabricModules() {
    }

    public static ModuleRegistry registry() {
        return ModuleRegistry.builder()
                .register(new SayModule())
                .register(new GotoModule())
                // M6.1 body abilities.
                .register(new DigModule())
                .register(new PlaceModule())
                .register(new WithdrawModule())
                .register(new DepositModule())
                .register(new MoveSlotModule())
                .register(new CraftModule())
                .register(new SmeltModule())
                // M6.2 area and storage modules.
                .register(new FarmAreaModules.Harvest())
                .register(new FarmAreaModules.Plant())
                .register(new FarmAreaModules.Till())
                .register(GatherModule.mineMaterial())
                .register(GatherModule.chopTree())
                .register(new TidyModule())
                // M6.5: the chores whitelist needs a way to clear drops at work stations.
                .register(new PickupModule())
                .build();
    }
}
