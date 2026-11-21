package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatch;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatchJson;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeCandidate;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeCodes;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeExporter;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeIngest;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeStore;
import com.touhoulittlemaid.mini.core.semantic.SemanticKind;
import com.touhoulittlemaid.mini.core.semantic.SemanticTag;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.semantic.FabricRealFacts;
import com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService;
import com.touhoulittlemaid.mini.fabric.semantic.SemanticService;
import com.touhoulittlemaid.mini.fabric.semantic.UnknownContentScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.Vec3;

/**
 * M6.3b: the annotation loop against a real world.
 *
 * <p>GameTests share one server and run in parallel, so the knowledge store is global state.
 * These tests therefore only ever touch their own ids, never reload the store, and use a local
 * store where the pipeline itself is what is being checked. The one end-to-end test cleans up
 * after itself so a rerun starts from the same place.
 *
 * <p>The model is replaced by an explicit JSON answer, which is exactly the shape the client
 * brain will send — so the production path (scan, validate, apply, behave, roll back) is what
 * runs.
 */
public final class M6KnowledgeGameTests {

    private static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    private static Path scratch(String name) {
        return gameDir().resolve("config").resolve("tlmm")
                .resolve("knowledge-gametest").resolve(name);
    }

    private static BlockPos placeCactus(GameTestHelper helper, int x, int y, int z) {
        BlockPos soil = new BlockPos(x, y, z);
        helper.setBlock(soil, Blocks.SAND);
        helper.setBlock(soil.above(), Blocks.CACTUS.defaultBlockState()
                .setValue(CactusBlock.AGE, CactusBlock.MAX_AGE));
        return helper.absolutePos(soil.above());
    }

