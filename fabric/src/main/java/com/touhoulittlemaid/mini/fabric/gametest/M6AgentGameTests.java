package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.core.plan.PlanRunner;
import com.touhoulittlemaid.mini.fabric.agent.AgentObserver;
import com.touhoulittlemaid.mini.fabric.agent.AgentService;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;

import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** M6.4: what the client brain is allowed to see, and what it learns when a plan ends. */
public final class M6AgentGameTests {

    private static ParsedPlan parse(GameTestHelper helper, String json) {
        try {
            return PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            throw helper.assertionException(
                    "plan json was rejected: " + exception.code() + " " + exception.getMessage());
        }
    }

    @GameTest(maxTicks = 40)
    public void observationCarriesTheNumbersAPlanNeeds(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setBehavior(LocalBehavior.FARM);
        companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 12));
        companion.backpack().setItem(1, new ItemStack(Items.IRON_PICKAXE));

        String json = AgentObserver.observe(companion, "status");
        for (String expected : new String[] {"\"companion\"", "\"behavior\":\"farm\"",
                "\"backpack_used\":2", "\"backpack_slots\":9", "\"minecraft:wheat_seeds\"",
                "\"count\":12", "\"minecraft:iron_pickaxe\"", "\"mature_crops\"",
                "\"hostiles\"", "\"dimension\":\"minecraft:", "\"position\""}) {
            if (!json.contains(expected)) {
                throw helper.assertionException(
                        "observation is missing " + expected + ": " + json);
            }
        }
        // Armour and equipment are folded into the same item list, so the same stack can
        // appear once with a summed count instead of twice.
        if (json.contains("\"count\":1,\"count\"")) {
            throw helper.assertionException("items were not merged by id: " + json);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void observationCategoryNarrowsThePayload(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        String inventory = AgentObserver.observe(companion, "inventory");
        if (inventory.contains("\"nearby\"") || !inventory.contains("\"items\"")) {
            throw helper.assertionException("inventory mode leaked the world scan: " + inventory);
        }
        String world = AgentObserver.observe(companion, "world");
        if (!world.contains("\"nearby\"") || world.contains("\"items\"")) {
            throw helper.assertionException("world mode leaked the inventory: " + world);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void capabilityLineFollowsTheBackpack(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        String bare = AgentObserver.capabilityLine(companion);
        if (bare.contains("mine") || bare.contains("till") || !bare.contains("harvest")) {
            throw helper.assertionException("unexpected bare capabilities: " + bare);
        }
        companion.backpack().setItem(0, new ItemStack(Items.IRON_PICKAXE));
        companion.backpack().setItem(1, new ItemStack(Items.IRON_HOE));
        String armed = AgentObserver.capabilityLine(companion);
        if (!armed.contains("mine") || !armed.contains("till")) {
            throw helper.assertionException("tools were not noticed: " + armed);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void completionListenerGetsTheSummary(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        AtomicReference<PlanRunner.Summary> seen = new AtomicReference<>();
        AtomicReference<String> seenPlan = new AtomicReference<>();
        PlanRuntime.addCompletionListener((companionId, planId, summary) -> {
            if (companionId.equals(companion.getUUID())) {
                seen.set(summary);
                seenPlan.set(planId);
            }
        });

        ParsedPlan plan = parse(helper,
                "{\"version\":1,\"steps\":[{\"module\":\"say\",\"params\":{\"text\":\"hi\"}}]}");
        var submitted = PlanRuntime.submit(companion, plan, PlanGate.Policy.command());
        if (!submitted.ok()) {
            throw helper.assertionException("plan rejected: " + submitted.code());
        }
        helper.succeedWhen(() -> {
            PlanRunner.Summary summary = seen.get();
            if (summary == null) {
                throw helper.assertionException("no completion was reported");
            }
            if (!summary.ok()) {
                throw helper.assertionException("summary was not ok: " + summary.code());
            }
            if (seenPlan.get() == null || !seenPlan.get().startsWith("plan-")) {
                throw helper.assertionException("plan id was not reported: " + seenPlan.get());
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void disconnectStopsTheOwnedPlan(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        CompanionEntity owner = helper.spawn(FabricContent.COMPANION, 5, 2, 2);
        companion.setOwner(owner);
        var start = companion.blockPosition();
        ParsedPlan plan = parse(helper, "{\"version\":1,\"steps\":[{\"module\":\"goto\","
                + "\"params\":{\"x\":" + (start.getX() + 30) + ",\"y\":" + start.getY()
                + ",\"z\":" + start.getZ() + ",\"range\":2}}]}");
        var submitted = PlanRuntime.submit(companion, plan, PlanGate.Policy.command());
        if (!submitted.ok()) {
            throw helper.assertionException("plan rejected: " + submitted.code());
        }
        if (!PlanRuntime.isRunning(companion.getUUID())) {
            throw helper.assertionException("the long plan did not start");
        }
        boolean cancelled = AgentService.cancelOwnedPlan(owner.getUUID(), "owner left");
        if (!cancelled) {
            throw helper.assertionException("disconnect did not find the owned plan");
        }
        if (PlanRuntime.isRunning(companion.getUUID())) {
            throw helper.assertionException("the plan outlived its owner's session");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void snapshotDescribesTheRunningStep(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        var start = companion.blockPosition();
        ParsedPlan plan = parse(helper, "{\"version\":1,\"steps\":[{\"module\":\"goto\","
                + "\"params\":{\"x\":" + (start.getX() + 12) + ",\"y\":" + start.getY()
                + ",\"z\":" + start.getZ() + ",\"range\":2}}]}");
        var submitted = PlanRuntime.submit(companion, plan, PlanGate.Policy.command());
        if (!submitted.ok()) {
            throw helper.assertionException("plan rejected: " + submitted.code());
        }
        helper.succeedWhen(() -> {
            var snapshot = PlanRuntime.snapshot(companion.getUUID());
            if (snapshot.isEmpty()) {
                if (!PlanRuntime.isRunning(companion.getUUID())) {
                    throw helper.assertionException("the plan ended before a snapshot was read");
                }
                throw helper.assertionException("no snapshot while running");
            }
            var value = snapshot.get();
            if (value.steps() != 1 || !"goto".equals(value.module())) {
                throw helper.assertionException("unexpected snapshot: " + value);
            }
        });
    }
}
