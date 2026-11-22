package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionEvent;
import com.touhoulittlemaid.mini.core.autonomy.Opportunity;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.fabric.autonomy.Director;
import com.touhoulittlemaid.mini.fabric.autonomy.OpportunityScanner;
import com.touhoulittlemaid.mini.fabric.autonomy.PlacementTracker;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * M6.5 acceptance: the deterministic part of autonomy.
 *
 * <p>The model is only asked to pick from a list; every rule that produces or blocks that list
 * is Java and is checked here. Global state is per-companion (keyed by UUID) except for the
 * operator pause, which this class sets and clears inside one synchronous test so parallel
 * GameTests cannot observe it.
 */
public final class M6AutonomyGameTests {

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void matureCrops(GameTestHelper helper, int count) {
        // A compact grid, not a long row: the scanner only looks cropRadius blocks away,
        // and a row of eight would fall outside a six-block work radius.
        for (int index = 0; index < count; index++) {
            BlockPos soil = new BlockPos(3 + index % 3, 1, 2 + index / 3);
            helper.setBlock(soil, Blocks.FARMLAND);
            helper.setBlock(soil.above(), Blocks.WHEAT.defaultBlockState()
                    .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        }
    }

    private static void ready(CompanionEntity companion) {
        companion.hunger().setLevel(20);
        companion.setHealth(companion.getMaxHealth());
    }

    private static ChestBlockEntity chest(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, Blocks.CHEST);
        var entity = helper.getLevel().getBlockEntity(helper.absolutePos(pos));
        if (!(entity instanceof ChestBlockEntity chest)) {
            throw helper.assertionException("chest was not created at " + pos);
        }
        return chest;
    }

    private static boolean has(List<Opportunity> found, String id) {
        return found.stream().anyMatch(row -> row.id().equals(id));
    }

    private static List<Opportunity> scan(GameTestHelper helper, CompanionEntity companion,
            PlanGate.Policy policy) {
        return OpportunityScanner.scan(helper.getLevel(), companion, now(), true, policy);
    }

    private static ParsedPlan farGoto(GameTestHelper helper, CompanionEntity companion) {
        BlockPos start = companion.blockPosition();
        try {
            return PlanJson.parse("{\"version\":1,\"steps\":[{\"module\":\"goto\","
                    + "\"params\":{\"x\":" + (start.getX() + 20) + ",\"y\":" + start.getY()
                    + ",\"z\":" + start.getZ() + ",\"range\":2}}]}");
        } catch (PlanFormatException exception) {
            throw helper.assertionException("plan json rejected: " + exception.code());
        }
    }

    // ---- scenario B: the harvest and cooking signals ---------------------------------------

