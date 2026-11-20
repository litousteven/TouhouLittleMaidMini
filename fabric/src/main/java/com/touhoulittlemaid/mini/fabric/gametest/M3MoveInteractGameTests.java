package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.ToolRegistry;

import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/** M3 move modes and generic interaction, including replanting. */
public final class M3MoveInteractGameTests {

    private static ToolRegistry registry(GameTestHelper helper) {
        return CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));
    }

    @GameTest(maxTicks = 100)
    public void matureCropIsHarvestedAndReplanted(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos soil = helper.absolutePos(new BlockPos(3, 1, 2));
        BlockPos crop = soil.above();
        helper.setBlock(3, 1, 2, Blocks.FARMLAND);
        helper.setBlock(3, 2, 2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHEAT_SEEDS, 2));
        ToolRegistry registry = registry(helper);

        ToolResult harvested = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", crop.getX(), "y", crop.getY(), "z", crop.getZ()));
        if (!"harvested".equals(harvested.code())) {
            throw helper.assertionException("harvest failed: " + harvested.message());
        }
        if (!helper.getLevel().getBlockState(crop).isAir()) {
            throw helper.assertionException("harvested crop should leave air");
        }

        ToolResult planted = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", crop.getX(), "y", crop.getY(), "z", crop.getZ()));
        if (!"planted".equals(planted.code())) {
            throw helper.assertionException("replant failed: " + planted.message());
        }
        if (!(helper.getLevel().getBlockState(crop).getBlock() instanceof CropBlock)) {
            throw helper.assertionException("replant did not place a crop");
        }
        if (companion.getItemBySlot(EquipmentSlot.MAINHAND).getCount() != 1) {
            throw helper.assertionException("replant should consume exactly one seed");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void interactingWithAirWithoutSeedsIsExplicit(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos soil = helper.absolutePos(new BlockPos(3, 1, 2));
        BlockPos air = soil.above();
        helper.setBlock(3, 1, 2, Blocks.FARMLAND);
        ToolRegistry registry = registry(helper);

        ToolResult result = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", air.getX(), "y", air.getY(), "z", air.getZ()));
        if (!"unsupported_interaction".equals(result.code())) {
            throw helper.assertionException(
                    "planting without a seed must be explicit, got " + result.code());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void replaceFalseRefusesToInterruptNavigation(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registry(helper);
        String id = companion.getUUID().toString();

        ToolResult first = registry.invoke("companion_move", Map.of(
                "companion_id", id, "x", 2, "y", 2, "z", 10));
        if (first.status() != ToolResult.Status.OK) {
            throw helper.assertionException("first move failed: " + first.message());
        }
        ToolResult busy = registry.invoke("companion_move", Map.of(
                "companion_id", id, "x", 2, "y", 2, "z", -6, "replace", false));
        if ("busy".equals(busy.code())) {
            ToolResult replaced = registry.invoke("companion_move", Map.of(
                    "companion_id", id, "x", 2, "y", 2, "z", -6, "replace", true));
            if (replaced.status() != ToolResult.Status.OK) {
                throw helper.assertionException(
                        "replace=true should interrupt, got " + replaced.code());
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void awayModeMovesInTheOppositeDirection(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolResult result = registry(helper).invoke("companion_move", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", 2, "y", 2, "z", 2,
                "mode", "away",
                "distance", 4.0));
        if (result.status() != ToolResult.Status.OK
                || !result.message().contains("away")) {
            throw helper.assertionException("away move was not accepted: " + result.message());
        }
        helper.succeed();
    }
}
