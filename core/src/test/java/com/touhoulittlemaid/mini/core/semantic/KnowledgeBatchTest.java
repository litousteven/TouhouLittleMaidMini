package com.touhoulittlemaid.mini.core.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The annotation round trip: request shape, response parsing and what may become behaviour. */
class KnowledgeBatchTest {

    private final SemanticTagsTest.StubFacts facts = new SemanticTagsTest.StubFacts();
    private final KnowledgeStore store = new KnowledgeStore();

    @Test
    void requestCarriesFactsButNotNames() {
        KnowledgeBatch batch = batch("kb-1",
                candidate("mod:cabbage", SemanticKind.ITEM, Set.of("#c:crops"),
                        Map.of("stack", 64, "food", Map.of("nutrition", 2))));
        String json = KnowledgeBatchJson.toRequestJson(batch);

        assertTrue(json.contains("\"batch\":\"kb-1\""));
        assertTrue(json.contains("\"kind\":\"item\""));
        assertTrue(json.contains("\"#c:crops\""));
        assertTrue(json.contains("\"nutrition\":2"));
        assertFalse(json.contains("\"name\""), "the server must not invent a language");
    }

    @Test
    void clientFilledNamesSurviveTheRoundTrip() {
        KnowledgeBatch batch = batch("kb-1",
                candidate("mod:cabbage", SemanticKind.ITEM, Set.of(), Map.of()));
        KnowledgeBatch named = new KnowledgeBatch("kb-1", List.of(
                batch.items().get(0).withName("卷心菜")));
        List<String> problems = new ArrayList<>();
        KnowledgeBatch parsed = KnowledgeBatchJson.parseRequest(
                KnowledgeBatchJson.toRequestJson(named), problems);

        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals("卷心菜", parsed.items().get(0).name());
        assertEquals(SemanticKind.ITEM, parsed.items().get(0).kind());
    }

    @Test
    void batchesDropDuplicatesAndStopAtSixtyFour() {
        List<KnowledgeCandidate> many = new ArrayList<>();
        for (int index = 0; index < 80; index++) {
            many.add(candidate("mod:item" + index, SemanticKind.ITEM, Set.of(), Map.of()));
        }
        many.add(candidate("mod:item0", SemanticKind.ITEM, Set.of(), Map.of()));
        KnowledgeBatch batch = new KnowledgeBatch("kb-1", many);

        assertEquals(KnowledgeBatch.MAX_ITEMS, batch.size());
        assertEquals(KnowledgeBatch.MAX_ITEMS, batch.ids().size());
    }

    @Test
    void responseDropsUnknownTagsButKeepsTheEntry() {
        var response = KnowledgeBatchJson.parseResponse("""
                {"entries":[
                  {"id":"mod:bush","tags":["crop","banana"],"confidence":0.8,"reason":"looks ripe"},
                  {"tags":["crop"],"confidence":0.8}
                ]}
                """);

        assertEquals(1, response.claims().size());
        assertEquals(Set.of(SemanticTag.CROP), response.claims().get(0).tags());
        assertTrue(response.problems().stream().anyMatch(line -> line.contains("banana")));
        assertTrue(response.problems().stream().anyMatch(line -> line.contains("without id")));
    }

    @Test
    void brokenResponseIsReportedInsteadOfThrowing() {
        var response = KnowledgeBatchJson.parseResponse("{not json");
        assertTrue(response.isEmpty());
        assertFalse(response.problems().isEmpty());
    }

    @Test
    void acceptedClaimBecomesBehaviour() {
        KnowledgeBatch batch = batch("kb-1",
                candidate("mod:cabbage", SemanticKind.ITEM, Set.of("#c:crops"), Map.of()));
        var report = KnowledgeIngest.ingest(batch, KnowledgeBatchJson.parseResponse("""
                {"entries":[{"id":"mod:cabbage","tags":["crop","food"],
                  "confidence":0.9,"reason":"seed plus food component"}]}
                """), facts, store);

        assertEquals(1, report.accepted());
        assertEquals(0, report.pending());
        var resolution = SemanticTags.resolve("mod:cabbage", SemanticKind.ITEM, facts, store);
        assertEquals(SemanticSource.MODEL, resolution.source());
        assertTrue(resolution.has(SemanticTag.CROP));
        assertEquals("kb-1", store.lookup("mod:cabbage", SemanticKind.ITEM,
                SemanticSource.MODEL).orElseThrow().batch());
    }

