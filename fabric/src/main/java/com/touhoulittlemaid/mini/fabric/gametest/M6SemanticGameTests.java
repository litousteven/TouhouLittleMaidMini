package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionEvent;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeStore;
import com.touhoulittlemaid.mini.core.semantic.SemanticSource;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.fabric.semantic.CropShape;
import com.touhoulittlemaid.mini.fabric.semantic.SemanticService;

import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;

/** M6.3: the semantic resolver, heuristics and knowledge files changing real behaviour. */
public final class M6SemanticGameTests {

    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    private static String unknownEvents(CompanionEntity companion) {
        return CompanionRuntimeState.eventBus()
                .after(companion.getUUID().toString(), 0, 400).stream()
                .filter(event -> event.kind().equals("unknown_category"))
                .map(CompanionEvent::data)
                .reduce("", (first, second) -> second);
    }

    private static ParsedPlan parse(GameTestHelper helper, String json) {
        try {
            return PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            throw helper.assertionException("plan json rejected: " + exception.code());
        }
    }

    @GameTest(maxTicks = 40)
    public void heuristicsClassifyVanillaAndTestCrops(GameTestHelper helper) {
        var wheat = SemanticService.resolve(Blocks.WHEAT.defaultBlockState());
        if (!wheat.has(SemanticTag.CROP)) {
            throw helper.assertionException("wheat was not classified as a crop: " + wheat);
        }
        if (wheat.source() != SemanticSource.TAG) {
            throw helper.assertionException(
                    "wheat should come from the crops tag, got " + wheat.source());
        }

        if (M4TestCrops.block == null) {
            throw helper.assertionException("test crop was not registered");
        }
        var testCrop = SemanticService.resolve(M4TestCrops.block.defaultBlockState());
        if (!testCrop.has(SemanticTag.CROP)) {
            throw helper.assertionException("test crop was not classified: " + testCrop);
        }
        if (testCrop.source() != SemanticSource.HEURISTIC) {
            throw helper.assertionException(
                    "a CropBlock outside the crops tag must be heuristic, got "
                            + testCrop.source());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void unknownAgeBlockIsReportedAndNotHarvested(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setBehavior(LocalBehavior.FARM);
        BlockPos soil = new BlockPos(3, 1, 2);
        helper.setBlock(soil, Blocks.DIRT);
        helper.setBlock(soil.above(), Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE,
                        net.minecraft.world.level.block.SweetBerryBushBlock.MAX_AGE));
        BlockPos bush = helper.absolutePos(soil.above());

        var resolution = SemanticService.resolve(Blocks.SWEET_BERRY_BUSH.defaultBlockState());
        if (resolution.isKnown()) {
            throw helper.assertionException(
                    "sweet berry bush is classified, the unknown path cannot be tested: "
                            + resolution);
        }

        // Drive the goal directly: the goal selector's timing is not what this test is
        // about, and waiting on it made the test flaky.
        companion.tickCount = 40;
        var goal = new com.touhoulittlemaid.mini.fabric.entity.goal.FarmGoal(companion);
        if (goal.canUse()) {
            throw helper.assertionException("an unclassified block must not become a target");
        }
        String data = unknownEvents(companion);
        if (!data.contains("minecraft:sweet_berry_bush")) {
            throw helper.assertionException("no unknown_category event was published; "
                    + "resolution=" + SemanticService.resolve(
                            helper.getLevel().getBlockState(bush)));
        }
        if (!helper.getLevel().getBlockState(bush).is(Blocks.SWEET_BERRY_BUSH)) {
            throw helper.assertionException("an unclassified block must not be harvested");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void knowledgeEntryMakesUnknownBlockFarmable(GameTestHelper helper) {
        Path directory = gameDir().resolve("config").resolve("tlmm")
                .resolve("knowledge-gametest").resolve("file");
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("minecraft.json"), """
                    {"namespace":"minecraft","updated_at":"2026-01-01T00:00:00Z","entries":{
                      "minecraft:nether_wart":{"kind":"block","tags":["crop"],
                        "source":"model","confidence":0.9,"batch":"gametest",
                        "reason":"age property, harvestable"}},
                     "pending":{}}
                    """);
        } catch (java.io.IOException exception) {
            throw helper.assertionException("could not write the knowledge file: "
                    + exception.getMessage());
        }
        // Read the file into a local store: reloading the process-wide one would wipe the
        // state of every other test running in this batch.
        var facts = new com.touhoulittlemaid.mini.fabric.semantic.FabricRealFacts();
        KnowledgeStore local = new KnowledgeStore();
        KnowledgeStore.LoadReport loaded = local.load(directory, facts);
        if (loaded.accepted() != 1) {
            throw helper.assertionException("knowledge entry was not accepted: " + loaded);
        }
        var fileResolution = com.touhoulittlemaid.mini.core.semantic.SemanticTags.resolve(
                "minecraft:nether_wart", com.touhoulittlemaid.mini.core.semantic.SemanticKind.BLOCK,
                facts, local);
        if (!fileResolution.has(SemanticTag.CROP)
                || fileResolution.source() != SemanticSource.MODEL) {
            throw helper.assertionException("the file did not classify nether wart: "
                    + fileResolution);
        }

        // The same line, applied to the live store, is what the farm goal actually reads.
        com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService.store().put(
                new com.touhoulittlemaid.mini.core.semantic.SemanticEntry("minecraft:nether_wart",
                        com.touhoulittlemaid.mini.core.semantic.SemanticKind.BLOCK,
                        java.util.Set.of(SemanticTag.CROP), SemanticSource.MODEL, 0.9D,
                        "gametest", "age property, harvestable"));
        var resolution = SemanticService.resolve(Blocks.NETHER_WART.defaultBlockState());
        if (!resolution.has(SemanticTag.CROP) || resolution.source() != SemanticSource.MODEL) {
            throw helper.assertionException("nether wart was not classified: " + resolution);
        }

        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos soil = new BlockPos(3, 1, 3);
        helper.setBlock(soil, Blocks.SOUL_SAND);
        helper.setBlock(soil.above(), Blocks.NETHER_WART.defaultBlockState()
                .setValue(NetherWartBlock.AGE, NetherWartBlock.MAX_AGE));
        BlockPos wart = helper.absolutePos(soil.above());

        ParsedPlan plan = parse(helper, "{\"version\":1,\"steps\":[{\"module\":"
                + "\"harvest_area\",\"params\":{\"radius\":4,\"replant\":false}}]}");
        var submitted = PlanRuntime.submit(companion, plan, PlanGate.Policy.commandConfirmed());
        if (!submitted.ok()) {
            throw helper.assertionException("harvest plan was rejected: "
                    + submitted.code() + " " + submitted.message());
        }

        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan still running");
            }
            if (!helper.getLevel().getBlockState(wart).isAir()) {
                var state = helper.getLevel().getBlockState(wart);
                throw helper.assertionException("the classified block was not harvested; state="
                        + state + " cropLike=" + CropShape.looksLikeCrop(state)
                        + " mature=" + CropShape.isMature(state)
                        + " resolution=" + SemanticService.resolve(state));
            }
            com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService
                    .forgetEntry("minecraft:nether_wart");
        });
    }

    @GameTest(maxTicks = 40)
    public void vanillaCropsRemainHarvestableWithoutKnowledge(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(3, 2, 2), Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        var resolution = SemanticService.resolve(
                helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(3, 2, 2))));
        if (!resolution.has(SemanticTag.CROP)) {
            throw helper.assertionException("wheat block was not recognised: " + resolution);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void knowledgeDirectoryIsUnderConfig(GameTestHelper helper) {
        Path path = SemanticService.knowledgeDirectory(gameDir());
        if (!path.endsWith(Path.of("config", "tlmm", "knowledge"))) {
            throw helper.assertionException("unexpected knowledge directory: " + path);
        }
        helper.succeed();
    }
}
