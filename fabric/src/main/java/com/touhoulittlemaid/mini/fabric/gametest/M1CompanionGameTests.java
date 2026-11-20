package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionInventoryLayout;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public final class M1CompanionGameTests {

    @GameTest(maxTicks = 60)
    public void companionKeepsIdentityBehaviorAndHome(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setCompanionName("Alice");
        companion.setBehavior(LocalBehavior.GUARD);
        companion.setHome(helper.absolutePos(new BlockPos(3, 2, 3)));
        CompanionSnapshot snapshot = companion.snapshot();
        if (!"Alice".equals(snapshot.name())) {
            throw helper.assertionException("companion name was not retained: " + snapshot.name());
        }
        if (snapshot.behavior() != LocalBehavior.GUARD) {
            throw helper.assertionException("companion behaviour was not retained");
        }
        if (!snapshot.homeSet()) {
            throw helper.assertionException("companion home flag was not set");
        }
        if (!companion.hasHome() || companion.homePosition().isEmpty()) {
            throw helper.assertionException("companion home position was not stored");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void companionBackpackHasExpectedSize(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        if (companion.backpack().getContainerSize() != CompanionInventoryLayout.BACKPACK_SLOTS) {
            throw helper.assertionException("companion backpack size did not match the core layout");
        }
        if (!companion.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("empty companion backpack must be lossless to delete");
        }

        companion.backpack().setItem(0, new ItemStack(Items.WHEAT, 3));
        if (companion.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("non-empty backpack must block silent deletion");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void companionSurvivesNbtRoundTrip(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setCompanionName("Reimu");
        companion.setBehavior(LocalBehavior.FOLLOW);
        companion.setHome(helper.absolutePos(new BlockPos(5, 2, 5)));
        companion.backpack().setItem(1, new ItemStack(Items.WHEAT, 7));

        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        companion.saveWithoutId(output);
        CompoundTag encoded = output.buildResult();

        CompanionEntity restored = new CompanionEntity(FabricContent.COMPANION, helper.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), encoded));

        if (!"Reimu".equals(restored.companionName())) {
            throw helper.assertionException("name did not survive NBT round trip");
        }
        if (restored.behavior() != LocalBehavior.FOLLOW) {
            throw helper.assertionException("behaviour did not survive NBT round trip");
        }
        if (!restored.hasHome()) {
            throw helper.assertionException("home did not survive NBT round trip");
        }
        ItemStack restoredStack = restored.backpack().getItem(1);
        if (restoredStack.getCount() != 7 || !restoredStack.is(Items.WHEAT)) {
            throw helper.assertionException("backpack slot 1 did not survive NBT round trip");
        }
        if (!restored.backpack().getItem(0).isEmpty()) {
            throw helper.assertionException("empty backpack slot 0 must stay empty after round trip");
        }
        helper.succeed();
    }
}