    @GameTest(maxTicks = 80)
    public void scanReportsUnknownContentAndSkipsTaggedCrops(GameTestHelper helper) {
        BlockPos bush = new BlockPos(4, 2, 2);
        helper.setBlock(new BlockPos(4, 1, 2), Blocks.DIRT);
        helper.setBlock(bush, Blocks.SWEET_BERRY_BUSH.defaultBlockState()
                .setValue(SweetBerryBushBlock.AGE, SweetBerryBushBlock.MAX_AGE));
        BlockPos bushAbs = helper.absolutePos(bush);
        // Two blocks away, on the same sampled lattice, so it is definitely looked at.
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(4, 2, 4), Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));

        UnknownContentScanner.Result scan = UnknownContentScanner.scan(helper.getLevel(),
                Vec3.atCenterOf(bushAbs), List.of());
        if (scan.positions() <= 0) {
            throw helper.assertionException("the scanner sampled nothing");
        }
        if (!SemanticService.resolve(Blocks.WHEAT.defaultBlockState()).isKnown()) {
            throw helper.assertionException("wheat should already be explained by a real tag");
        }
        var candidate = scan.candidates().stream()
                .filter(item -> item.id().equals("minecraft:sweet_berry_bush"))
                .findFirst();
        if (candidate.isEmpty()) {
            throw helper.assertionException("the scan missed an unclassified age block; found="
                    + scan.candidates().stream().map(item -> item.id()).limit(10).toList());
        }
        if (!String.valueOf(candidate.get().data().get("states")).contains("age=0..3")) {
            throw helper.assertionException("the request must carry the age range, got "
                    + candidate.get().data());
        }
        if (scan.candidates().stream().anyMatch(item -> item.id().equals("minecraft:wheat"))) {
            throw helper.assertionException("tagged crops must never be asked about");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void modelProposalMakesUnknownContentHarvestable(GameTestHelper helper) {
        KnowledgeService.ledger().clear();
        KnowledgeService.forgetEntry("minecraft:cactus");

        BlockPos cactus = placeCactus(helper, 4, 1, 2);
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);

        var prepared = KnowledgeService.prepare(helper.getLevel(), Vec3.atCenterOf(cactus),
                List.of(), Instant.now());
        if (!prepared.ok()) {
            throw helper.assertionException("no batch was prepared: " + prepared.describe());
        }
        if (prepared.batch().items().stream()
                .noneMatch(item -> item.id().equals("minecraft:cactus"))) {
            throw helper.assertionException("the batch did not ask about the cactus");
        }

        KnowledgeIngest.IngestReport report = KnowledgeService.ingest(prepared.batch(), """
                {"entries":[{"id":"minecraft:cactus","tags":["crop"],
                  "confidence":0.9,"reason":"has an age property and ripens to a harvest"}]}
                """);
        if (report.accepted() != 1) {
            throw helper.assertionException("the proposal was not applied: " + report.summary()
                    + " " + report.details());
        }
        if (!SemanticService.resolve(Blocks.CACTUS.defaultBlockState())
                .has(SemanticTag.CROP)) {
            throw helper.assertionException("the block is still not a crop: "
                    + SemanticService.resolve(Blocks.CACTUS.defaultBlockState()));
        }

        ParsedPlan plan = parse(helper, "{\"version\":1,\"steps\":[{\"module\":"
                + "\"harvest_area\",\"params\":{\"radius\":4,\"replant\":false}}]}");
        var submitted = PlanRuntime.submit(companion, plan, PlanGate.Policy.commandConfirmed());
        if (!submitted.ok()) {
            throw helper.assertionException("harvest plan was rejected: " + submitted.code()
                    + " " + submitted.message());
        }

        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("harvest plan still running");
            }
            if (!helper.getLevel().getBlockState(cactus).isAir()) {
                throw helper.assertionException("the newly classified block was not harvested; "
                        + "resolution=" + SemanticService.resolve(
                                helper.getLevel().getBlockState(cactus)));
            }
            int removed = KnowledgeService.undo(prepared.batch().id());
            if (removed != 1) {
                throw helper.assertionException("batch rollback removed " + removed + " rows");
            }
            if (SemanticService.resolve(Blocks.CACTUS.defaultBlockState()).isKnown()) {
                throw helper.assertionException("rollback left the entry in force");
            }
            KnowledgeService.forgetEntry("minecraft:cactus");
        });
    }

    @GameTest(maxTicks = 80)
    public void hostileClaimWaitsForThePlayer(GameTestHelper helper) {
        KnowledgeService.ledger().clear();
        KnowledgeService.forgetEntry("minecraft:allay");
        var allay = helper.spawn(EntityTypes.ALLAY, 4, 2, 4);

        var prepared = KnowledgeService.prepare(helper.getLevel(),
                Vec3.atCenterOf(allay.blockPosition()), List.of(), Instant.now());
        if (!prepared.ok()) {
            throw helper.assertionException("no batch was prepared: " + prepared.describe());
        }
        if (prepared.batch().items().stream()
                .noneMatch(item -> item.id().equals("minecraft:allay"))) {
            throw helper.assertionException("the batch did not ask about the allay");
        }

        KnowledgeIngest.IngestReport report = KnowledgeService.ingest(prepared.batch(), """
                {"entries":[{"id":"minecraft:allay","tags":["hostile"],"confidence":0.99}]}
                """);
        if (report.accepted() != 0 || report.pending() != 1) {
            throw helper.assertionException("a hostile claim must not apply directly: "
                    + report.summary());
        }
        if (report.codes().getOrDefault(KnowledgeCodes.HOSTILE_UNCONFIRMED, 0) != 1) {
            throw helper.assertionException("wrong pending code: " + report.codes());
        }
        if (KnowledgeService.store().any("minecraft:allay", SemanticKind.ENTITY).isPresent()) {
            throw helper.assertionException("a pending row leaked into the accepted set");
        }
        if (SemanticService.resolve(allay).has(SemanticTag.HOSTILE)) {
            throw helper.assertionException("guard would target a merely suspected allay");
        }
        if (!KnowledgeService.approve("minecraft:allay")) {
            throw helper.assertionException("the player could not confirm the pending row");
        }
        if (!SemanticService.resolve(allay).has(SemanticTag.HOSTILE)) {
            throw helper.assertionException("a confirmed row did not take effect");
        }
        KnowledgeService.forgetEntry("minecraft:allay");
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void askedContentIsNotAskedTwice(GameTestHelper helper) {
        KnowledgeService.ledger().clear();
        BlockPos cactus = placeCactus(helper, 4, 1, 2);
        Vec3 center = Vec3.atCenterOf(cactus);
        Instant now = Instant.now();

        var first = KnowledgeService.prepare(helper.getLevel(), center, List.of(), now);
        if (!first.ok()) {
            throw helper.assertionException("the first batch was refused: " + first.describe());
        }
        var immediate = KnowledgeService.prepare(helper.getLevel(), center, List.of(), now);
        if (immediate.ok() || !KnowledgeCodes.RATE_LIMITED.equals(immediate.code())) {
            throw helper.assertionException("a second batch within a minute must be refused, got "
                    + immediate.code());
        }
        var later = KnowledgeService.prepare(helper.getLevel(), center, List.of(),
                now.plusSeconds(61));
        if (later.ok()) {
            throw helper.assertionException("already asked content was queued again");
        }
        if (!"nothing_new".equals(later.code())) {
            throw helper.assertionException("expected nothing_new, got " + later.code());
        }
        KnowledgeService.undo(first.batch().id());
        KnowledgeService.ledger().clear();
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void failedPlanPutsItsUnknownContentFirst(GameTestHelper helper) {
        KnowledgeService.ledger().clear();
        KnowledgeService.clearUrgent();
        BlockPos soil = new BlockPos(4, 1, 2);
        helper.setBlock(soil, Blocks.SAND);
        helper.setBlock(soil.above(), Blocks.SUGAR_CANE.defaultBlockState());
        BlockPos cane = helper.absolutePos(soil.above());

        // A plan that just failed on this block queues it; the next batch must ask first.
        KnowledgeService.noteUnknown("minecraft:sugar_cane", SemanticKind.BLOCK);
        var prepared = KnowledgeService.prepare(helper.getLevel(), Vec3.atCenterOf(cane),
                List.of(), Instant.now());
        if (!prepared.ok()) {
            throw helper.assertionException("no batch was prepared: " + prepared.describe());
        }
        String first = prepared.batch().items().get(0).id();
        if (!first.equals("minecraft:sugar_cane")) {
            throw helper.assertionException("the failed content was not queued first, got " + first
                    + " of " + prepared.batch().size());
        }
        KnowledgeService.undo(prepared.batch().id());
        KnowledgeService.clearUrgent();
        KnowledgeService.ledger().clear();
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void exportWritesOnlyItsOwnNamespace(GameTestHelper helper) {
        Path directory = scratch("export");
        try {
            deleteTree(directory);
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw helper.assertionException("cannot prepare " + directory + ": "
                    + exception.getMessage());
        }
        FabricRealFacts facts = new FabricRealFacts();
        KnowledgeStore local = new KnowledgeStore();
        KnowledgeBatch batch = new KnowledgeBatch("kb-export",
                List.of(KnowledgeCandidate.of("minecraft:cactus", SemanticKind.BLOCK,
                        Set.of(), Map.of())));
        var ingest = KnowledgeIngest.ingest(batch, KnowledgeBatchJson.parseResponse("""
                {"entries":[{"id":"minecraft:cactus","tags":["crop"],"confidence":0.9}]}
                """), facts, local);
        if (ingest.accepted() != 1) {
            throw helper.assertionException("the local ingest failed: " + ingest.summary());
        }

        var report = KnowledgeExporter.export(directory, local.entries(), 75);
        if (report.tags() == 0) {
            throw helper.assertionException("nothing was exported: " + report.summary());
        }
        Path tag = directory.resolve("export").resolve("minecraft").resolve("data")
                .resolve("tlmm").resolve("tags").resolve("block").resolve("crops.json");
        if (!Files.isRegularFile(tag)) {
            throw helper.assertionException("missing exported tag file " + tag);
        }
        try {
            String body = Files.readString(tag);
            if (!body.contains("minecraft:cactus")) {
                throw helper.assertionException("export lost the entry: " + body);
            }
            if (!body.contains("\"replace\": false")) {
                throw helper.assertionException("export must not replace other packs: " + body);
            }
            if (!Files.isRegularFile(directory.resolve("export").resolve("minecraft")
                    .resolve("pack.mcmeta"))) {
                throw helper.assertionException("export is not a valid data pack");
            }
            deleteTree(directory);
        } catch (IOException exception) {
            throw helper.assertionException("cannot read the export: " + exception.getMessage());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void knowledgeCommandsAreRegistered(GameTestHelper helper) {
        var knowledge = helper.getLevel().getServer().getCommands().getDispatcher()
                .getRoot().getChild("tlmm").getChild("knowledge");
        if (knowledge == null) {
            throw helper.assertionException("/tlmm knowledge is not registered");
        }
        for (String sub : new String[] {"list", "scan", "ingest", "approve", "deny", "undo",
                "forget", "export", "ui"}) {
            if (knowledge.getChild(sub) == null) {
                throw helper.assertionException("/tlmm knowledge " + sub + " is missing");
            }
        }
        helper.succeed();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static ParsedPlan parse(GameTestHelper helper, String json) {
        try {
            return PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            throw helper.assertionException("plan json rejected: " + exception.code());
        }
    }
}
