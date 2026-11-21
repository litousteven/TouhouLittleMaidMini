package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/** Proves the model-free baseline brain can harvest and replant a crop by itself. */
public final class M5BaselineGameTests {

    @GameTest(maxTicks = 300)
    public void farmBehaviorHarvestsAndReplantsWithoutAgent(GameTestHelper helper) {
        if (M4TestCrops.block == null || M4TestCrops.seeds == null) {
            throw helper.assertionException("test crop was not registered under GameTest");
        }
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setBehavior(LocalBehavior.FARM);
        companion.backpack().setItem(0, new ItemStack(M4TestCrops.seeds, 2));
        companion.backpack().setChanged();

        BlockPos soil = helper.absolutePos(new BlockPos(2, 1, 3));
        BlockPos cropPos = soil.above();
        var level = helper.getLevel();
        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
        level.setBlockAndUpdate(cropPos, M4TestCrops.block.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        helper.succeedWhen(() -> {
            if (!(level.getBlockState(cropPos).getBlock() instanceof M4TestCrops.TestCropBlock)) {
                throw helper.assertionException("baseline farm did not replant the crop");
            }
            if (level.getBlockState(cropPos).getValue(CropBlock.AGE) != 0) {
                throw helper.assertionException("baseline farm replanted a mature crop");
            }
            if (companion.backpack().getItem(0).getCount() != 1) {
                throw helper.assertionException("baseline farm did not consume one seed");
            }
        });
    }
}
