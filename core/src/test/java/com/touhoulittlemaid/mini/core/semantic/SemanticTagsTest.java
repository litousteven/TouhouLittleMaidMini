package com.touhoulittlemaid.mini.core.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Priority order and the hard rule that overlays cannot beat registry truth. */
class SemanticTagsTest {

    private final StubFacts facts = new StubFacts();
    private final KnowledgeStore store = new KnowledgeStore();

    @Test
    void realDataBeatsEveryOverlay() {
        facts.data.put("minecraft:bread", Set.of(SemanticTag.FOOD));
        facts.tags.put("minecraft:bread", Set.of(SemanticTag.CROP));
        store.put(entry("minecraft:bread", SemanticKind.ITEM, SemanticSource.USER,
                Set.of(SemanticTag.DECORATIVE), 1.0D));

        SemanticResolution resolution = SemanticTags.resolve("minecraft:bread",
                SemanticKind.ITEM, facts, store);
        assertEquals(SemanticSource.DATA, resolution.source());
        assertTrue(resolution.has(SemanticTag.FOOD));
        assertFalse(resolution.has(SemanticTag.DECORATIVE));
        assertEquals(1.0D, resolution.confidence());
    }

    @Test
    void realTagsBeatThePlayer() {
        facts.tags.put("minecraft:oak_log", Set.of(SemanticTag.LOG));
        store.put(entry("minecraft:oak_log", SemanticKind.BLOCK, SemanticSource.USER,
                Set.of(SemanticTag.STONE), 1.0D));

        SemanticResolution resolution = SemanticTags.resolve("minecraft:oak_log",
                SemanticKind.BLOCK, facts, store);
        assertEquals(SemanticSource.TAG, resolution.source());
        assertTrue(resolution.has(SemanticTag.LOG));
    }

    @Test
    void userBeatsModelAndModelBeatsHeuristic() {
        facts.heuristic.put("examplemod:bush", Set.of(SemanticTag.DECORATIVE));
        store.put(entry("examplemod:bush", SemanticKind.BLOCK, SemanticSource.MODEL,
                Set.of(SemanticTag.CROP), 0.9D));
        assertEquals(SemanticSource.MODEL, SemanticTags.resolve("examplemod:bush",
                SemanticKind.BLOCK, facts, store).source());

        store.put(entry("examplemod:bush", SemanticKind.BLOCK, SemanticSource.USER,
                Set.of(SemanticTag.CROP, SemanticTag.STATION), 1.0D));
        SemanticResolution resolution = SemanticTags.resolve("examplemod:bush",
                SemanticKind.BLOCK, facts, store);
        assertEquals(SemanticSource.USER, resolution.source());
        assertTrue(resolution.has(SemanticTag.STATION));
    }

    @Test
    void heuristicFillsTheGapWithLowConfidence() {
        facts.heuristic.put("examplemod:ore", Set.of(SemanticTag.ORE));
        SemanticResolution resolution = SemanticTags.resolve("examplemod:ore",
                SemanticKind.BLOCK, facts, store);
        assertEquals(SemanticSource.HEURISTIC, resolution.source());
        assertEquals(SemanticTags.HEURISTIC_CONFIDENCE, resolution.confidence());
        assertTrue(resolution.isKnown());
    }

    @Test
    void unknownContentReportsACode() {
        SemanticResolution resolution = SemanticTags.resolve("examplemod:mystery",
                SemanticKind.BLOCK, facts, store);
        assertFalse(resolution.isKnown());
        assertEquals("unknown_category", resolution.failureCode());
        assertEquals(SemanticSource.UNKNOWN, resolution.source());
    }

    private static SemanticEntry entry(String id, SemanticKind kind, SemanticSource source,
            Set<SemanticTag> tags, double confidence) {
        return new SemanticEntry(id, kind, tags, source, confidence, "test", "");
    }

    static final class StubFacts implements RealFacts {

        final Map<String, Set<SemanticTag>> data = new HashMap<>();
        final Map<String, Set<SemanticTag>> tags = new HashMap<>();
        final Map<String, Set<SemanticTag>> heuristic = new HashMap<>();
        boolean everythingExists = true;

        @Override
        public boolean exists(SemanticKind kind, String id) {
            return everythingExists;
        }

        @Override
        public Set<SemanticTag> realData(SemanticKind kind, String id) {
            return data.getOrDefault(id, Set.of());
        }

        @Override
        public Set<SemanticTag> realTags(SemanticKind kind, String id) {
            return tags.getOrDefault(id, Set.of());
        }

        @Override
        public Set<SemanticTag> heuristic(SemanticKind kind, String id) {
            return heuristic.getOrDefault(id, Set.of());
        }
    }
}
