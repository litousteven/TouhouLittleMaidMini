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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** M3 item pickup, container contents and the work schedule. */
public final class M3PickupContainerGameTests {

    private static ToolRegistry registry(GameTestHelper helper) {
        return CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));
    }

    @GameTest(maxTicks = 100)
    public void droppedItemIsPickedUpIntoTheBackpack(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ItemEntity dropped = helper.spawnItem(Items.WHEAT, 3, 2, 2);
        dropped.setItem(new ItemStack(Items.WHEAT, 3));
        dropped.setPickUpDelay(0);
        ToolResult result = registry(helper).invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "target_kind", "entity",
                "entity_id", dropped.getUUID().toString()));
        if (!"picked_up".equals(result.code())) {
            throw helper.assertionException("pickup failed: " + result.message());
        }
        if (!companion.backpack().getItem(0).is(Items.WHEAT)
                || companion.backpack().getItem(0).getCount() != 3) {
            throw helper.assertionException("wheat did not reach the backpack");
        }
        if (!dropped.isRemoved()) {
            throw helper.assertionException("picked up item entity should be removed");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void pickupLeavesTheOverflowBehind(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        for (int slot = 0; slot < 8; slot++) {
            companion.backpack().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.backpack().setItem(8, new ItemStack(Items.STONE, 60));
        ItemEntity dropped = helper.spawnItem(Items.STONE, 3, 2, 2);
        dropped.setItem(new ItemStack(Items.STONE, 10));
        dropped.setPickUpDelay(0);
        ToolResult result = registry(helper).invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "target_kind", "entity",
                "entity_id", dropped.getUUID().toString()));

        if (!"picked_up".equals(result.code())) {
            throw helper.assertionException("partial pickup failed: " + result.message());
        }
        if (companion.backpack().getItem(8).getCount() != 64) {
            throw helper.assertionException("partial pickup should top up the existing stack");
        }
        if (dropped.isRemoved() || dropped.getItem().getCount() != 6) {
            throw helper.assertionException("overflow should remain on the ground");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void fullBackpackRefusesPickup(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        for (int slot = 0; slot < 9; slot++) {
            companion.backpack().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        ItemEntity dropped = helper.spawnItem(Items.WHEAT, 3, 2, 2);
        dropped.setPickUpDelay(0);
        ToolResult result = registry(helper).invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "target_kind", "entity",
                "entity_id", dropped.getUUID().toString()));

        if (!"inventory_full".equals(result.code())) {
            throw helper.assertionException(
                    "full backpack must refuse pickup, got " + result.code());
        }
        if (dropped.isRemoved()) {
            throw helper.assertionException("refused pickup must not consume the item");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void nonItemEntityInteractionStaysExplicit(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        var villager = helper.spawn(net.minecraft.world.entity.EntityTypes.VILLAGER, 3, 2, 3);
        ToolResult result = registry(helper).invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "target_kind", "entity",
                "entity_id", villager.getUUID().toString()));

        if (!"unsupported_interaction".equals(result.code())) {
            throw helper.assertionException(
                    "non-item entity interaction must stay explicit, got " + result.code());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void airInteractionStaysExplicit(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolResult result = registry(helper).invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "target_kind", "air",
                "x", 2, "y", 3, "z", 2));

        if (!"unsupported_interaction".equals(result.code())) {
            throw helper.assertionException(
                    "air interaction must stay explicit, got " + result.code());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void containerContentsAreReported(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.setBlock(3, 2, 2, Blocks.CHEST);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 2, 2));
        var blockEntity = helper.getLevel().getBlockEntity(chest);
        if (!(blockEntity instanceof net.minecraft.world.Container container)) {
            throw helper.assertionException("chest did not expose a container");
        }
        container.setItem(0, new ItemStack(Items.DIAMOND, 5));
        container.setChanged();

        ToolResult result = registry(helper).invoke("companion_context", Map.of(
                "companion_id", companion.getUUID().toString(),
                "category", "containers",
                "radius", 6));

        if (result.status() != ToolResult.Status.OK) {
            throw helper.assertionException("container context failed: " + result.message());
        }
        if (!result.message().contains("\"item\":\"minecraft:diamond\"")
                || !result.message().contains("\"count\":5")) {
            throw helper.assertionException(
                    "container contents were not reported: " + result.message());
        }
        if (result.message().contains("not_read")) {
            throw helper.assertionException(
                    "containers must no longer report not_read: " + result.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void scheduleIsPersistedAndReported(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registry(helper);

        ToolResult set = registry.invoke("companion_set_behavior", Map.of(
                "companion_id", companion.getUUID().toString(),
                "behavior", "guard",
                "schedule", "night"));
        if (set.status() != ToolResult.Status.OK) {
            throw helper.assertionException("schedule set failed: " + set.message());
        }
        if (companion.schedule() != com.touhoulittlemaid.mini.core.CompanionSchedule.NIGHT) {
            throw helper.assertionException("schedule was not applied to the entity");
        }

        ToolResult self = registry.invoke("companion_context", Map.of(
                "companion_id", companion.getUUID().toString(),
                "category", "self"));
        if (!self.message().contains("\"schedule\":\"night\"")) {
            throw helper.assertionException(
                    "self context did not report the schedule: " + self.message());
        }
        helper.succeed();
    }
}
