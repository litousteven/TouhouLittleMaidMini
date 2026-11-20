package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class M1CommandGameTests {

    @GameTest(maxTicks = 60)
    public void commandsAreRegistered(GameTestHelper helper) {
        var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
        if (dispatcher.getRoot().getChild("tlmm") == null) {
            throw helper.assertionException("tlmm command root was not registered");
        }
        for (String sub : new String[] {"summon", "remove", "rename", "home", "behavior"}) {
            if (dispatcher.getRoot().getChild("tlmm").getChild(sub) == null) {
                throw helper.assertionException("tlmm " + sub + " subcommand was not registered");
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void removalIsBlockedWhileBackpackHoldsItems(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);

        if (!companion.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("fresh companion should be removable");
        }

        companion.backpack().setItem(3, new ItemStack(Items.WHEAT, 1));
        if (companion.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("companion holding items must block silent removal");
        }

        companion.backpack().setItem(3, ItemStack.EMPTY);
        if (!companion.canBeDeletedInventoryLossless()) {
            throw helper.assertionException("emptied companion should be removable again");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void stayBehaviorSitsAndFollowClearsIt(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);

        companion.setBehavior(LocalBehavior.STAY);
        if (!companion.isOrderedToSit()) {
            throw helper.assertionException("stay behaviour should order the companion to sit");
        }

        companion.setBehavior(LocalBehavior.FOLLOW);
        if (companion.isOrderedToSit()) {
            throw helper.assertionException("follow behaviour should clear the sit order");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void homeBindingTracksDimensionAndPosition(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos home = helper.absolutePos(new BlockPos(1, 2, 1));

        companion.setHome(home);
        if (companion.homePosition().isEmpty()) {
            throw helper.assertionException("home position was not stored");
        }
        if (!companion.homePosition().get().dimension().equals(helper.getLevel().dimension())) {
            throw helper.assertionException("home must record its dimension");
        }
        if (!companion.homePosition().get().pos().equals(home)) {
            throw helper.assertionException("home must record the exact block position");
        }

        companion.clearHome();
        if (companion.hasHome()) {
            throw helper.assertionException("clearing home must reset the home flag");
        }
        helper.succeed();
    }
}
