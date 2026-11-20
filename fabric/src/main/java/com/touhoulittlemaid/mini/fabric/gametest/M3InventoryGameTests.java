package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.ToolRegistry;

import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M3 inventory actions: list, read, move, equip and drop without item creation. */
public final class M3InventoryGameTests {

    private static ToolRegistry registry(GameTestHelper helper) {
        return CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));
    }

    private static String id(CompanionEntity companion) {
        return companion.getUUID().toString();
    }

    @GameTest(maxTicks = 80)
    public void listReadAndMoveItems(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
        companion.backpack().setItem(1, new ItemStack(Items.DIAMOND_HELMET));
        ToolRegistry registry = registry(helper);

        ToolResult list = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "list"));
        if (!list.message().contains("minecraft:wheat_seeds")
                || !list.message().contains("minecraft:diamond_helmet")) {
            throw helper.assertionException("inventory list was incomplete: " + list.message());
        }

        ToolResult read = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "read", "item", "minecraft:wheat_seeds"));
        if (!read.message().contains("\"count\":8")) {
            throw helper.assertionException("read did not report the stack: " + read.message());
        }

        ToolResult moved = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "move",
                "item", "minecraft:wheat_seeds", "count", 3,
                "source", "inventory", "destination", "main_hand"));
        if (moved.status() != ToolResult.Status.OK) {
            throw helper.assertionException("move failed: " + moved.message());
        }
        if (!companion.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.WHEAT_SEEDS)) {
            throw helper.assertionException("main hand did not receive the seeds");
        }
        if (companion.backpack().getItem(0).getCount() != 5) {
            throw helper.assertionException("source stack was not reduced");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void equipWritesTheEntitySlot(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(1, new ItemStack(Items.DIAMOND_HELMET));
        ToolRegistry registry = registry(helper);

        ToolResult equipped = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "equip",
                "item", "minecraft:diamond_helmet", "destination", "head"));
        if (equipped.status() != ToolResult.Status.OK) {
            throw helper.assertionException("equip failed: " + equipped.message());
        }
        if (!companion.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)) {
            throw helper.assertionException("head equipment was not applied");
        }
        if (!companion.backpack().getItem(1).isEmpty()) {
            throw helper.assertionException("helmet was not removed from the backpack");
        }

        companion.backpack().setItem(2, new ItemStack(Items.DIAMOND_HELMET));
        ToolResult occupied = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "equip",
                "item", "minecraft:diamond_helmet", "destination", "head"));
        if (!"busy".equals(occupied.code())) {
            throw helper.assertionException(
                    "occupied equipment slot must be busy, got " + occupied.code());
        }

        ToolResult wrongSlot = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "equip",
                "item", "minecraft:diamond_helmet", "destination", "legs"));
        if (!"invalid_params".equals(wrongSlot.code())) {
            throw helper.assertionException(
                    "a helmet must not fit the legs, got " + wrongSlot.code());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void moveBackFromEquipmentAndDrop(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.equipment().setItem(0, new ItemStack(Items.IRON_SWORD));
        companion.applyEquipment();
        ToolRegistry registry = registry(helper);

        ToolResult back = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "move",
                "item", "minecraft:iron_sword",
                "source", "main_hand", "destination", "inventory"));
        if (back.status() != ToolResult.Status.OK) {
            throw helper.assertionException("move back failed: " + back.message());
        }
        if (!companion.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            throw helper.assertionException("main hand was not cleared");
        }
        if (companion.backpack().getItem(0).is(Items.IRON_SWORD) == false) {
            throw helper.assertionException("sword did not reach the backpack");
        }

        ToolResult pending = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "drop",
                "item", "minecraft:iron_sword", "count", 1));
        if (!"permission_required".equals(pending.code())) {
            throw helper.assertionException(
                    "drop must ask for authorization first: " + pending.message());
        }
        String permissionId = pending.message()
                .substring(pending.message().indexOf("permission_id=") + "permission_id=".length())
                .strip();
        if (!com.touhoulittlemaid.mini.fabric.permission.CompanionPermissions
                .decide(permissionId, true)) {
            throw helper.assertionException("could not approve the drop");
        }

        ToolResult dropped = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "drop",
                "item", "minecraft:iron_sword", "count", 1));
        if (dropped.status() != ToolResult.Status.OK) {
            throw helper.assertionException("drop failed after approval: " + dropped.message());
        }
        if (!companion.backpack().getItem(0).isEmpty()) {
            throw helper.assertionException("dropped item stayed in the backpack");
        }
        int itemEntities = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                companion.getBoundingBox().inflate(8.0D)).size();
        if (itemEntities < 1) {
            throw helper.assertionException("drop did not spawn an item entity");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void fullBackpackAndMissingItemsAreRefused(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        for (int slot = 0; slot < 9; slot++) {
            companion.backpack().setItem(slot, new ItemStack(Items.DIRT, 64));
        }
        companion.equipment().setItem(0, new ItemStack(Items.IRON_SWORD));
        companion.applyEquipment();
        ToolRegistry registry = registry(helper);

        ToolResult full = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "move",
                "item", "minecraft:iron_sword",
                "source", "main_hand", "destination", "inventory"));
        if (!"inventory_full".equals(full.code())) {
            throw helper.assertionException(
                    "full backpack must reject the move, got " + full.code());
        }
        if (!companion.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.IRON_SWORD)) {
            throw helper.assertionException("refused move must not consume the source");
        }

        ToolResult missing = registry.invoke("companion_inventory", Map.of(
                "companion_id", id(companion), "action", "move",
                "item", "minecraft:gold_ingot",
                "source", "inventory", "destination", "main_hand"));
        if (!"missing_item".equals(missing.code())) {
            throw helper.assertionException(
                    "missing item must be explicit, got " + missing.code());
        }
        helper.succeed();
    }
}
