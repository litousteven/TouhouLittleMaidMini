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
        return registryFor(helper, new FabricCompanionBackend(helper.getLevel().getServer()));
    }

    private static ToolRegistry registryFor(GameTestHelper helper,
            FabricCompanionBackend backend) {
        return CompanionTools.registry(backend);
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
    public void stopAndEventPollingWorkOnARealCompanion(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        FabricCompanionBackend backend = new FabricCompanionBackend(
                helper.getLevel().getServer());
        ToolRegistry registry = registryFor(helper, backend);
        String id = companion.getUUID().toString();

        ToolResult stopped = registry.invoke("companion_stop", Map.of("companion_id", id));
        if (stopped.status() != ToolResult.Status.OK) {
            throw helper.assertionException("companion_stop failed: " + text(stopped));
        }

        ToolResult events = registry.invoke("companion_wait_events", Map.of(
                "companion_id", id, "after_seq", 0, "max_events", 10));
        if (events.status() != ToolResult.Status.OK) {
            throw helper.assertionException("companion_wait_events failed: " + text(events));
        }
        if (!events.message().contains("operation_stopped")) {
            throw helper.assertionException("stop must publish an operation_stopped event: "
                    + events.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void behaviorChangePublishesAnEvent(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        FabricCompanionBackend backend = new FabricCompanionBackend(
                helper.getLevel().getServer());
        ToolRegistry registry = registryFor(helper, backend);
        String id = companion.getUUID().toString();

        registry.invoke("companion_set_behavior", Map.of(
                "companion_id", id, "behavior", "follow"));

        ToolResult events = registry.invoke("companion_wait_events", Map.of(
                "companion_id", id, "after_seq", 0, "max_events", 10));
        if (!events.message().contains("behavior_changed")) {
            throw helper.assertionException("behaviour change must publish an event: "
                    + events.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void companionHarvestsAFullyGrownCrop(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registryFor(helper);

        var soil = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 3));
        var cropPos = soil.above();
        var level = helper.getLevel();
        level.setBlockAndUpdate(soil, net.minecraft.world.level.block.Blocks.FARMLAND
                .defaultBlockState());
        var wheat = net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7);
        level.setBlockAndUpdate(cropPos, wheat);

        if (!(level.getBlockState(cropPos).getBlock()
                instanceof net.minecraft.world.level.block.CropBlock crop)
                || !crop.isMaxAge(level.getBlockState(cropPos))) {
            throw helper.assertionException("test setup did not create a mature crop");
        }

        ToolResult result = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", cropPos.getX(),
                "y", cropPos.getY(),
                "z", cropPos.getZ()));

        if (result.status() != ToolResult.Status.OK) {
            throw helper.assertionException("harvest failed: " + text(result));
        }
        if (!result.message().contains("wheat")) {
            throw helper.assertionException("harvest did not report the crop: " + result.message());
        }
        if (!level.getBlockState(cropPos).isAir()) {
            throw helper.assertionException("harvested crop should be removed from the field");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void immatureCropIsRefusedWithNotReady(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registryFor(helper);

        var soil = helper.absolutePos(new net.minecraft.core.BlockPos(2, 1, 3));
        var cropPos = soil.above();
        var level = helper.getLevel();
        level.setBlockAndUpdate(soil, net.minecraft.world.level.block.Blocks.FARMLAND
                .defaultBlockState());
        level.setBlockAndUpdate(cropPos,
                net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState());

        ToolResult result = registry.invoke("companion_interact", Map.of(
                "companion_id", companion.getUUID().toString(),
                "x", cropPos.getX(),
                "y", cropPos.getY(),
                "z", cropPos.getZ()));

        if (result.status() != ToolResult.Status.REJECTED
                || !result.code().equals("not_ready")) {
            throw helper.assertionException("immature crop must be refused with not_ready: "
                    + text(result));
        }
        if (level.getBlockState(cropPos).isAir()) {
            throw helper.assertionException("immature crop must not be destroyed");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void contextReportsNearbyBlocks(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ToolRegistry registry = registryFor(helper);

        ToolResult result = registry.invoke("companion_context", Map.of(
                "companion_id", companion.getUUID().toString(),
                "radius", 2));
        if (result.status() != ToolResult.Status.OK) {
            throw helper.assertionException("companion_context failed: " + text(result));
        }
        if (!result.message().contains("\"scanned\"")) {
            throw helper.assertionException("context must report how much it scanned: "
                    + result.message());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void unknownCompanionIsRefusedNotCrashed(GameTestHelper helper) {
        ToolRegistry registry = registryFor(helper);
        String missing = UUID.randomUUID().toString();

        Map<String, Map<String, Object>> calls = new java.util.LinkedHashMap<>();
        calls.put("companion_move", Map.of("companion_id", missing, "x", 0, "y", 0, "z", 0));
        calls.put("companion_interact", Map.of("companion_id", missing, "x", 0, "y", 0, "z", 0));
        calls.put("companion_context", Map.of("companion_id", missing, "radius", 1));
        calls.put("companion_inventory", Map.of("companion_id", missing, "slot", 0));
        calls.put("companion_set_behavior", Map.of("companion_id", missing, "behavior", "stay"));
        calls.put("companion_stop", Map.of("companion_id", missing));
        calls.put("companion_wait_events", Map.of("companion_id", missing, "after_seq", 0));
        calls.put("companion_say", Map.of("companion_id", missing, "text", "hi"));

        for (Map.Entry<String, Map<String, Object>> call : calls.entrySet()) {
            ToolResult result = registry.invoke(call.getKey(), call.getValue());
            if (result.status() != ToolResult.Status.REJECTED
                    || !result.code().equals("unknown_companion")) {
                throw helper.assertionException(call.getKey()
                        + " must refuse an unknown companion with unknown_companion: "
                        + text(result));
            }
        }
        helper.succeed();
    }
}
