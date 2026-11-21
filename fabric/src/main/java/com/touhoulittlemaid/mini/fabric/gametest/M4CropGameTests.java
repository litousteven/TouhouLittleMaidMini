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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Proves the M3 generic crop path also handles a crop this codebase has never seen:
 * no new MCP tool, no new action provider, only a {@link CropBlock} in the world.
 */
public final class M4CropGameTests {

    @GameTest(maxTicks = 120)
    public void genericActionsHarvestAndReplantAnUnseenCrop(GameTestHelper helper) {
        if (M4TestCrops.block == null || M4TestCrops.seeds == null) {
            throw helper.assertionException("test crop was not registered under GameTest");
        }
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));

        BlockPos soil = helper.absolutePos(new BlockPos(2, 1, 3));
        BlockPos cropPos = soil.above();
        var level = helper.getLevel();
        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
        BlockState mature = M4TestCrops.block.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE);
        level.setBlockAndUpdate(cropPos, mature);

        ToolResult harvested = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", cropPos.getX(),
                "y", cropPos.getY(),
                "z", cropPos.getZ()));
        if (harvested.status() != ToolResult.Status.OK
                || !harvested.message().contains("harvested")) {
            throw helper.assertionException("unseen crop was not harvested through the "
                    + "generic action: " + harvested.status() + ":" + harvested.code()
                    + ":" + harvested.message());
        }
        if (!level.getBlockState(cropPos).isAir()) {
            throw helper.assertionException("unseen crop remained in the world after harvest");
        }

        level.setBlockAndUpdate(cropPos, Blocks.AIR.defaultBlockState());
        companion.equipment().setItem(0, new ItemStack(M4TestCrops.seeds, 2));
        companion.applyEquipment();

        ToolResult planted = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", cropPos.getX(),
                "y", cropPos.getY(),
                "z", cropPos.getZ()));
        if (planted.status() != ToolResult.Status.OK || !planted.message().contains("planted")) {
            throw helper.assertionException("unseen crop seed was not replanted: "
                    + planted.status() + ":" + planted.code() + ":" + planted.message());
        }
        if (!(level.getBlockState(cropPos).getBlock() instanceof M4TestCrops.TestCropBlock)) {
            throw helper.assertionException("replanted block is not the unseen crop");
        }
        if (level.getBlockState(cropPos).getValue(CropBlock.AGE) != 0) {
            throw helper.assertionException("replanted crop did not start at age 0");
        }
        if (companion.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND)
                .getCount() != 1) {
            throw helper.assertionException("replanting must consume exactly one seed");
        }
        helper.succeed();
    }
}
