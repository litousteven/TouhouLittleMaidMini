package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.ToolRegistry;

import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * End-to-end M2 coverage: the same registry the MCP transport uses, driven against a
 * real server thread and a real companion entity.
 */
public final class M2ToolPipelineGameTests {

    private static ToolRegistry registryFor(GameTestHelper helper) {
        return CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));
    }

    private static String text(ToolResult result) {
        return result.status() + ":" + result.code() + ":" + result.message();
    }

    @GameTest(maxTicks = 100)
    public void listAndStateReflectARealCompanion(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setCompanionName("Marisa");
        companion.setBehavior(LocalBehavior.STAY);
        ToolRegistry registry = registryFor(helper);

        ToolResult listed = registry.invoke("companion_list", Map.of());
        if (listed.status() != ToolResult.Status.OK || !listed.message().contains("Marisa")) {
            throw helper.assertionException("companion_list did not report the real companion: "
                    + text(listed));
        }

        ToolResult state = registry.invoke("companion_state",
                Map.of("companion_id", companion.getUUID().toString()));
        if (state.status() != ToolResult.Status.OK || !state.message().contains("stay")) {
            throw helper.assertionException("companion_state did not report real state: "
                    + text(state));
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void setBehaviorReachesTheEntity(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registryFor(helper);

        ToolResult result = registry.invoke("companion_set_behavior", Map.of(
                "companion_id", companion.getUUID().toString(),
                "behavior", "guard"));
        if (result.status() != ToolResult.Status.OK) {
            throw helper.assertionException("companion_set_behavior failed: " + text(result));
        }
        if (companion.behavior() != LocalBehavior.GUARD) {
            throw helper.assertionException("the entity did not receive the behaviour change");
        }

        CompanionSnapshot snapshot = companion.snapshot();
        if (snapshot.behavior() != LocalBehavior.GUARD) {
            throw helper.assertionException("snapshot did not reflect the new behaviour");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void inventoryToolReadsBackpackSlots(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(4, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.WHEAT, 5));
        ToolRegistry registry = registryFor(helper);

        ToolResult empty = registry.invoke("companion_inventory", Map.of(
                "companion_id", companion.getUUID().toString(),
                "slot", 0));
        if (empty.status() != ToolResult.Status.OK || !empty.code().equals("empty_slot")) {
            throw helper.assertionException("empty slot should report empty_slot: " + text(empty));
        }

        ToolResult filled = registry.invoke("companion_inventory", Map.of(
                "companion_id", companion.getUUID().toString(),
                "slot", 4));
        if (filled.status() != ToolResult.Status.OK
                || !filled.message().contains("5")
                || !filled.message().contains("wheat")) {
            throw helper.assertionException("filled slot was not reported: " + text(filled));
        }

        ToolResult outOfRange = registry.invoke("companion_inventory", Map.of(
                "companion_id", companion.getUUID().toString(),
                "slot", 99));
        if (outOfRange.status() != ToolResult.Status.REJECTED) {
            throw helper.assertionException("out-of-range slot must be refused: " + text(outOfRange));
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void stopAndWaitWorkOnARealCompanion(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registryFor(helper);
        String id = companion.getUUID().toString();

        ToolResult stopped = registry.invoke("companion_stop", Map.of("companion_id", id));
        if (stopped.status() != ToolResult.Status.OK) {
            throw helper.assertionException("companion_stop failed: " + text(stopped));
        }

        ToolResult waited = registry.invoke("companion_wait", Map.of(
                "companion_id", id, "ticks", 10));
        if (waited.status() != ToolResult.Status.OK) {
            throw helper.assertionException("companion_wait failed: " + text(waited));
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void unknownCompanionIsRefusedNotCrashed(GameTestHelper helper) {
        ToolRegistry registry = registryFor(helper);
        String missing = UUID.randomUUID().toString();

        for (String tool : new String[] {
                "companion_move", "companion_interact", "companion_inventory",
                "companion_set_behavior", "companion_stop", "companion_wait"}) {
            Map<String, Object> arguments = new java.util.HashMap<>();
            arguments.put("companion_id", missing);
            arguments.put("x", 0);
            arguments.put("y", 0);
            arguments.put("z", 0);
            arguments.put("slot", 0);
            arguments.put("behavior", "stay");
            arguments.put("ticks", 1);

            ToolResult result = registry.invoke(tool, arguments);
            if (result.status() != ToolResult.Status.REJECTED) {
                throw helper.assertionException(tool + " must refuse an unknown companion: "
                        + text(result));
            }
        }
        helper.succeed();
    }
}