    @Test
    void lowConfidenceIsParkedAndNeverResolves() {
        var report = KnowledgeIngest.ingest(single("mod:bush", SemanticKind.BLOCK),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[{"id":"mod:bush","tags":["crop"],"confidence":0.5}]}
                        """), facts, store);

        assertEquals(0, report.accepted());
        assertEquals(1, report.pending());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.LOW_CONFIDENCE, 0));
        assertFalse(SemanticTags.resolve("mod:bush", SemanticKind.BLOCK, facts, store).isKnown());
    }

    @Test
    void hostileAlwaysWaitsForThePlayer() {
        facts.heuristic.put("mod:beast", Set.of());
        var report = KnowledgeIngest.ingest(single("mod:beast", SemanticKind.ENTITY),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[{"id":"mod:beast","tags":["hostile"],"confidence":0.99}]}
                        """), facts, store);

        assertEquals(0, report.accepted());
        assertEquals(1, report.pending());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.HOSTILE_UNCONFIRMED, 0));
        assertTrue(store.pendingEntry("mod:beast", SemanticKind.ENTITY).isPresent());
        assertFalse(store.any("mod:beast", SemanticKind.ENTITY).isPresent());
        assertFalse(SemanticTags.resolve("mod:beast", SemanticKind.ENTITY, facts, store).isKnown());
    }

    @Test
    void hardExclusionsCannotBeCalledHostile() {
        facts.hostileExcluded.add("minecraft:villager");
        var report = KnowledgeIngest.ingest(single("minecraft:villager", SemanticKind.ENTITY),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[{"id":"minecraft:villager","tags":["hostile"],
                          "confidence":0.99}]}
                        """), facts, store);

        assertEquals(1, report.rejected());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.HOSTILE_EXCLUDED, 0));
        assertTrue(store.entries().isEmpty());
        assertTrue(store.pendingEntries().isEmpty());
    }

    @Test
    void modelCannotAnswerAboutContentNobodyAskedFor() {
        var report = KnowledgeIngest.ingest(single("mod:crop", SemanticKind.BLOCK),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[{"id":"mod:secret","tags":["crop"],"confidence":0.99}]}
                        """), facts, store);

        assertEquals(1, report.rejected());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.UNEXPECTED_ID, 0));
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void selfDeclaredUnknownStaysPendingEvenAtHighConfidence() {
        var report = KnowledgeIngest.ingest(single("mod:bush", SemanticKind.BLOCK),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[{"id":"mod:bush","tags":["crop"],"confidence":0.95}],
                         "unknown":["mod:bush"]}
                        """), facts, store);

        assertEquals(0, report.accepted());
        assertEquals(1, report.pending());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.MODEL_UNKNOWN, 0));
    }

    @Test
    void contradictionWithRealTagsIsRejected() {
        facts.tags.put("minecraft:oak_log", Set.of(SemanticTag.LOG));
        var report = KnowledgeIngest.ingest(
                single("minecraft:oak_log", SemanticKind.BLOCK),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[{"id":"minecraft:oak_log","tags":["stone"],
                          "confidence":0.9}]}
                        """), facts, store);

        assertEquals(1, report.rejected());
        assertEquals(1, report.codes().getOrDefault(KnowledgeCodes.TAG_CONFLICT, 0));
    }

    @Test
    void duplicateClaimsOnlyCountOnce() {
        var report = KnowledgeIngest.ingest(single("mod:crop", SemanticKind.BLOCK),
                KnowledgeBatchJson.parseResponse("""
                        {"entries":[
                          {"id":"mod:crop","tags":["crop"],"confidence":0.9},
                          {"id":"mod:crop","tags":["decorative"],"confidence":0.9}]}
                        """), facts, store);

        assertEquals(1, report.accepted());
        assertTrue(store.any("mod:crop", SemanticKind.BLOCK).orElseThrow()
                .has(SemanticTag.CROP));
    }

    @Test
    void batchIdsStayUniqueWithinOneSecond() {
        var now = Instant.parse("2026-11-22T10:00:00Z");
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < 100; index++) {
            ids.add(KnowledgeBatch.newId(now));
        }
        assertEquals(100, ids.size(), "a rollback must never hit somebody else's batch");
    }

    @Test
    void oneIdCanBeForgottenAcrossKinds() {
        store.put(new SemanticEntry("mod:crop", SemanticKind.ITEM, Set.of(SemanticTag.FOOD),
                SemanticSource.MODEL, 0.9D, "kb-1", ""));
        store.putPending(new SemanticEntry("mod:crop", SemanticKind.BLOCK,
                Set.of(SemanticTag.CROP), SemanticSource.MODEL, 0.5D, "kb-1", ""));

        assertTrue(store.removeAny("mod:crop"));
        assertTrue(store.entries().isEmpty());
        assertTrue(store.pendingEntries().isEmpty());
        assertFalse(store.removeAny("mod:crop"));
    }

    private KnowledgeBatch single(String id, SemanticKind kind) {
        return batch("kb-1", candidate(id, kind, Set.of(), Map.of()));
    }

    private static KnowledgeCandidate candidate(String id, SemanticKind kind, Set<String> tags,
            Map<String, Object> data) {
        return KnowledgeCandidate.of(id, kind, tags, data);
    }

    private static KnowledgeBatch batch(String id, KnowledgeCandidate candidate) {
        return new KnowledgeBatch(id, List.of(candidate));
    }
}
