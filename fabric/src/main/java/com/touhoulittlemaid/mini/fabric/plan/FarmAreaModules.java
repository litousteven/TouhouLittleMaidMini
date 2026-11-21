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
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.semantic.CropShape;
import com.touhoulittlemaid.mini.fabric.semantic.SemanticService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Crop work that loops internally over an area.
 *
 * <p>The loop is inside the module because "walk the field" is not a plan-level concept;
 * each tick handles one block, so a large field costs many cheap ticks rather than one
 * long one.
 */
final class FarmAreaModules {

    private FarmAreaModules() {
    }

    /** Harvests mature crops and optionally replants from carried seeds. */
    static final class Harvest implements ActionModule {

        static final String ID = "harvest_area";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public ModuleSpec spec() {
            return new ModuleSpec(ID, "Harvest mature crops in an area and replant them.",
                    List.of(
                            ParamSpec.intRange("radius", 1, 32, 8, "search radius"),
                            ParamSpec.bool("replant", true, "replant from carried seeds"),
                            ParamSpec.intRange("max", 1, 32, 16, "stop after this many crops")));
        }

        @Override
        public RiskClass risk() {
            return RiskClass.MODIFY;
        }

        @Override
        public int estimatedTicks(ModuleParams params) {
            return 4 * params.integer("max", 16);
        }

        @Override
        public ModuleResult check(ModuleContext context, ModuleParams params) {
            FabricModuleContext fabric = require(context);
            List<BlockPos> crops = findMature(fabric, params);
            if (crops.isEmpty()) {
                String unknown = firstUnknownCrop(fabric, params);
                if (unknown != null) {
                    com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService.noteUnknown(
                            unknown, com.touhoulittlemaid.mini.core.semantic.SemanticKind.BLOCK);
                    return ModuleResult.failed(FailureCodes.UNKNOWN_CATEGORY,
                            unknown + " has an age property but no known category; "
                                    + "add it to the knowledge layer");
                }
                return ModuleResult.failed(FailureCodes.NOT_HARVESTABLE,
                        "no mature crop within " + params.integer("radius", 8) + " blocks");
            }
            return ModuleResult.ok(FailureCodes.OK, "ready");
        }

        @Override
        public ModuleExecution start(ModuleContext context, ModuleParams params) {
            FabricModuleContext fabric = require(context);
            CompanionEntity companion = fabric.companion();
            boolean replant = params.bool("replant", true);
            Deque<BlockPos> queue = new ArrayDeque<>(findMature(fabric, params));
            return new ModuleExecution() {
                private int harvested;
                private int replanted;

                @Override
                public ModuleResult tick() {
                    BlockPos pos = queue.pollFirst();
                    if (pos != null) {
                        BlockState state = fabric.level().getBlockState(pos);
                        if (SemanticService.resolve(state).has(SemanticTag.CROP)
                                && CropShape.isMature(state)) {
                            if (fabric.level().destroyBlock(pos, true, companion)) {
                                harvested++;
                                if (replant && plantSeed(companion, pos)) {
                                    replanted++;
                                }
                            }
                        }
                        return ModuleResult.running();
                    }
                    return ModuleResult.ok("harvested",
                            "harvested " + harvested + ", replanted " + replanted,
                            Map.of("harvested", harvested, "replanted", replanted));
                }
            };
        }

        @Override
        public List<String> outputs() {
            return List.of("harvested", "replanted");
        }
    }

    /** Plants seeds on existing farmland; no hoe needed. */
    static final class Plant implements ActionModule {

        static final String ID = "plant_area";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public ModuleSpec spec() {
            return new ModuleSpec(ID, "Plant a carried seed on existing farmland.", List.of(
                    ParamSpec.intRange("radius", 1, 32, 8, "search radius"),
                    ParamSpec.string("seed", true, "seed item id to plant"),
                    ParamSpec.intRange("max", 1, 32, 16, "stop after this many plants")));
        }

        @Override
        public RiskClass risk() {
            return RiskClass.MODIFY;
        }

        @Override
        public int estimatedTicks(ModuleParams params) {
            return 2 * params.integer("max", 16);
        }

