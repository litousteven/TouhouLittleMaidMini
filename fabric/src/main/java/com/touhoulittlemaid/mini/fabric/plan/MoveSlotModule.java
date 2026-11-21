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
 * Moves items between two exact slots, atomically.
 *
 * <p>Slot-level transfer is the primitive {@code tidy_chests} needs: it can address a
 * chest slot without opening a GUI, and it refuses rather than overwriting when the target
 * holds something else. Merging uses item plus components, so a renamed sword never merges
 * with a plain one.
 */
public final class MoveSlotModule implements ActionModule {

    public static final String ID = "move_slot";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Move items between two slots; omit x/y/z for the backpack.",
                List.of(
                        ParamSpec.optionalInt("from_x", -30_000_000, 30_000_000, "source chest x"),
                        ParamSpec.optionalInt("from_y", -64, 320, "source chest y"),
                        ParamSpec.optionalInt("from_z", -30_000_000, 30_000_000, "source chest z"),
                        ParamSpec.intRange("from_slot", 0, 255, null, "source slot"),
                        ParamSpec.optionalInt("to_x", -30_000_000, 30_000_000, "target chest x"),
                        ParamSpec.optionalInt("to_y", -64, 320, "target chest y"),
                        ParamSpec.optionalInt("to_z", -30_000_000, 30_000_000, "target chest z"),
                        ParamSpec.intRange("to_slot", 0, 255, null, "target slot"),
                        ParamSpec.intRange("count", 1, 64, 1, "how many to move")));
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
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "move_slot needs a body");
        }
        Container from = source(fabric, params);
        Container to = target(fabric, params);
        if (from == null || to == null) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "a container position does not hold a container");
        }
        int fromSlot = params.integer("from_slot", -1);
        int toSlot = params.integer("to_slot", -1);
        if (!validSlot(from, fromSlot) || !validSlot(to, toSlot)) {
            return ModuleResult.failed(FailureCodes.INVALID_PARAMS, "slot index is out of range");
        }
        if (from == to && fromSlot == toSlot) {
            return ModuleResult.failed(FailureCodes.INVALID_PARAMS,
                    "source and target are the same slot");
        }
        if (from.getItem(fromSlot).isEmpty()) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM, "the source slot is empty");
        }
        ItemStack destination = to.getItem(toSlot);
        if (!destination.isEmpty() && !ItemStack.isSameItemSameComponents(destination,
                from.getItem(fromSlot))) {
            return ModuleResult.failed(FailureCodes.BLOCKED,
                    "the target slot already holds a different item");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        return () -> {
            Container from = source(fabric, params);
            Container to = target(fabric, params);
            if (from == null || to == null) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "a container is gone");
            }
            int fromSlot = params.integer("from_slot", -1);
            int toSlot = params.integer("to_slot", -1);
            int wanted = params.integer("count", 1);
            ItemStack source = from.getItem(fromSlot);
            if (source.isEmpty()) {
                return ModuleResult.failed(FailureCodes.MISSING_ITEM, "the source slot is empty");
            }
            ItemStack destination = to.getItem(toSlot);
            int space = destination.isEmpty()
                    ? Math.min(64, source.getMaxStackSize())
                    : Math.min(64, destination.getMaxStackSize()) - destination.getCount();
            if (space <= 0) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "the target slot is full");
            }
            int moved = Math.min(Math.min(wanted, source.getCount()), space);
            if (moved <= 0) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "nothing could be moved");
            }
            if (destination.isEmpty()) {
                to.setItem(toSlot, source.copyWithCount(moved));
            } else {
                destination.grow(moved);
                to.setItem(toSlot, destination);
            }
            source.shrink(moved);
            from.setItem(fromSlot, source.isEmpty() ? ItemStack.EMPTY : source);
            from.setChanged();
            to.setChanged();
            companion.backpack().setChanged();
            return ModuleResult.ok("moved", "moved " + moved + " item(s)",
                    Map.of("count", moved));
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("count");
    }

    private static Container source(FabricModuleContext fabric, ModuleParams params) {
        if (!params.has("from_x")) {
            return fabric.companion().backpack();
        }
        return Containers.at(fabric, new BlockPos(params.integer("from_x", 0),
                params.integer("from_y", 0), params.integer("from_z", 0)));
    }

    private static Container target(FabricModuleContext fabric, ModuleParams params) {
        if (!params.has("to_x")) {
            return fabric.companion().backpack();
        }
        return Containers.at(fabric, new BlockPos(params.integer("to_x", 0),
                params.integer("to_y", 0), params.integer("to_z", 0)));
    }

    private static boolean validSlot(Container container, int slot) {
        return slot >= 0 && slot < container.getContainerSize();
    }
}
