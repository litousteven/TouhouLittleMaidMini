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

/** Puts carried items into a container, merging same items first. */
public final class DepositModule implements ActionModule {

    public static final String ID = "deposit_items";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Put carried items into a container.", List.of(
                ParamSpec.intRange("x", -30_000_000, 30_000_000, null, "container x"),
                ParamSpec.intRange("y", -64, 320, null, "container y"),
                ParamSpec.intRange("z", -30_000_000, 30_000_000, null, "container z"),
                ParamSpec.string("item", true, "item id or #tag to deposit"),
                ParamSpec.intRange("count", 1, 64, 64, "at most this many")));
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
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "deposit needs a body");
        }
        BlockPos pos = pos(params);
        if (Containers.at(fabric, pos) == null) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "there is no container at " + pos.toShortString());
        }
        String item = params.string("item", "");
        if (fabric.carriedCount(item) <= 0) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM, "nothing to deposit: " + item);
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        BlockPos pos = pos(params);
        String item = params.string("item", "");
        int limit = params.integer("count", 64);
        return () -> {
            Container container = Containers.at(fabric, pos);
            if (container == null) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "the container is gone");
            }
            boolean tag = item.startsWith("#");
            String id = tag ? item.substring(1) : item;
            ItemStack extracted = Containers.extract(companion.backpack(), tag, id, limit);
            if (extracted.isEmpty()) {
                return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                        "nothing to deposit: " + item);
            }
            int leftover = Containers.insert(container, extracted);
            if (leftover > 0) {
                ItemStack back = extracted.copyWithCount(leftover);
                ItemStack rest = companion.backpack().addItem(back);
                companion.backpack().setChanged();
                int deposited = extracted.getCount() - leftover;
                if (deposited == 0) {
                    return ModuleResult.failed(FailureCodes.INVENTORY_FULL,
                            "the container is full");
                }
                return ModuleResult.failed(FailureCodes.INVENTORY_FULL,
                        "deposited " + deposited + ", the container is full");
            }
            return ModuleResult.ok("deposited", "deposited " + extracted.getCount() + " " + item,
                    Map.of("count", extracted.getCount()));
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