        @Override
        public ModuleResult check(ModuleContext context, ModuleParams params) {
            FabricModuleContext fabric = require(context);
            String seed = params.string("seed", "");
            if (seedStack(fabric.companion(), seed).isEmpty()) {
                return ModuleResult.failed(FailureCodes.MISSING_ITEM, "no " + seed + " carried");
            }
            if (findFarmland(fabric, params).isEmpty()) {
                return ModuleResult.failed(FailureCodes.BLOCKED, "no empty farmland nearby");
            }
            return ModuleResult.ok(FailureCodes.OK, "ready");
        }

        @Override
        public ModuleExecution start(ModuleContext context, ModuleParams params) {
            FabricModuleContext fabric = require(context);
            CompanionEntity companion = fabric.companion();
            String seed = params.string("seed", "");
            Deque<BlockPos> queue = new ArrayDeque<>(findFarmland(fabric, params));
            return new ModuleExecution() {
                private int planted;

                @Override
                public ModuleResult tick() {
                    BlockPos pos = queue.pollFirst();
                    if (pos != null) {
                        if (seedStack(companion, seed).isEmpty()) {
                            return ModuleResult.failed(FailureCodes.MISSING_ITEM,
                                    "ran out of " + seed);
                        }
                        if (plantSeed(companion, pos)) {
                            planted++;
                        }
                        return ModuleResult.running();
                    }
                    return ModuleResult.ok("planted", "planted " + planted,
                            Map.of("planted", planted));
                }
            };
        }

        @Override
        public List<String> outputs() {
            return List.of("planted");
        }
    }

    /** Turns dirt or grass into farmland with a hoe, then optionally plants. */
    static final class Till implements ActionModule {

        static final String ID = "till_area";

        @Override
        public String id() {
            return ID;
        }

        @Override
        public ModuleSpec spec() {
            return new ModuleSpec(ID, "Till dirt or grass into farmland with a hoe.", List.of(
                    ParamSpec.intRange("radius", 1, 32, 8, "search radius"),
                    ParamSpec.string("seed", "", "optional seed to plant after tilling"),
                    ParamSpec.intRange("max", 1, 32, 9, "stop after this many blocks")));
        }

        @Override
        public RiskClass risk() {
            return RiskClass.MODIFY;
        }

        @Override
        public int estimatedTicks(ModuleParams params) {
            return 2 * params.integer("max", 9);
        }

        @Override
        public ModuleResult check(ModuleContext context, ModuleParams params) {
            FabricModuleContext fabric = require(context);
            if (hoeSlot(fabric.companion()) < 0) {
                return ModuleResult.failed(FailureCodes.TOOL_NOT_IN_INVENTORY,
                        "tilling needs a hoe in the backpack");
            }
            if (findTillable(fabric, params).isEmpty()) {
                return ModuleResult.failed(FailureCodes.BLOCKED,
                        "no dirt or grass to till nearby");
            }
            return ModuleResult.ok(FailureCodes.OK, "ready");
        }

        @Override
        public ModuleExecution start(ModuleContext context, ModuleParams params) {
            FabricModuleContext fabric = require(context);
            CompanionEntity companion = fabric.companion();
            String seed = params.string("seed", "");
            Deque<BlockPos> queue = new ArrayDeque<>(findTillable(fabric, params));
            return new ModuleExecution() {
                private int tilled;
                private int planted;

                @Override
                public ModuleResult tick() {
                    BlockPos pos = queue.pollFirst();
                    if (pos == null) {
                        return ModuleResult.ok("tilled", "tilled " + tilled + ", planted "
                                + planted, Map.of("tilled", tilled, "planted", planted));
                    }
                    int hoe = hoeSlot(companion);
                    if (hoe < 0) {
                        return ModuleResult.failed(FailureCodes.TOOL_LOST, "the hoe is gone");
                    }
                    BlockState above = fabric.level().getBlockState(pos.above());
                    if (!above.isAir() && !above.canBeReplaced()) {
                        return ModuleResult.running();
                    }
                    fabric.level().setBlockAndUpdate(pos, Blocks.FARMLAND.defaultBlockState());
                    tilled++;
                    ItemStack tool = companion.backpack().getItem(hoe);
                    tool.hurtAndBreak(1, companion, EquipmentSlot.MAINHAND);
                    companion.backpack().setItem(hoe,
                            tool.isEmpty() ? ItemStack.EMPTY : tool);
                    if (!seed.isEmpty() && plantSeed(companion, pos.above())) {
                        planted++;
                    }
                    return ModuleResult.running();
                }
            };
        }

