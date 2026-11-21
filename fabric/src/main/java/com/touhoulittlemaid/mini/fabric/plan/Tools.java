package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tool authorization, read from the inventory every time.
 *
 * <p>Nothing is cached: the player can take the pickaxe out between two steps and the next
 * step must notice. A missing tool and a too-weak tool are different codes on purpose —
 * the first asks the player to hand something over, the second asks for a better one.
 */
final class Tools {

    static final int MIN_DURABILITY = 10;

    private Tools() {
    }

    record Verdict(boolean ok, String code, String message) {

        static Verdict pass() {
            return new Verdict(true, FailureCodes.OK, "");
        }

        static Verdict refuse(String code, String message) {
            return new Verdict(false, code, message);
        }
    }

    static Verdict checkFor(CompanionEntity companion, BlockState state) {
        ItemStack held = companion.getMainHandItem();
        if (state.requiresCorrectToolForDrops() && !held.isCorrectToolForDrops(state)) {
            boolean carriesAnyTool = carriesAnyTool(companion);
            if (!carriesAnyTool) {
                return Verdict.refuse(FailureCodes.TOOL_NOT_IN_INVENTORY,
                        "no tool in the backpack can harvest this block; put one in and retry");
            }
            return Verdict.refuse(FailureCodes.WRONG_TOOL,
                    "holding " + name(held) + ", which cannot harvest this block; "
                            + "a stronger tool is required");
        }
        if (held.isDamageableItem()) {
            int left = held.getMaxDamage() - held.getDamageValue();
            if (left < MIN_DURABILITY) {
                return Verdict.refuse(FailureCodes.TOOL_LOST,
                        name(held) + " has only " + left + " durability left");
            }
        }
        return Verdict.pass();
    }

    static boolean carriesAnyTool(CompanionEntity companion) {
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            if (companion.backpack().getItem(slot).isDamageableItem()) {
                return true;
            }
        }
        for (int slot = 0; slot < companion.equipment().getContainerSize(); slot++) {
            if (companion.equipment().getItem(slot).isDamageableItem()) {
                return true;
            }
        }
        return companion.getMainHandItem().isDamageableItem()
                || companion.getOffhandItem().isDamageableItem();
    }

    /** Vanilla destroy progress per tick, without a Player instance. */
    static int ticksToBreak(BlockState state, ItemStack tool, net.minecraft.world.level.BlockGetter level,
            net.minecraft.core.BlockPos pos) {
        float hardness = Math.max(0.0F, state.getDestroySpeed(level, pos));
        float speed = Math.max(1.0F, tool.getDestroySpeed(state));
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        float damagePerTick = correct
                ? speed / hardness / 30.0F
                : speed / hardness / 100.0F;
        if (damagePerTick <= 0.0F) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil(1.0F / damagePerTick));
    }

    static String name(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty hand";
        }
        return stack.getItem().toString();
    }
}
