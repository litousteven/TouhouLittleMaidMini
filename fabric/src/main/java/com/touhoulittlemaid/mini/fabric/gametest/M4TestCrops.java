package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.jetbrains.annotations.Nullable;

/**
 * Test-only crop proving a new {@link CropBlock} needs no new MCP tool or Java task.
 * Registered only on the GameTest server so release builds do not ship test content.
 */
public final class M4TestCrops {

    private static final Identifier CROP_ID =
            Identifier.fromNamespaceAndPath(TlmmFabric.MOD_ID, "test_crop");
    private static final Identifier SEED_ID =
            Identifier.fromNamespaceAndPath(TlmmFabric.MOD_ID, "test_crop_seeds");

    @Nullable
    public static Block block;
    @Nullable
    public static Item seeds;

    private M4TestCrops() {
    }

    public static void registerIfGameTest() {
        String gameTestProperty = System.getProperty("fabric-api.gametest");
        if (gameTestProperty == null || "false".equalsIgnoreCase(gameTestProperty)
                || block != null) {
            return;
        }
        block = Registry.register(BuiltInRegistries.BLOCK, CROP_ID,
                new TestCropBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.WHEAT)
                        .setId(net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.BLOCK, CROP_ID))));
        seeds = Registry.register(BuiltInRegistries.ITEM, SEED_ID,
                new BlockItem(block, new Item.Properties().setId(
                        net.minecraft.resources.ResourceKey.create(
                                net.minecraft.core.registries.Registries.ITEM, SEED_ID))));
    }

    /** A crop that only differs by registry id and a seed item, like a modded crop. */
    public static final class TestCropBlock extends CropBlock {

        TestCropBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected ItemLike getBaseSeedId() {
            return seeds == null ? Items.WHEAT_SEEDS : seeds;
        }
    }
}