        @Override
        public List<String> outputs() {
            return List.of("tilled", "planted");
        }
    }

    // --- shared helpers -------------------------------------------------

    private static FabricModuleContext require(ModuleContext context) {
        if (!(context instanceof FabricModuleContext fabric)) {
            throw new IllegalStateException("farm modules need a body");
        }
        return fabric;
    }

    /** Returns the first age-bearing block the resolver cannot classify, if any. */
    private static String firstUnknownCrop(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        BlockPos center = fabric.companion().blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!fabric.level().isLoaded(pos)) {
                continue;
            }
            BlockState state = fabric.level().getBlockState(pos);
            if (CropShape.looksLikeCrop(state)
                    && !SemanticService.resolve(state).has(SemanticTag.CROP)) {
                return SemanticService.resolve(state).id();
            }
        }
        return null;
    }

    private static List<BlockPos> findMature(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        int max = params.integer("max", 16);
        BlockPos center = fabric.companion().blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!fabric.level().isLoaded(pos)) {
                continue;
            }
            BlockState state = fabric.level().getBlockState(pos);
            if (CropShape.looksLikeCrop(state)
                    && SemanticService.resolve(state).has(SemanticTag.CROP)
                    && CropShape.isMature(state)) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.size() > max ? found.subList(0, max) : found;
    }

    private static List<BlockPos> findFarmland(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        int max = params.integer("max", 16);
        BlockPos center = fabric.companion().blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!fabric.level().isLoaded(pos)) {
                continue;
            }
            if (fabric.level().getBlockState(pos).getBlock() instanceof FarmlandBlock
                    && fabric.level().getBlockState(pos.above()).isAir()) {
                found.add(pos.above().immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.size() > max ? found.subList(0, max) : found;
    }

    private static List<BlockPos> findTillable(FabricModuleContext fabric, ModuleParams params) {
        int radius = params.integer("radius", 8);
        int max = params.integer("max", 9);
        BlockPos center = fabric.companion().blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius),
                center.offset(radius, 2, radius))) {
            if (!fabric.level().isLoaded(pos)) {
                continue;
            }
            BlockState state = fabric.level().getBlockState(pos);
            if ((state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK))
                    && fabric.level().getBlockState(pos.above()).isAir()) {
                found.add(pos.immutable());
            }
        }
        found.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        return found.size() > max ? found.subList(0, max) : found;
    }

    private static int hoeSlot(CompanionEntity companion) {
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (!stack.isEmpty() && stack.typeHolder().is(ItemTags.HOES)) {
                return slot;
            }
        }
        return -1;
    }

    static ItemStack seedStack(CompanionEntity companion, String seed) {
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                    || !SemanticService.resolve(blockItem.getBlock().defaultBlockState())
                            .has(SemanticTag.CROP)) {
                continue;
            }
            if (ItemMatch.matches(stack, seed.startsWith("#"),
                    seed.startsWith("#") ? seed.substring(1) : seed)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Plants any carried crop seed, vanilla style, consuming exactly one. */
    static boolean plantSeed(CompanionEntity companion, BlockPos pos) {
        if (!companion.level().getBlockState(pos).isAir()
                && !companion.level().getBlockState(pos).canBeReplaced()) {
            return false;
        }
        for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
            ItemStack stack = companion.backpack().getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)
                    || !SemanticService.resolve(blockItem.getBlock().defaultBlockState())
                            .has(SemanticTag.CROP)) {
                continue;
            }
            BlockState place = blockItem.getBlock().defaultBlockState();
            if (!place.canSurvive(companion.level(), pos)) {
                continue;
            }
            if (!companion.level().setBlockAndUpdate(pos, place)) {
                continue;
            }
            stack.shrink(1);
            companion.backpack().setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            companion.backpack().setChanged();
            return true;
        }
        return false;
    }
}
