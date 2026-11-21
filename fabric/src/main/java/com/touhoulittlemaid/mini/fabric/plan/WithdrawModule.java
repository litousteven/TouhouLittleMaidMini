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

/**
 * Takes items out of a container into the companion's backpack.
 *
 * <p>Ownership filtering ("only registered storage") belongs to the autonomy layer; the
 * command path can point at any reachable container the player names.
 */
public final class WithdrawModule implements ActionModule {

    public static final String ID = "withdraw_items";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Take matching items from a container.", List.of(
                ParamSpec.intRange("x", -30_000_000, 30_000_000, null, "container x"),
                ParamSpec.intRange("y", -64, 320, null, "container y"),
                ParamSpec.intRange("z", -30_000_000, 30_000_000, null, "container z"),
                ParamSpec.string("item", true, "item id or #tag to take"),
                ParamSpec.intRange("count", 1, 64, 1, "how many to take")));
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
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "withdraw needs a body");
        }
        BlockPos pos = pos(params);
        Container container = Containers.at(fabric, pos);
        if (container == null) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "there is no container at " + pos.toShortString());
        }
        String item = params.string("item", "");
        if (Containers.countMatching(container, item.startsWith("#"),
                item.startsWith("#") ? item.substring(1) : item) <= 0) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                    "the container holds no " + item);
        }
        if (fabric.freeSlots() <= 0) {
            return ModuleResult.failed(FailureCodes.INVENTORY_FULL,
                    "the backpack has no free slot");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        BlockPos pos = pos(params);
        String item = params.string("item", "");
        int count = params.integer("count", 1);
        return () -> {
            Container container = Containers.at(fabric, pos);
            if (container == null) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "the container is gone");
            }
            boolean tag = item.startsWith("#");
            ItemStack taken = Containers.extract(container, tag,
                    tag ? item.substring(1) : item, count);
            if (taken.isEmpty()) {
                return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                        "the container holds no " + item);
            }
            ItemStack remainder = companion.backpack().addItem(taken);
            companion.backpack().setChanged();
            if (!remainder.isEmpty()) {
                // Put the overflow back so nothing is duplicated or lost.
                Containers.insert(container, remainder);
                return ModuleResult.failed(FailureCodes.INVENTORY_FULL,
                        "the backpack filled up; " + remainder.getCount()
                                + " item(s) stayed in the container");
            }
            return ModuleResult.ok("withdrawn",
                    "took " + taken.getCount() + " " + item,
                    Map.of("count", taken.getCount()));
        };
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
