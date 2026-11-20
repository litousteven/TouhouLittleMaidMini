package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.ToolRegistry;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/** M3 context slices: categories, tag/ID filters and bounded results. */
public final class M3ContextGameTests {

    private static ToolRegistry registry(GameTestHelper helper) {
        return CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));
    }

    @GameTest(maxTicks = 80)
    public void blockFiltersSelectOnlyMatchingBlocks(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.setBlock(3, 1, 2, Blocks.FARMLAND);
        helper.setBlock(3, 2, 2, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        helper.setBlock(4, 2, 2, Blocks.STONE);
        ToolRegistry registry = registry(helper);
        String id = companion.getUUID().toString();

        ToolResult byTag = registry.invoke("companion_context", Map.of(
                "companion_id", id,
                "category", "blocks",
                "radius", 6,
                "tags", List.of("minecraft:crops")));
        if (byTag.status() != ToolResult.Status.OK
                || !byTag.message().contains("minecraft:wheat")) {
            throw helper.assertionException("crop tag filter missed wheat: " + byTag.message());
        }
        if (byTag.message().contains("\"id\":\"minecraft:stone\"")) {
            throw helper.assertionException("crop tag filter leaked stone: " + byTag.message());
        }

        ToolResult byId = registry.invoke("companion_context", Map.of(
                "companion_id", id,
                "category", "blocks",
                "radius", 6,
                "block_ids", List.of("minecraft:stone")));
        if (!byId.message().contains("\"id\":\"minecraft:stone\"")) {
            throw helper.assertionException("block id filter missed stone: " + byId.message());
        }
        if (byId.message().contains("\"id\":\"minecraft:wheat\"")) {
            throw helper.assertionException("block id filter leaked wheat: " + byId.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void entityFilterUsesTypeIDs(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.spawn(EntityTypes.VILLAGER, 4, 2, 4);
        helper.spawn(EntityTypes.ZOMBIE, 5, 2, 5);
        ToolRegistry registry = registry(helper);

        ToolResult villagers = registry.invoke("companion_context", Map.of(
                "companion_id", companion.getUUID().toString(),
                "category", "entities",
                "radius", 8,
                "entity_types", List.of("minecraft:villager")));
        if (!villagers.message().contains("minecraft:villager")) {
            throw helper.assertionException(
                    "entity filter missed the villager: " + villagers.message());
        }
        if (villagers.message().contains("minecraft:zombie")) {
            throw helper.assertionException(
                    "entity filter leaked the zombie: " + villagers.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void selfAndInventoryCategoriesExposeBodyState(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.hunger().setLevel(7);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT, 3));
        ToolRegistry registry = registry(helper);
        String id = companion.getUUID().toString();

        ToolResult self = registry.invoke("companion_context", Map.of(
                "companion_id", id, "category", "self"));
        if (!self.message().contains("\"hunger\":7")
                || !self.message().contains("\"category\":\"self\"")) {
            throw helper.assertionException("self context was incomplete: " + self.message());
        }

        ToolResult inventory = registry.invoke("companion_context", Map.of(
                "companion_id", id, "category", "inventory"));
        if (!inventory.message().contains("\"item\":\"minecraft:wheat\"")
                || !inventory.message().contains("\"count\":3")) {
            throw helper.assertionException(
                    "inventory context missed the wheat: " + inventory.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void invalidTagIsAStructuredRefusal(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolResult result = registry(helper).invoke("companion_context", Map.of(
                "companion_id", companion.getUUID().toString(),
                "category", "blocks",
                "radius", 4,
                "tags", List.of("not a valid tag")));
        if (!"invalid_params".equals(result.code())) {
            throw helper.assertionException(
                    "invalid tag must be rejected, got " + result.code());
        }
        helper.succeed();
    }
}
