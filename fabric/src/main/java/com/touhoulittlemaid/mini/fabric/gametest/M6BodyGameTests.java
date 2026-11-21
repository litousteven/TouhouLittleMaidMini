package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionEvent;
import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** M6.1: the six body abilities, each with its refusal code. */
public final class M6BodyGameTests {

    private static ParsedPlan parse(GameTestHelper helper, String json) {
        try {
            return PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            throw helper.assertionException(
                    "plan json rejected: " + exception.code() + " " + exception.getMessage());
        }
    }

    private static PlanRuntime.SubmitResult submit(CompanionEntity companion, ParsedPlan plan) {
        return PlanRuntime.submit(companion, plan, PlanGate.Policy.commandConfirmed());
    }

    private static String finishedData(CompanionEntity companion) {
        return CompanionRuntimeState.eventBus()
                .after(companion.getUUID().toString(), 0, 300).stream()
                .filter(event -> event.kind().equals("plan_finished"))
                .reduce((first, second) -> second)
                .map(CompanionEvent::data)
                .orElse("");
    }

    private static String digJson(BlockPos pos) {
        return "{\"version\":1,\"steps\":[{\"module\":\"dig_block\",\"params\":{\"x\":"
                + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}}]}";
    }

    private static ChestBlockEntity chestAt(GameTestHelper helper, BlockPos relative) {
        BlockPos absolute = helper.absolutePos(relative);
        return (ChestBlockEntity) helper.getLevel().getBlockEntity(absolute);
    }

    @GameTest(maxTicks = 100)
    public void bareHandCannotBreakStone(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos relative = new BlockPos(3, 1, 2);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);

        if (!submit(companion, parse(helper, digJson(target))).ok()) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains("\"code\":\"tool_not_in_inventory\"")) {
                throw helper.assertionException("expected tool_not_in_inventory, got " + data);
            }
            if (!helper.getLevel().getBlockState(target).is(Blocks.STONE)) {
                throw helper.assertionException("stone was broken without a tool");
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void woodenPickCannotMineIron(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        BlockPos relative = new BlockPos(3, 1, 2);
        helper.setBlock(relative, Blocks.IRON_ORE);
        BlockPos target = helper.absolutePos(relative);

        if (!submit(companion, parse(helper, digJson(target))).ok()) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains("\"code\":\"wrong_tool\"")) {
                throw helper.assertionException("expected wrong_tool, got " + data);
            }
            if (!helper.getLevel().getBlockState(target).is(Blocks.IRON_ORE)) {
                throw helper.assertionException("iron ore was broken with a wooden pickaxe");
            }
        });
    }

    @GameTest(maxTicks = 160)
    public void stonePickBreaksStoneAndDropsIt(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
        BlockPos relative = new BlockPos(3, 1, 2);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);

        if (!submit(companion, parse(helper, digJson(target))).ok()) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains("\"ok\":true")) {
                throw helper.assertionException("stone was not broken: " + data);
            }
            if (!helper.getLevel().getBlockState(target).isAir()) {
                throw helper.assertionException("the block is still there");
            }
            if (helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    companion.getBoundingBox().inflate(8.0D)).isEmpty()) {
                throw helper.assertionException("no drop appeared");
            }
            ItemStack held = companion.getMainHandItem();
            if (held.getDamageValue() != 1) {
                throw helper.assertionException(
                        "pickaxe durability should drop by one, was " + held.getDamageValue());
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void toolRemovedMidDigReportsToolLost(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_PICKAXE));
        BlockPos relative = new BlockPos(3, 1, 2);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);

        if (!submit(companion, parse(helper, digJson(target))).ok()) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.runAfterDelay(3, () -> companion.setItemSlot(EquipmentSlot.MAINHAND,
                ItemStack.EMPTY));
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains("\"code\":\"tool_lost\"")) {
                throw helper.assertionException("expected tool_lost, got " + data);
            }
            if (!helper.getLevel().getBlockState(target).is(Blocks.STONE)) {
                throw helper.assertionException("the block broke after the tool was taken");
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void placeBlockConsumesOneAndRefusesOccupiedSpot(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        companion.backpack().setChanged();

        BlockPos free = helper.absolutePos(new BlockPos(4, 2, 2));
        String placeJson = "{\"version\":1,\"steps\":[{\"module\":\"place_block\",\"params\":"
                + "{\"x\":" + free.getX() + ",\"y\":" + free.getY() + ",\"z\":" + free.getZ()
                + ",\"item\":\"minecraft:cobblestone\"}}]}";
        if (!submit(companion, parse(helper, placeJson)).ok()) {
            throw helper.assertionException("place plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            if (!helper.getLevel().getBlockState(free).is(Blocks.COBBLESTONE)) {
                throw helper.assertionException("the block was not placed");
            }
            if (companion.backpack().getItem(0).getCount() != 3) {
                throw helper.assertionException("placing must consume exactly one item");
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void placingIntoAnOccupiedSpotIsRefused(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        companion.backpack().setChanged();
        BlockPos occupied = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.DIRT);

        String placeJson = "{\"version\":1,\"steps\":[{\"module\":\"place_block\",\"params\":"
                + "{\"x\":" + occupied.getX() + ",\"y\":" + occupied.getY()
                + ",\"z\":" + occupied.getZ()
                + ",\"item\":\"minecraft:cobblestone\"}}]}";
        if (!submit(companion, parse(helper, placeJson)).ok()) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains("\"code\":\"blocked\"")) {
                throw helper.assertionException("expected blocked, got " + data);
            }
            if (companion.backpack().getItem(0).getCount() != 4) {
                throw helper.assertionException("a refused placement must not consume the item");
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void withdrawKeepsTheItemCount(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.CHEST);
        ChestBlockEntity chest = chestAt(helper, new BlockPos(3, 1, 2));
        if (chest == null) {
            throw helper.assertionException("chest block entity is missing");
        }
        chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 2));

        String plan = "{\"version\":1,\"steps\":[{\"module\":\"withdraw_items\",\"params\":"
                + "{\"x\":" + chestPos.getX() + ",\"y\":" + chestPos.getY()
                + ",\"z\":" + chestPos.getZ()
                + ",\"item\":\"minecraft:cobblestone\",\"count\":64}}]}";
        if (!submit(companion, parse(helper, plan)).ok()) {
            throw helper.assertionException("withdraw plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            int carried = 0;
            for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
                ItemStack stack = companion.backpack().getItem(slot);
                if (stack.is(Items.COBBLESTONE)) {
                    carried += stack.getCount();
                }
            }
            int left = chest.getItem(0).getCount();
            if (carried != 64 || left != 0) {
                throw helper.assertionException(
                        "counts not conserved: carried=" + carried + " chest=" + left);
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void depositAndSlotMoveKeepCounts(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
        companion.backpack().setChanged();
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.CHEST);
        ChestBlockEntity chest = chestAt(helper, new BlockPos(3, 1, 2));
        if (chest == null) {
            throw helper.assertionException("chest block entity is missing");
        }
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 2));

        String deposit = "{\"version\":1,\"steps\":[{\"module\":\"deposit_items\",\"params\":"
                + "{\"x\":" + chestPos.getX() + ",\"y\":" + chestPos.getY()
                + ",\"z\":" + chestPos.getZ()
                + ",\"item\":\"minecraft:oak_planks\",\"count\":6}}]}";
        if (!submit(companion, parse(helper, deposit)).ok()) {
            throw helper.assertionException("deposit plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            int carried = companion.backpack().getItem(0).getCount();
            int stored = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                if (chest.getItem(slot).is(Items.OAK_PLANKS)) {
                    stored += chest.getItem(slot).getCount();
                }
            }
            if (carried != 4 || stored != 6) {
                throw helper.assertionException("deposit lost items: carried=" + carried
                        + " stored=" + stored);
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void moveSlotTransfersExactlyOneSlot(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.CHEST);
        ChestBlockEntity chest = chestAt(helper, new BlockPos(3, 1, 2));
        if (chest == null) {
            throw helper.assertionException("chest block entity is missing");
        }
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 9));
        BlockPos chestPos = helper.absolutePos(new BlockPos(3, 1, 2));

        String plan = "{\"version\":1,\"steps\":[{\"module\":\"move_slot\",\"params\":{"
                + "\"from_x\":" + chestPos.getX() + ",\"from_y\":" + chestPos.getY()
                + ",\"from_z\":" + chestPos.getZ() + ",\"from_slot\":0,"
                + "\"to_slot\":2,\"count\":5}}]}";
        if (!submit(companion, parse(helper, plan)).ok()) {
            throw helper.assertionException("move_slot plan was rejected");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            int inChest = chest.getItem(0).getCount();
            int inBag = companion.backpack().getItem(2).getCount();
            if (inChest != 4 || inBag != 5) {
                throw helper.assertionException("move_slot counts wrong: chest=" + inChest
                        + " bag=" + inBag);
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void craftPlanksAndChestFromMaterials(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.OAK_LOG, 1));
        companion.backpack().setChanged();

        String planks = "{\"version\":1,\"steps\":[{\"module\":\"craft_item\",\"params\":"
                + "{\"item\":\"minecraft:oak_planks\",\"count\":1}}]}";
        if (!submit(companion, parse(helper, planks)).ok()) {
            throw helper.assertionException("craft planks was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            int planksCarried = 0;
            for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
                if (companion.backpack().getItem(slot).is(Items.OAK_PLANKS)) {
                    planksCarried += companion.backpack().getItem(slot).getCount();
                }
            }
            if (planksCarried != 4) {
                throw helper.assertionException("expected 4 planks, got " + planksCarried
                        + " (" + data + ")");
            }
        });
    }

    @GameTest(maxTicks = 120)
    public void craftChestUsesTheThreeByThreeGrid(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
        companion.backpack().setChanged();

        String plan = "{\"version\":1,\"steps\":[{\"module\":\"craft_item\",\"params\":"
                + "{\"item\":\"minecraft:chest\",\"count\":1}}]}";
        if (!submit(companion, parse(helper, plan)).ok()) {
            throw helper.assertionException("craft chest was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            boolean hasChest = false;
            for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
                if (companion.backpack().getItem(slot).is(Items.CHEST)) {
                    hasChest = true;
                }
            }
            if (!hasChest) {
                throw helper.assertionException("no chest was crafted: "
                        + finishedData(companion));
            }
        });
    }

    @GameTest(maxTicks = 80)
    public void craftWithoutMaterialsIsExplicit(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        String plan = "{\"version\":1,\"steps\":[{\"module\":\"craft_item\",\"params\":"
                + "{\"item\":\"minecraft:diamond_sword\",\"count\":1}}]}";
        if (!submit(companion, parse(helper, plan)).ok()) {
            throw helper.assertionException("plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            if (!data.contains(FailureCodes.MISSING_ITEM)) {
                throw helper.assertionException("expected missing_item, got " + data);
            }
        });
    }

    @GameTest(maxTicks = 400)
    public void smeltIronInAFurnace(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.backpack().setItem(0, new ItemStack(Items.RAW_IRON, 2));
        companion.backpack().setItem(1, new ItemStack(Items.COAL, 2));
        companion.backpack().setChanged();
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.FURNACE);
        BlockPos furnacePos = helper.absolutePos(new BlockPos(3, 1, 2));

        String plan = "{\"version\":1,\"steps\":[{\"module\":\"smelt_items\",\"params\":{"
                + "\"x\":" + furnacePos.getX() + ",\"y\":" + furnacePos.getY()
                + ",\"z\":" + furnacePos.getZ()
                + ",\"input\":\"minecraft:raw_iron\",\"fuel\":\"minecraft:coal\","
                + "\"count\":1}}]}";
        if (!submit(companion, parse(helper, plan)).ok()) {
            throw helper.assertionException("smelt plan was rejected before running");
        }
        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            String data = finishedData(companion);
            int ingots = 0;
            for (int slot = 0; slot < companion.backpack().getContainerSize(); slot++) {
                if (companion.backpack().getItem(slot).is(Items.IRON_INGOT)) {
                    ingots += companion.backpack().getItem(slot).getCount();
                }
            }
            if (ingots < 1) {
                throw helper.assertionException("no iron ingot was produced: " + data);
            }
        });
    }
}
