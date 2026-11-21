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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places one block from the companion's own inventory.
 *
 * <p>Survival is checked before the block is set, so an invalid spot costs nothing and
 * never consumes the item. Placement is intentionally one block per step: bulk building is
 * a plan of many steps and stays visible in the budget.
 */
public final class PlaceModule implements ActionModule {

    public static final String ID = "place_block";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Place one carried block at a position.", List.of(
                ParamSpec.intRange("x", -30_000_000, 30_000_000, null, "block x"),
                ParamSpec.intRange("y", -64, 320, null, "block y"),
                ParamSpec.intRange("z", -30_000_000, 30_000_000, null, "block z"),
                ParamSpec.string("item", true, "block item id to place")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.MODIFY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return 2;
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "place needs a body");
        }
        CompanionEntity companion = fabric.companion();
        String item = params.string("item", "");
        int slot = findSlot(companion, item);
        if (slot < 0) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                    "no " + item + " in the backpack");
        }
        ItemStack stack = companion.backpack().getItem(slot);
        if (!(stack.getItem() instanceof BlockItem)) {
            return ModuleResult.failed(FailureCodes.INVALID_PARAMS,
                    item + " is not a block item");
        }
        BlockPos pos = pos(params);
        if (!fabric.level().isLoaded(pos)) {
            return ModuleResult.failed(FailureCodes.UNREACHABLE, "target chunk is not loaded");
        }
        BlockState existing = fabric.level().getBlockState(pos);
        if (!existing.isAir() && !existing.canBeReplaced()) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "the target position is already occupied");
        }
        BlockState toPlace = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        if (!toPlace.canSurvive(fabric.level(), pos)) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "the block cannot survive at that position");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        String item = params.string("item", "");
        BlockPos pos = pos(params);
        int slot = findSlot(companion, item);
        if (slot < 0) {
            return () -> ModuleResult.failed(FailureCodes.MISSING_ITEM,
                    "the item disappeared before placing");
        }
        ItemStack stack = companion.backpack().getItem(slot);
        BlockState toPlace = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
        return () -> {
            if (!fabric.level().setBlock(pos, toPlace, 3)) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "the world refused the block");
            }
            ItemStack updated = companion.backpack().getItem(slot);
            updated.shrink(1);
            companion.backpack().setItem(slot,
                    updated.isEmpty() ? ItemStack.EMPTY : updated);
            companion.backpack().setChanged();
            return ModuleResult.ok("placed", "placed " + item + " at "
                    + pos.toShortString(), Map.of("x", pos.getX(), "y", pos.getY(),
                    "z", pos.getZ()));
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("x", "y", "z");
    }

    private static int findSlot(CompanionEntity companion, String item) {
        boolean tag = item.startsWith("#");
        String id = tag ? item.substring(1) : item;
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem
                    && ItemMatch.matches(stack, tag, id)) {
                return slot;
            }
        }
        return -1;
    }

    private static BlockPos pos(ModuleParams params) {
        return new BlockPos(params.integer("x", 0), params.integer("y", 0),
                params.integer("z", 0));
    }
}
