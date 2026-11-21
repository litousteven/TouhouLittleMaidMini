package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** M6.2: area modules, tag-driven gathering and deterministic chest tidying. */
public final class M6ModulesGameTests {

    private static ParsedPlan parse(GameTestHelper helper, String json) {
        try {
            return PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            throw helper.assertionException(
                    "plan json rejected: " + exception.code() + " " + exception.getMessage());
        }
    }

    private static boolean start(CompanionEntity companion, ParsedPlan plan) {
        return PlanRuntime.submit(companion, plan, PlanGate.Policy.commandConfirmed()).ok();
    }

    private static String finishedData(CompanionEntity companion) {
        return CompanionRuntimeState.eventBus()
                .after(companion.getUUID().toString(), 0, 400).stream()
                .filter(event -> event.kind().equals("plan_finished"))
                .reduce((first, second) -> second)
                .map(com.touhoulittlemaid.mini.core.CompanionEvent::data)
                .orElse("");
    }

    private static String single(String module, String params) {
        return "{\"version\":1,\"steps\":[{\"module\":\"" + module + "\",\"params\":{"
                + params + "}}]}";
    }

    @GameTest(maxTicks = 160)
    public void harvestAreaHarvestsAndReplantsEverything(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 3));
        companion.backpack().setChanged();
        BlockPos[] crops = new BlockPos[3];
        for (int index = 0; index < 3; index++) {
            BlockPos soil = new BlockPos(3 + index, 1, 2);
            helper.setBlock(soil, Blocks.FARMLAND);
            helper.setBlock(soil.above(), Blocks.WHEAT.defaultBlockState()
                    .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
            crops[index] = helper.absolutePos(soil.above());
        }

        if (!start(companion, parse(helper, single("harvest_area",
                "\"radius\":4,\"replant\":true,\"max\":6")))) {
            throw helper.assertionException("harvest plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            for (BlockPos crop : crops) {
                if (!(helper.getLevel().getBlockState(crop).getBlock() instanceof CropBlock)) {
                    throw helper.assertionException("a crop was not replanted");
                }
            }
            if (companion.backpack().getItem(0).getCount() != 0) {
                throw helper.assertionException("replanting should consume all three seeds");
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void plantAreaFillsEmptyFarmland(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 2));
        companion.backpack().setChanged();
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.FARMLAND);

        if (!start(companion, parse(helper, single("plant_area",
                "\"radius\":4,\"seed\":\"minecraft:wheat_seeds\",\"max\":4")))) {
            throw helper.assertionException("plant plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            if (!(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 2, 2)))
                    .getBlock() instanceof CropBlock)
                    || !(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(4, 2, 2)))
                    .getBlock() instanceof CropBlock)) {
                throw helper.assertionException("farmland was not planted");
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void tillingWithoutHoeIsRefused(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.DIRT);
        if (!start(companion, parse(helper, single("till_area", "\"radius\":4")))) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains(FailureCodes.TOOL_NOT_IN_INVENTORY)) {
                throw helper.assertionException("expected tool_not_in_inventory, got " + data);
            }
            if (helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 1, 2)))
                    .is(Blocks.FARMLAND)) {
                throw helper.assertionException("dirt was tilled without a hoe");
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void tillingWithHoeMakesFarmland(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.WOODEN_HOE));
        companion.backpack().setChanged();
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.DIRT);

        if (!start(companion, parse(helper, single("till_area", "\"radius\":4")))) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            if (!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 1, 2)))
                    .is(Blocks.FARMLAND)) {
                throw helper.assertionException("dirt was not tilled");
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void miningWithoutPickaxeIsRefused(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        if (!start(companion, parse(helper, single("mine_material",
                "\"radius\":4,\"count\":1,\"ids\":[\"minecraft:stone\"]")))) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains(FailureCodes.TOOL_NOT_IN_INVENTORY)) {
                throw helper.assertionException("expected tool_not_in_inventory, got " + data);
            }
            if (!helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 1, 2)))
                    .is(Blocks.STONE)) {
                throw helper.assertionException("stone was mined without a pickaxe");
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void miningProtectsTheHomeRadius(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        BlockPos stone = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.STONE);
        companion.setHome(stone);

        if (!start(companion, parse(helper, single("mine_material",
                "\"radius\":8,\"count\":1,\"ids\":[\"minecraft:stone\"]")))) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains(FailureCodes.NOT_HARVESTABLE)) {
                throw helper.assertionException("expected not_harvestable, got " + data);
            }
            if (!helper.getLevel().getBlockState(stone).is(Blocks.STONE)) {
                throw helper.assertionException("home radius block was mined");
            }
        });
    }

    @GameTest(maxTicks = 160)
    public void chopTreeOnlyBreaksLogs(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        BlockPos log = helper.absolutePos(new BlockPos(3, 1, 2));
        BlockPos dirt = helper.absolutePos(new BlockPos(4, 1, 2));
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.DIRT);

        if (!start(companion, parse(helper, single("chop_tree",
                "\"radius\":4,\"count\":1")))) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            if (!helper.getLevel().getBlockState(log).isAir()) {
                throw helper.assertionException("the log was not chopped");
            }
            if (!helper.getLevel().getBlockState(dirt).is(Blocks.DIRT)) {
                throw helper.assertionException("chop_tree broke a non-log block");
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void tidyChestsMergesAndConservesCounts(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        for (int index = 0; index < 3; index++) {
            helper.setBlock(new BlockPos(3 + index, 1, 3), Blocks.CHEST);
        }
        ChestBlockEntity first = (ChestBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(new BlockPos(3, 1, 3)));
        ChestBlockEntity second = (ChestBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(new BlockPos(4, 1, 3)));
        ChestBlockEntity third = (ChestBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(new BlockPos(5, 1, 3)));
        if (first == null || second == null || third == null) {
            throw helper.assertionException("chests were not created");
        }
        first.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        second.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        second.setItem(1, new ItemStack(Items.OAK_PLANKS, 10));

        if (!start(companion, parse(helper, single("tidy_chests",
                "\"radius\":8,\"budget\":50")))) {
            throw helper.assertionException("tidy plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains("\"ok\":true")) {
                throw helper.assertionException("tidy failed: " + data);
            }
            int cobble = 0;
            int planks = 0;
            int empty = 0;
            for (ChestBlockEntity chest : new ChestBlockEntity[] {first, second, third}) {
                if (chest.isEmpty()) {
                    empty++;
                }
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    ItemStack stack = chest.getItem(slot);
                    if (stack.is(Items.COBBLESTONE)) {
                        cobble += stack.getCount();
                    }
                    if (stack.is(Items.OAK_PLANKS)) {
                        planks += stack.getCount();
                    }
                }
            }
            if (cobble != 48 || planks != 10) {
                throw helper.assertionException("counts changed: cobble=" + cobble
                        + " planks=" + planks);
            }
            if (empty < 1) {
                throw helper.assertionException("tidy must leave at least one empty chest");
            }
        });
    }

    @GameTest(maxTicks = 200)
    public void tidyNeverTouchesKeepChests(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        for (int index = 0; index < 3; index++) {
            helper.setBlock(new BlockPos(3 + index, 1, 4), Blocks.CHEST);
        }
        ChestBlockEntity keep = (ChestBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(new BlockPos(3, 1, 4)));
        ChestBlockEntity second = (ChestBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(new BlockPos(4, 1, 4)));
        ChestBlockEntity third = (ChestBlockEntity) helper.getLevel().getBlockEntity(
                helper.absolutePos(new BlockPos(5, 1, 4)));
        if (keep == null || second == null || third == null) {
            throw helper.assertionException("chests were not created");
        }
        // 26.3 stores custom names as data components on the block entity.
        keep.applyComponents(net.minecraft.core.component.DataComponentMap.EMPTY,
                net.minecraft.core.component.DataComponentPatch.builder()
                        .set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                                Component.literal("keep:ores"))
                        .build());
        keep.setItem(0, new ItemStack(Items.COBBLESTONE, 32));
        second.setItem(0, new ItemStack(Items.COBBLESTONE, 16));
        third.setItem(0, new ItemStack(Items.COBBLESTONE, 16));

        if (!start(companion, parse(helper, single("tidy_chests",
                "\"radius\":8,\"budget\":50")))) {
            throw helper.assertionException("tidy plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            if (keep.getItem(0).getCount() != 32) {
                throw helper.assertionException("the keep chest was modified");
            }
            int merged = second.getItem(0).getCount() + third.getItem(0).getCount();
            if (merged != 32) {
                throw helper.assertionException("counts changed outside the keep chest: "
                        + merged);
            }
        });
    }
}
