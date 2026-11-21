package com.touhoulittlemaid.mini.core.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Validation, file round trip, conflict handling and batch rollback. */
class KnowledgeStoreTest {

    private final SemanticTagsTest.StubFacts facts = new SemanticTagsTest.StubFacts();

    @Test
    void rejectsUnknownIdsKindMismatchesAndBadTags() {
        facts.everythingExists = false;
        assertEquals(KnowledgeCodes.UNKNOWN_ID, validate(entry("a:b", SemanticKind.ITEM,
                SemanticSource.MODEL, Set.of(SemanticTag.FOOD), 0.9D)).code());

        facts.everythingExists = true;
        assertEquals(KnowledgeCodes.KIND_MISMATCH, validate(entry("a:b", SemanticKind.ITEM,
                SemanticSource.MODEL, Set.of(SemanticTag.STATION), 0.9D)).code());
        assertEquals(KnowledgeCodes.BAD_TAG, validate(entry("a:b", SemanticKind.ITEM,
                SemanticSource.MODEL, Set.of(), 0.9D)).code());
    }

    @Test
    void realTagsCannotBeContradicted() {
        facts.tags.put("minecraft:oak_log", Set.of(SemanticTag.LOG));
        var validation = validate(entry("minecraft:oak_log", SemanticKind.BLOCK,
                SemanticSource.MODEL, Set.of(SemanticTag.STONE), 0.95D));
        assertEquals(KnowledgeValidator.Outcome.REJECTED, validation.outcome());
        assertEquals(KnowledgeCodes.TAG_CONFLICT, validation.code());

        assertTrue(validate(entry("minecraft:oak_log", SemanticKind.BLOCK,
                SemanticSource.MODEL, Set.of(SemanticTag.LOG), 0.95D)).ok());
    }

    @Test
    void playerEntriesCannotBeOverwrittenByModels() {
        KnowledgeStore store = new KnowledgeStore();
        store.put(entry("mod:crop", SemanticKind.BLOCK, SemanticSource.USER,
                Set.of(SemanticTag.CROP), 1.0D));
        var validation = KnowledgeValidator.validate(entry("mod:crop", SemanticKind.BLOCK,
                SemanticSource.MODEL, Set.of(SemanticTag.DECORATIVE), 0.9D), facts, store);
        assertEquals(KnowledgeCodes.USER_LOCKED, validation.code());
    }

    @Test
    void lowConfidenceGoesToPendingInsteadOfApplying() {
        KnowledgeStore store = new KnowledgeStore();
        var validation = KnowledgeValidator.validate(entry("mod:bush", SemanticKind.BLOCK,
                SemanticSource.MODEL, Set.of(SemanticTag.CROP), 0.5D), facts, store);
        assertEquals(KnowledgeValidator.Outcome.PENDING, validation.outcome());
        assertEquals(KnowledgeCodes.LOW_CONFIDENCE, validation.code());
        assertEquals(0, store.size());
    }

    @Test
    void filesRoundTripAndKeepBatchIds(@TempDir Path directory) throws Exception {
        KnowledgeStore store = new KnowledgeStore();
        store.put(entry("mod:cabbage", SemanticKind.ITEM, SemanticSource.MODEL,
                Set.of(SemanticTag.CROP, SemanticTag.FOOD), 0.9D));
        store.putPending(entry("mod:mystery", SemanticKind.BLOCK, SemanticSource.MODEL,
                Set.of(SemanticTag.CROP), 0.5D));
        store.save(directory);

        assertTrue(Files.isRegularFile(directory.resolve("mod.json")));
        assertFalse(Files.exists(directory.resolve("mod.json.tmp")));

        KnowledgeStore reloaded = new KnowledgeStore();
        var report = reloaded.load(directory, facts);
        assertEquals(1, report.accepted());
        assertEquals(1, report.pending());
        assertTrue(reloaded.lookup("mod:cabbage", SemanticKind.ITEM,
                SemanticSource.MODEL).isPresent());
        assertEquals("test", reloaded.lookup("mod:cabbage", SemanticKind.ITEM,
                SemanticSource.MODEL).orElseThrow().batch());
    }

    @Test
    void conflictingFileEntryIsIgnoredButTheRestLoads(@TempDir Path directory) throws Exception {
        facts.tags.put("minecraft:oak_log", Set.of(SemanticTag.LOG));
        Files.writeString(directory.resolve("mod.json"), """
                {"namespace":"mod","updated_at":"2026-01-01T00:00:00Z","entries":{
                  "minecraft:oak_log":{"kind":"block","tags":["stone"],"source":"model",
                    "confidence":0.9,"batch":"b1"},
                  "mod:cabbage":{"kind":"item","tags":["crop","food"],"source":"model",
                    "confidence":0.9,"batch":"b1"}},
                 "pending":{}}
                """);
        KnowledgeStore store = new KnowledgeStore();
        var report = store.load(directory, facts);

        assertEquals(1, report.accepted());
        assertEquals(1, report.rejected());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.TAG_CONFLICT, 0));
        assertTrue(store.any("mod:cabbage", SemanticKind.ITEM).isPresent());
        assertTrue(store.any("minecraft:oak_log", SemanticKind.BLOCK).isEmpty());
    }

    @Test
    void brokenFileIsReportedInsteadOfThrowing(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("broken.json"), "{not json");
        KnowledgeStore store = new KnowledgeStore();
        var report = store.load(directory, facts);
        assertEquals(0, report.accepted());
        assertFalse(report.problems().isEmpty());
    }

    @Test
    void batchRollbackRemovesAcceptedAndPending(@TempDir Path directory) throws Exception {
        KnowledgeStore store = new KnowledgeStore();
        SemanticEntry first = new SemanticEntry("mod:a", SemanticKind.ITEM,
                Set.of(SemanticTag.FOOD), SemanticSource.MODEL, 0.9D, "kb-1", "");
        SemanticEntry second = new SemanticEntry("mod:b", SemanticKind.ITEM,
                Set.of(SemanticTag.FOOD), SemanticSource.MODEL, 0.9D, "kb-2", "");
        store.put(first);
        store.put(second);
        assertEquals(1, store.removeBatch("kb-1"));
        assertEquals(1, store.size());
        assertTrue(store.any("mod:b", SemanticKind.ITEM).isPresent());
    }

    private KnowledgeValidator.Validation validate(SemanticEntry entry) {
        return KnowledgeValidator.validate(entry, facts, new KnowledgeStore());
    }

    private static SemanticEntry entry(String id, SemanticKind kind, SemanticSource source,
            Set<SemanticTag> tags, double confidence) {
        return new SemanticEntry(id, kind, tags, source, confidence, "test", "");
    }

    @Test
    void pendingEntriesNeverInfluenceResolution() {
        KnowledgeStore store = new KnowledgeStore();
        store.putPending(entry("mod:bush", SemanticKind.BLOCK, SemanticSource.MODEL,
                Set.of(SemanticTag.CROP), 0.5D));
        var resolution = SemanticTags.resolve("mod:bush", SemanticKind.BLOCK, facts, store);
        assertFalse(resolution.isKnown());
        assertEquals(List.of(), store.entries().stream().toList());
    }
}
