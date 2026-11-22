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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Picks up dropped items near a work station and puts them into the backpack.
 *
 * <p>docs/10 §6.2 lists {@code pickup_items} in the chores whitelist, and the
 * {@code tidy_station} opportunity has no meaning without it. One item entity per tick, so a
 * pile of drops cannot stall the server; a partially picked up stack stays on the ground with
 * its reduced count, which keeps items conserved even when the backpack fills up mid-step.
 */
public final class PickupModule implements ActionModule {

    public static final String ID = "pickup_items";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Pick up nearby dropped items into the backpack.", List.of(
                ParamSpec.intRange("radius", 1, 16, 8, "search radius"),
                ParamSpec.intRange("max", 1, 64, 16, "maximum item entities to collect")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.MODIFY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return Math.min(200, params.integer("max", 16) * 2);
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "pickup needs a body");
        }
        if (fabric.freeSlots() <= 0 && !hasStackableRoom(fabric, params)) {
            return ModuleResult.failed(FailureCodes.INVENTORY_FULL,
                    "the backpack has no free slot");
        }
        if (nearby(fabric.companion(), params).isEmpty()) {
            return ModuleResult.failed(FailureCodes.MISSING_ITEM, "no dropped items nearby");
        }
        return ModuleResult.ok(FailureCodes.OK, "ready");
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        List<ItemEntity> queue = new ArrayList<>(nearby(companion, params));
        return new ModuleExecution() {
            private int collected;

            @Override
            public ModuleResult tick() {
                if (queue.isEmpty()) {
                    return ModuleResult.ok("picked_up",
                            "picked up " + collected + " item(s)",
                            Map.of("count", collected));
                }
                ItemEntity entity = queue.remove(0);
                if (!entity.isAlive()) {
                    return ModuleResult.running();
                }
                ItemStack stack = entity.getItem();
                if (stack.isEmpty()) {
                    entity.discard();
                    return ModuleResult.running();
                }
                int before = stack.getCount();
                ItemStack remainder = companion.backpack().addItem(stack.copy());
                int moved = before - remainder.getCount();
                if (moved <= 0) {
                    return ModuleResult.failed(FailureCodes.INVENTORY_FULL,
                            "the backpack filled up; the remaining items stayed on the ground");
                }
                companion.backpack().setChanged();
                collected += moved;
                if (remainder.isEmpty()) {
                    entity.discard();
                } else {
                    entity.setItem(remainder);
                }
                return ModuleResult.running();
            }
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("count");
    }

    private static List<ItemEntity> nearby(CompanionEntity companion, ModuleParams params) {
        int radius = params.integer("radius", 8);
        int max = params.integer("max", 16);
        AABB box = companion.getBoundingBox().inflate(radius, 4.0D, radius);
        List<ItemEntity> items = new ArrayList<>();
        for (ItemEntity item : companion.level().getEntitiesOfClass(ItemEntity.class, box)) {
            if (item.isAlive() && !item.getItem().isEmpty()) {
                items.add(item);
                if (items.size() >= max) {
                    break;
                }
            }
        }
        return items;
    }

    /** A full backpack is still fine when the drops can stack into existing slots. */
    private static boolean hasStackableRoom(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        AABB box = fabric.companion().getBoundingBox().inflate(radius, 4.0D, radius);
        for (ItemEntity item : fabric.companion().level()
                .getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = item.getItem();
            for (int slot = 0; slot < fabric.companion().backpack().getContainerSize(); slot++) {
                ItemStack existing = fabric.companion().backpack().getItem(slot);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)
                        && existing.getCount() < existing.getMaxStackSize()) {
                    return true;
                }
            }
        }
        return false;
    }
}
