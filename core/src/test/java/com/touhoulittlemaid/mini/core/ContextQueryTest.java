package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class ContextQueryTest {

    @Test
    void categoryIdsRoundTrip() {
        for (ContextCategory category : ContextCategory.values()) {
            assertEquals(category, ContextCategory.fromId(category.id()));
        }
        assertEquals(ContextCategory.BLOCKS, ContextCategory.fromId(" BLOCKS "));
        assertThrows(IllegalArgumentException.class, () -> ContextCategory.fromId("farm"));
    }

    @Test
    void defaultQueryIsBlocks() {
        ContextQuery query = ContextQuery.blocks(16);
        assertEquals(ContextCategory.BLOCKS, query.category());
        assertEquals(16, query.radius());
        assertEquals(ContextQuery.DEFAULT_MAX_RESULTS, query.maxResults());
        assertFalse(query.hasBlockFilters());
    }

    @Test
    void listsAreTrimmedLoweredAndDeduplicated() {
        ContextQuery query = new ContextQuery(ContextCategory.BLOCKS, 8,
                Arrays.asList(" #minecraft:crops ", "minecraft:crops", "", null),
                Arrays.asList("Minecraft:Wheat"),
                Arrays.asList("minecraft:zombie", "MINECRAFT:ZOMBIE"),
                32);
        assertEquals(List.of("minecraft:crops"), query.tags());
        assertEquals(List.of("minecraft:wheat"), query.blockIds());
        assertEquals(List.of("minecraft:zombie"), query.entityTypes());
        assertEquals(32, query.maxResults());
    }

    @Test
    void valuesAreClamped() {
        ContextQuery query = new ContextQuery(ContextCategory.ENTITIES, 0,
                List.of(), List.of(), List.of(), 0);
        assertEquals(1, query.radius());
        assertEquals(1, query.maxResults());

        ContextQuery huge = new ContextQuery(ContextCategory.BLOCKS, 1000,
                List.of(), List.of(), List.of(), 100_000);
        assertEquals(ContextQuery.MAX_RADIUS, huge.radius());
        assertEquals(ContextQuery.MAX_RESULTS, huge.maxResults());
    }

    @Test
    void nullListsBecomeEmpty() {
        ContextQuery query = new ContextQuery(ContextCategory.SELF, 4, null, null, null, 10);
        assertTrue(query.tags().isEmpty());
        assertTrue(query.blockIds().isEmpty());
        assertTrue(query.entityTypes().isEmpty());
    }
}
