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

/** The optional data pack export: own namespace only, one file per category. */
class KnowledgeExporterTest {

    @Test
    void writesOneTagFilePerNamespaceAndKind(@TempDir Path directory) throws Exception {
        List<SemanticEntry> entries = List.of(
                entry("mod:cabbage", SemanticKind.ITEM, Set.of(SemanticTag.CROP,
                        SemanticTag.FOOD)),
                entry("mod:cabbages", SemanticKind.BLOCK, Set.of(SemanticTag.CROP)),
                entry("mod:oak_log", SemanticKind.BLOCK, Set.of(SemanticTag.LOG)));

        var report = KnowledgeExporter.export(directory, entries, 75);

        assertEquals(1, report.namespaces());
        assertEquals(4, report.tags(), "crop applies to both the item and the block");
        assertEquals(4, report.values());
        assertTrue(report.problems().isEmpty(), report.problems().toString());

        Path pack = directory.resolve("export").resolve("mod");
        assertTrue(Files.isRegularFile(pack.resolve("pack.mcmeta")));
        String items = Files.readString(pack.resolve("data/tlmm/tags/item/crops.json"));
        assertTrue(items.contains("mod:cabbage"));
        String blocks = Files.readString(pack.resolve("data/tlmm/tags/block/crops.json"));
        assertTrue(blocks.contains("mod:cabbages"));
        String foods = Files.readString(pack.resolve("data/tlmm/tags/item/foods.json"));
        assertTrue(foods.contains("mod:cabbage"));
        assertTrue(Files.isRegularFile(pack.resolve("data/tlmm/tags/block/logs.json")));
    }

    @Test
    void exportIsAReplaceNotAnAppend(@TempDir Path directory) throws Exception {
        KnowledgeExporter.export(directory, List.of(
                entry("mod:old", SemanticKind.ITEM, Set.of(SemanticTag.FOOD))), 75);
        assertTrue(Files.exists(directory.resolve("export/mod/data/tlmm/tags/item/foods.json")));

        KnowledgeExporter.export(directory, List.of(
                entry("mod:new", SemanticKind.ITEM, Set.of(SemanticTag.TOOL))), 75);

        assertFalse(Files.exists(directory.resolve("export/mod/data/tlmm/tags/item/foods.json")));
        assertTrue(Files.readString(directory.resolve("export/mod/data/tlmm/tags/item/tools.json"))
                .contains("mod:new"));
    }

    @Test
    void emptyKnowledgeWritesNothing() {
        var report = KnowledgeExporter.export(null, List.of(), 75);
        assertEquals(0, report.namespaces());
        assertTrue(report.summary().startsWith("0 namespace"));
    }

    private static SemanticEntry entry(String id, SemanticKind kind, Set<SemanticTag> tags) {
        return new SemanticEntry(id, kind, tags, SemanticSource.MODEL, 0.9D, "kb-1", "");
    }
}
