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
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Loads a furnace and waits for vanilla to smelt.
 *
 * <p>The furnace does the work: the module only moves input, fuel and output through the
 * container slots. That keeps cook time, fuel burn and recipe selection in vanilla instead
 * of re-implementing them. Waiting is tick-based, so the plan never blocks.
 */
public final class SmeltModule implements ActionModule {

    public static final String ID = "smelt_items";

    // AbstractFurnaceBlockEntity declares these protected; the indices are part of the
    // vanilla container layout.
    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Smelt items in a furnace and collect the output.", List.of(
                ParamSpec.intRange("x", -30_000_000, 30_000_000, null, "furnace x"),
                ParamSpec.intRange("y", -64, 320, null, "furnace y"),
                ParamSpec.intRange("z", -30_000_000, 30_000_000, null, "furnace z"),
                ParamSpec.string("input", true, "item id or #tag to smelt"),
                ParamSpec.string("fuel", true, "item id or #tag to burn"),
                ParamSpec.intRange("count", 1, 8, 1, "how many results to collect")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.MODIFY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return 200 * params.integer("count", 1);
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "smelt needs a body");
        }
        BlockEntity entity = fabric.level().getBlockEntity(pos(params));
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "there is no furnace at " + pos(params).toShortString());
        }
        String input = params.string("input", "");
        String fuel = params.string("fuel", "");
        if (furnace.getItem(SLOT_INPUT).isEmpty() && fabric.carriedCount(input) <= 0) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM, "no " + input + " to smelt");
        }
        if (furnace.getItem(SLOT_FUEL).isEmpty() && fabric.carriedCount(fuel) <= 0) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM, "no " + fuel + " to burn");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        BlockPos pos = pos(params);
        String input = params.string("input", "");
        String fuel = params.string("fuel", "");
        int wanted = params.integer("count", 1);
        int timeout = 400 * wanted;

        return new ModuleExecution() {
            private boolean loaded;
            private int collected;
            private int elapsed;

            @Override
            public ModuleResult tick() {
                elapsed++;
                BlockEntity blockEntity = fabric.level().getBlockEntity(pos);
                if (!(blockEntity instanceof AbstractFurnaceBlockEntity furnace)) {
                    return ModuleResult.failed(FailureCodes.BLOCKED, "the furnace is gone");
                }
                if (!loaded) {
                    loaded = true;
                    load(furnace, companion, input, fuel, wanted);
                }
                ItemStack result = furnace.getItem(SLOT_RESULT);
                if (!result.isEmpty()) {
                    ItemStack taken = result.copy();
                    ItemStack leftover = companion.backpack().addItem(taken);
                    if (leftover.isEmpty()) {
                        furnace.setItem(SLOT_RESULT, ItemStack.EMPTY);
                    } else {
                        furnace.setItem(SLOT_RESULT, leftover);
                    }
                    furnace.setChanged();
                    companion.backpack().setChanged();
                    collected += taken.getCount() - leftover.getCount();
                    if (collected >= wanted) {
                        return ModuleResult.ok("smelted", "collected " + collected + " item(s)",
                                Map.of("count", collected));
                    }
                }
                if (furnace.getItem(SLOT_INPUT).isEmpty()
                        && furnace.getItem(SLOT_RESULT).isEmpty() && collected < wanted) {
                    return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                            "the furnace ran out of " + input + " after " + collected);
                }
                if (elapsed > timeout) {
                    return ModuleResult.timedOut(FailureCodes.TIMEOUT,
                            "smelting did not finish within " + (timeout / 20) + " seconds");
                }
                return ModuleResult.running();
            }
        };
    }

    private static void load(AbstractFurnaceBlockEntity furnace, CompanionEntity companion,
            String input, String fuel, int wanted) {
        if (furnace.getItem(SLOT_INPUT).isEmpty()) {
            boolean tag = input.startsWith("#");
            ItemStack taken = Containers.extract(companion.backpack(), tag,
                    tag ? input.substring(1) : input, wanted);
            if (!taken.isEmpty()) {
                furnace.setItem(SLOT_INPUT, taken);
            }
        }
        if (furnace.getItem(SLOT_FUEL).isEmpty()) {
            boolean tag = fuel.startsWith("#");
            ItemStack taken = Containers.extract(companion.backpack(), tag,
                    tag ? fuel.substring(1) : fuel, Math.max(1, wanted));
            if (!taken.isEmpty()) {
                furnace.setItem(SLOT_FUEL, taken);
            }
        }
        furnace.setChanged();
        companion.backpack().setChanged();
    }

    @Override
    public List<String> outputs() {
        return List.of("count");
    }

    private static BlockPos pos(ModuleParams params) {
        return new BlockPos(params.integer("x", 0), params.integer("y", 0),
                params.integer("z", 0));
    }
}