    @GameTest(maxTicks = 40)
    public void matureCropsWithSeedsOfferHarvest(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setWorkRadius(6);
        ready(companion);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 12));
        matureCrops(helper, OpportunityScanner.MIN_MATURE_CROPS);

        List<Opportunity> found = scan(helper, companion, PlanGate.Policy.chores());
        if (!has(found, "harvest_farm")) {
            throw helper.assertionException("no harvest opportunity: " + found);
        }
        Opportunity harvest = found.stream().filter(row -> row.id().equals("harvest_farm"))
                .findFirst().orElseThrow();
        if (!Boolean.TRUE.equals(harvest.suggested().get("replant"))) {
            throw helper.assertionException("harvest must replant: " + harvest.describe());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void noSeedsMeansNoHarvestOpportunity(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setWorkRadius(6);
        matureCrops(helper, OpportunityScanner.MIN_MATURE_CROPS);

        if (has(scan(helper, companion, PlanGate.Policy.chores()), "harvest_farm")) {
            throw helper.assertionException("harvest was offered without any seed");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void rawFoodFuelAndAFurnaceOfferCooking(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setWorkRadius(6);
        ChestBlockEntity chest = chest(helper, new BlockPos(3, 1, 3));
        chest.setItem(0, new ItemStack(Items.BEEF, OpportunityScanner.MIN_SMELTABLES));
        chest.setItem(1, new ItemStack(Items.COAL, 2));
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.FURNACE);

        List<Opportunity> found = scan(helper, companion, PlanGate.Policy.chores());
        if (!has(found, "cook_food")) {
            throw helper.assertionException("cooking was not offered: " + found);
        }
        Opportunity cook = found.stream().filter(row -> row.id().equals("cook_food"))
                .findFirst().orElseThrow();
        if (!cook.suggested().containsKey("x") || !cook.suggested().containsKey("input")) {
            throw helper.assertionException("cook_food lacks a furnace or an input: "
                    + cook.describe());
        }
        helper.succeed();
    }

    // ---- scenario A: gathering is a free-tier opportunity and needs a tool ------------------

    @GameTest(maxTicks = 40)
    public void choresNeverOffersGatheringButFreeDoes(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setWorkRadius(6);
        companion.backpack().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        companion.setOwner(owner);
        ChestBlockEntity chest = chest(helper, new BlockPos(3, 1, 3));
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        long now = now();
        for (int index = 0; index < OpportunityScanner.MIN_PLACEMENTS; index++) {
            PlacementTracker.record(owner.getUUID(), "minecraft:cobblestone", now);
        }
        try {
            if (has(scan(helper, companion, PlanGate.Policy.chores()), "gather_building")) {
                throw helper.assertionException("chores must not offer a mining task (§6.2)");
            }
            List<Opportunity> free = scan(helper, companion, PlanGate.Policy.free());
            if (!has(free, "gather_building")) {
                throw helper.assertionException("free should offer gathering: " + free);
            }
            Opportunity gather = free.stream()
                    .filter(row -> row.id().equals("gather_building")).findFirst()
                    .orElseThrow();
            if (gather.priority() != 1
                    || !"minecraft:cobblestone".equals(gather.suggested().get("material"))) {
                throw helper.assertionException("unexpected gathering candidate: "
                        + gather.describe());
            }
        } finally {
            PlacementTracker.forget(owner.getUUID());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void noToolMeansNoGatheringOpportunity(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setWorkRadius(6);
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        companion.setOwner(owner);
        chest(helper, new BlockPos(3, 1, 3)).setItem(0,
                new ItemStack(Items.COBBLESTONE, 2));
        long now = now();
        for (int index = 0; index < OpportunityScanner.MIN_PLACEMENTS; index++) {
            PlacementTracker.record(owner.getUUID(), "minecraft:cobblestone", now);
        }
        try {
            if (has(scan(helper, companion, PlanGate.Policy.free()), "gather_building")) {
                throw helper.assertionException("no pickaxe means no gathering opportunity");
            }
        } finally {
            PlacementTracker.forget(owner.getUUID());
        }
        helper.succeed();
    }

    // ---- arbitration and breakers -----------------------------------------------------------

    @GameTest(maxTicks = 60)
    public void threatPreemptsAutonomyButNotAnOwnerCommand(GameTestHelper helper) {
        CompanionEntity autonomous = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        CompanionEntity commanded = helper.spawn(FabricContent.COMPANION, 5, 2, 5);
        helper.spawn(EntityTypes.ZOMBIE, 4, 2, 4);
        if (!PlanRuntime.submit(autonomous, farGoto(helper, autonomous),
                PlanGate.Policy.commandConfirmed(), PlanRuntime.SOURCE_AUTONOMY).ok()
                || !PlanRuntime.submit(commanded, farGoto(helper, commanded),
                PlanGate.Policy.commandConfirmed(), PlanRuntime.SOURCE_COMMAND).ok()) {
            throw helper.assertionException("the long plans did not start");
        }
        if (!PlanRuntime.isRunning(autonomous.getUUID())
                || !PlanRuntime.isRunning(commanded.getUUID())) {
            throw helper.assertionException("both plans must be running before arbitration");
        }
        long now = now();
        Director.arbitrate(autonomous, now);
        Director.arbitrate(commanded, now);
        try {
            if (PlanRuntime.isRunning(autonomous.getUUID())) {
                throw helper.assertionException("a threat did not stop the autonomous plan");
            }
            if (!PlanRuntime.isRunning(commanded.getUUID())) {
                throw helper.assertionException("a threat stopped the owner's own command");
            }
            String data = CompanionRuntimeState.eventBus()
                    .after(autonomous.getUUID().toString(), 0, 200).stream()
                    .filter(event -> event.kind().equals("autonomy_interrupted"))
                    .map(CompanionEvent::data).findFirst().orElse("");
            if (!data.contains("threat nearby")) {
                throw helper.assertionException("no interruption event: " + data);
            }
        } finally {
            PlanRuntime.cancel(autonomous.getUUID(), "test finished");
            PlanRuntime.cancel(commanded.getUUID(), "test finished");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aHurtOwnerPausesAutonomyForFifteenMinutes(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();
        companion.setOwner(owner);
        long now = now();
        Director.noteHurt(owner.getUUID(), now);
        Director.arbitrate(companion, now);
        if (!Director.state(companion.getUUID()).budget().paused(now)) {
            throw helper.assertionException("a hurt owner did not trip the pause");
        }
        if (!Director.evaluate(companion, now).isEmpty()) {
            throw helper.assertionException("candidates were offered right after a hit");
        }
        long later = now + 15 * 60 * 1_000L + 1_000L;
        Director.evaluate(companion, later);
        if (Director.state(companion.getUUID()).lastReason().contains("paused")) {
            throw helper.assertionException("the hurt pause lasted longer than 15 minutes");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aCriticallyHurtCompanionPausesAutonomy(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setHealth(companion.getMaxHealth() * 0.2F);
        long now = now();
        Director.arbitrate(companion, now);
        if (!Director.state(companion.getUUID()).budget().paused(now)) {
            throw helper.assertionException("low health did not trip the 15-minute pause");
        }
        if (!Director.evaluate(companion, now).isEmpty()) {
            throw helper.assertionException("a hurt companion was still offered chores");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void operatorPauseStopsEvaluationsUntilResumed(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        long now = now();
        try {
            Director.pauseGlobally(now, 60_000L, "paused by test");
            if (!Director.evaluate(companion, now).isEmpty()
                    || !Director.state(companion.getUUID()).lastReason()
                            .equals("paused by test")) {
                throw helper.assertionException("the operator pause was ignored");
            }
        } finally {
            Director.resumeGlobally();
        }
        Director.evaluate(companion, now);
        if (Director.state(companion.getUUID()).lastReason().contains("paused by test")) {
            throw helper.assertionException("the pause outlived /tlmm ai resume");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void anAutonomyPlanMustAnswerTheCurrentRound(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setWorkRadius(6);
        ready(companion);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 12));
        matureCrops(helper, OpportunityScanner.MIN_MATURE_CROPS);
        long now = now();
        if (!has(Director.evaluate(companion, now), "harvest_farm")) {
            throw helper.assertionException("the director did not open a round; reason="
                    + Director.state(companion.getUUID()).lastReason());
        }
        if (Director.consumeOpportunity(companion.getUUID(), "made_up_task")) {
            throw helper.assertionException("an unoffered opportunity was accepted");
        }
        if (!Director.consumeOpportunity(companion.getUUID(), "harvest_farm")) {
            throw helper.assertionException("the offered opportunity was refused");
        }
        if (Director.consumeOpportunity(companion.getUUID(), "harvest_farm")) {
            throw helper.assertionException("an opportunity could be spent twice");
        }
        helper.succeed();
    }

    // ---- the newly whitelisted pickup module ------------------------------------------------

    @GameTest(maxTicks = 120)
    public void pickupItemsCollectsDropsIntoTheBackpack(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        for (int index = 0; index < 3; index++) {
            helper.spawnItem(Items.COBBLESTONE, 3.0F + index, 2.0F, 3.0F);
        }
        ParsedPlan plan;
        try {
            plan = PlanJson.parse("{\"version\":1,\"steps\":[{\"module\":\"pickup_items\","
                    + "\"params\":{\"radius\":6,\"max\":8}}]}");
        } catch (PlanFormatException exception) {
            throw helper.assertionException("plan json rejected: " + exception.code());
        }
        if (!PlanRuntime.submit(companion, plan, PlanGate.Policy.chores()).ok()) {
            throw helper.assertionException("a chores plan could not use pickup_items");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("the pickup plan never finished");
            }
            int total = 0;
            for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
                ItemStack stack = companion.backpack().getItem(slot);
                if (stack.is(Items.COBBLESTONE)) {
                    total += stack.getCount();
                }
            }
            if (total != 3) {
                throw helper.assertionException("expected 3 collected items, got " + total);
            }
            AABB box = companion.getBoundingBox().inflate(6, 4, 6);
            List<ItemEntity> left = companion.level().getEntitiesOfClass(ItemEntity.class, box);
            if (!left.isEmpty()) {
                throw helper.assertionException(left.size() + " drop(s) were left behind");
            }
        });
    }
}
