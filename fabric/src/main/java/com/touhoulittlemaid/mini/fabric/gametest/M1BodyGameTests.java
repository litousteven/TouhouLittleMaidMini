package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionHunger;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.fabric.menu.CompanionMenu;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.ToolRegistry;

import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public final class M1BodyGameTests {

    @GameTest(maxTicks = 60)
    public void equipmentPersistsAndApplies(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.equipment().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        companion.equipment().setItem(5, new ItemStack(Items.DIAMOND_HELMET));
        companion.applyEquipment();

        if (!companion.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD)) {
            throw helper.assertionException("main hand equipment was not applied");
        }
        if (!companion.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)) {
            throw helper.assertionException("head equipment was not applied");
        }

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        companion.saveWithoutId(output);
        CompoundTag encoded = output.buildResult();

        CompanionEntity restored = new CompanionEntity(FabricContent.COMPANION, helper.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), encoded));

        if (!restored.equipment().getItem(5).is(Items.DIAMOND_HELMET)) {
            throw helper.assertionException("equipment slot 5 did not survive NBT round trip");
        }
        if (!restored.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)) {
            throw helper.assertionException("restored equipment was not applied to the entity");
        }
        if (restored.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("equipped items must block silent deletion");
        }
        restored.equipment().setItem(0, ItemStack.EMPTY);
        restored.equipment().setItem(5, ItemStack.EMPTY);
        if (!restored.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("emptied equipment should allow deletion again");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void identityAndBodyStateSurviveRoundTrip(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.hunger().setLevel(7);
        companion.setWorkRadius(24);

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        companion.saveWithoutId(output);
        CompoundTag encoded = output.buildResult();

        CompanionEntity restored = new CompanionEntity(FabricContent.COMPANION, helper.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), encoded));

        helper.assertValueEqual(restored.hunger().level(), 7, "hunger");
        helper.assertValueEqual(restored.workRadius(), 24, "work radius");
        helper.succeed();
    }

    @GameTest(maxTicks = 160)
    public void hungryCompanionEatsFromBackpack(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setBehavior(LocalBehavior.STAY);
        companion.hunger().setLevel(3);
        companion.backpack().setItem(0, new ItemStack(Items.BREAD, 1));

        helper.runAfterDelay(80, () -> {
            if (companion.hunger().level() <= 3) {
                throw helper.assertionException("companion did not eat while hungry");
            }
            if (companion.hunger().level() > CompanionHunger.MAX_LEVEL) {
                throw helper.assertionException("hunger exceeded the maximum level");
            }
            if (!companion.backpack().getItem(0).isEmpty()) {
                throw helper.assertionException("the bread should have been consumed");
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 240)
    public void followsOwnerAroundAnObstacle(GameTestHelper helper) {
        for (int x = 0; x <= 10; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.setBlock(x, 1, z, Blocks.STONE);
            }
        }
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 1, 2, 2);
        Villager owner = helper.spawn(EntityTypes.VILLAGER, 9, 2, 2);
        owner.setNoAi(true);
        companion.setOwner(owner);
        companion.setBehavior(LocalBehavior.FOLLOW);

        for (int z = 1; z <= 3; z++) {
            for (int y = 2; y <= 3; y++) {
                helper.setBlock(5, y, z, Blocks.STONE);
            }
        }

        double startDistance = companion.distanceToSqr(owner);
        helper.runAfterDelay(200, () -> {
            double currentDistance = companion.distanceToSqr(owner);
            if (currentDistance >= startDistance - 4.0D) {
                throw helper.assertionException(
                        "companion did not follow the owner around the obstacle");
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 60)
    public void lowHealthBlocksBodyActions(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setHealth(1.0F);
        ToolRegistry registry = CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));
        BlockPos target = companion.blockPosition();

        ToolResult blocked = registry.invoke("companion_move", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", target.getX(), "y", target.getY(), "z", target.getZ()));
        if (!"unsafe_area".equals(blocked.code())) {
            throw helper.assertionException(
                    "low health must reject body actions, got " + blocked.code());
        }

        companion.setHealth(companion.getMaxHealth());
        ToolResult allowed = registry.invoke("companion_move", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", target.getX(), "y", target.getY(), "z", target.getZ()));
        if (allowed.status() != ToolResult.Status.OK) {
            throw helper.assertionException(
                    "healthy companion should accept a move, got " + allowed.code());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void guardTargetRulesProtectAllies(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        Player owner = helper.makeMockPlayer(GameType.CREATIVE);
        companion.setOwner(owner);

        if (companion.canAttack(owner)) {
            throw helper.assertionException("companion must never target its owner");
        }

        Villager villager = helper.spawn(EntityTypes.VILLAGER, 4, 2, 4);
        if (companion.canAttack(villager)) {
            throw helper.assertionException("companion must not default-target villagers");
        }

        Wolf wolf = helper.spawn(EntityTypes.WOLF, 5, 2, 5);
        wolf.setTame(true, true);
        wolf.setOwner(owner);
        if (companion.canAttack(wolf)) {
            throw helper.assertionException("companion must not default-target tamed pets");
        }

        Zombie zombie = helper.spawn(EntityTypes.ZOMBIE, 6, 2, 6);
        if (!companion.canAttack(zombie)) {
            throw helper.assertionException("guard must be able to target hostile monsters");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void managementMenuExposesSlotsAndSafeControls(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        companion.setOwner(player);

        companion.backpack().setItem(0, new ItemStack(Items.WHEAT, 4));
        companion.equipment().setItem(5, new ItemStack(Items.DIAMOND_HELMET));

        CompanionMenu menu = new CompanionMenu(7, player.getInventory(), companion);
        if (menu.slots.size() != 9 + 6 + 36) {
            throw helper.assertionException("unexpected menu slot count: " + menu.slots.size());
        }
        if (!menu.getSlot(0).getItem().is(Items.WHEAT)) {
            throw helper.assertionException("backpack slot 0 was not exposed");
        }
        if (!menu.getSlot(9 + 5).getItem().is(Items.DIAMOND_HELMET)) {
            throw helper.assertionException("equipment slot 5 was not exposed");
        }
        if (!menu.getSlot(9 + 5).mayPlace(new ItemStack(Items.DIAMOND_HELMET))) {
            throw helper.assertionException("helmet should fit the head slot");
        }
        if (menu.getSlot(9 + 5).mayPlace(new ItemStack(Items.DIAMOND_SWORD))) {
            throw helper.assertionException("sword must not fit the head slot");
        }

        if (!menu.clickMenuButton(player, CompanionMenu.BUTTON_CYCLE_BEHAVIOR)) {
            throw helper.assertionException("behaviour button was not handled");
        }
        if (companion.behavior() != LocalBehavior.FOLLOW) {
            throw helper.assertionException("behaviour did not cycle to follow");
        }
        if (!menu.clickMenuButton(player, CompanionMenu.BUTTON_SET_HOME)) {
            throw helper.assertionException("home button was not handled");
        }
        if (!companion.hasHome()) {
            throw helper.assertionException("home button did not set home");
        }

        if (menu.clickMenuButton(player, CompanionMenu.BUTTON_CONFIRM_DELETE)) {
            throw helper.assertionException("delete with items must be refused");
        }
        if (companion.isRemoved()) {
            throw helper.assertionException("companion must survive a refused delete");
        }

        companion.backpack().setItem(0, ItemStack.EMPTY);
        companion.equipment().setItem(5, ItemStack.EMPTY);
        if (!menu.clickMenuButton(player, CompanionMenu.BUTTON_CONFIRM_DELETE)) {
            throw helper.assertionException("confirmed lossless delete was not handled");
        }
        if (!companion.isRemoved()) {
            throw helper.assertionException("confirmed delete did not remove the companion");
        }
        helper.succeed();
    }
}
