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

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks one block, honouring tool tier, durability and protected tags.
 *
 * <p>Progress is accumulated per tick using the vanilla formula, so a stone block takes
 * about as long as it would for a player with the same tool. Drops are computed with the
 * held tool, which is what makes ore drops correct instead of empty.
 */
public final class DigModule implements ActionModule {

    public static final String ID = "dig_block";
    private static final int ESTIMATED_TICKS = 80;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleSpec spec() {
        return new ModuleSpec(ID, "Break one block with the held tool.", List.of(
                ParamSpec.intRange("x", -30_000_000, 30_000_000, null, "block x"),
                ParamSpec.intRange("y", -64, 320, null, "block y"),
                ParamSpec.intRange("z", -30_000_000, 30_000_000, null, "block z")));
    }

    @Override
    public RiskClass risk() {
        return RiskClass.DESTROY;
    }

    @Override
    public int estimatedTicks(ModuleParams params) {
        return ESTIMATED_TICKS;
    }

    @Override
    public int destroyedBlocks(ModuleParams params) {
        return 1;
    }

    @Override
    public ModuleResult check(ModuleContext context, ModuleParams params) {
        if (!(context instanceof FabricModuleContext fabric)) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "dig needs a body");
        }
        CompanionEntity companion = fabric.companion();
        if (!companion.isAlive()) {
            return ModuleResult.failed(FailureCodes.INTERRUPTED, "companion is gone");
        }
        BlockPos pos = pos(params);
        if (!fabric.level().isLoaded(pos)) {
            return ModuleResult.failed(FailureCodes.UNREACHABLE, "block chunk is not loaded");
        }
        BlockState state = fabric.level().getBlockState(pos);
        if (state.isAir()) {
            return ModuleResult.failed(FailureCodes.NOT_HARVESTABLE, "there is no block here");
        }
        if (state.is(protectedTag())) {
            return ModuleResult.failed(FailureCodes.PROTECTED,
                    "this block carries #tlmm:protected");
        }
        if (state.getDestroySpeed(fabric.level(), pos) < 0.0F) {
            return ModuleResult.failed(FailureCodes.BLOCKED, "this block cannot be broken");
        }
        Tools.Verdict verdict = Tools.checkFor(companion, state);
        return verdict.ok()
                ? ModuleResult.ok(FailureCodes.OK, "ready")
                : ModuleResult.failed(verdict.code(), verdict.message());
    }

    @Override
    public ModuleExecution start(ModuleContext context, ModuleParams params) {
        FabricModuleContext fabric = (FabricModuleContext) context;
        CompanionEntity companion = fabric.companion();
        BlockPos pos = pos(params);
        ItemStack startingTool = companion.getMainHandItem().copy();
        BlockState state = fabric.level().getBlockState(pos);
        int required = Tools.ticksToBreak(state, startingTool, fabric.level(), pos);

        return new ModuleExecution() {
            private int elapsed;
            private int reported;

            @Override
            public ModuleResult tick() {
                ItemStack held = companion.getMainHandItem();
                if (startingTool.isEmpty() != held.isEmpty()
                        || (!startingTool.isEmpty()
                                && !ItemStack.isSameItemSameComponents(startingTool, held))) {
                    return ModuleResult.failed(FailureCodes.TOOL_LOST,
                            "the tool in hand changed while digging");
                }
                BlockState current = fabric.level().getBlockState(pos);
                if (current.isAir()) {
                    return ModuleResult.failed(FailureCodes.NOT_HARVESTABLE,
                            "the block disappeared before it broke");
                }
                elapsed++;
                if (elapsed % 10 == 0) {
                    reported = Math.min(99, elapsed * 100 / Math.max(1, required));
                }
                if (elapsed < required) {
                    return ModuleResult.running();
                }

                List<ItemStack> drops = new ArrayList<>(Block.getDrops(current,
                        fabric.level(), pos, fabric.level().getBlockEntity(pos), companion,
                        held));
                for (ItemStack drop : drops) {
                    Block.popResource(fabric.level(), pos, drop);
                }
                fabric.level().removeBlock(pos, false);
                if (held.isDamageableItem()) {
                    held.hurtAndBreak(1, companion, EquipmentSlot.MAINHAND);
                }
                List<String> ids = drops.stream()
                        .map(stack -> stack.getItem().toString() + " x" + stack.getCount())
                        .toList();
                return ModuleResult.ok("broken",
                        "broke the block, dropped " + ids,
                        Map.of("drops", ids, "count", drops.size()));
            }

            @Override
            public void cancel() {
                // Progress is simply abandoned; no world change happened yet.
            }
        };
    }

    @Override
    public List<String> outputs() {
        return List.of("drops", "count");
    }

    private static BlockPos pos(ModuleParams params) {
        return new BlockPos(params.integer("x", 0), params.integer("y", 0),
                params.integer("z", 0));
    }

    private static TagKey<Block> protectedTag() {
        return TagKey.create(Registries.BLOCK, Identifier.parse("tlmm:protected"));
    }
}
