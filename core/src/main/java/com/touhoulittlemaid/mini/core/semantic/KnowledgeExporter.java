package com.touhoulittlemaid.mini.core.semantic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Writes the knowledge layer out as a standalone data pack.
 *
 * <p>The export only ever creates {@code #tlmm:} tags in its own namespace, one pack per
 * source namespace. It never rewrites a mod's or vanilla's tag files, so it cannot fight with
 * KubeJS, CraftTweaker or another data pack. Exporting is an explicit action: the result is
 * not installed into the world automatically.
 */
public final class KnowledgeExporter {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
            .create();

    public record ExportReport(int namespaces, int tags, int values, Path root,
            List<String> problems) {

        public ExportReport {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static ExportReport empty() {
            return new ExportReport(0, 0, 0, null, List.of());
        }

        public String summary() {
            return namespaces + " namespace(s), " + tags + " tag file(s), " + values + " value(s)";
        }
    }

    private KnowledgeExporter() {
    }

    public static ExportReport export(Path knowledgeDirectory, Collection<SemanticEntry> entries,
            int packFormat) {
        if (knowledgeDirectory == null) {
            return ExportReport.empty();
        }
        Path root = knowledgeDirectory.resolve("export");
        List<String> problems = new ArrayList<>();
        try {
            deleteTree(root, knowledgeDirectory);
        } catch (IOException exception) {
            problems.add("cannot clear " + root + ": " + exception.getMessage());
            return new ExportReport(0, 0, 0, root, problems);
        }

        Map<String, Map<SemanticKind, Map<SemanticTag, Set<String>>>> byNamespace =
                new TreeMap<>();
        if (entries != null) {
            for (SemanticEntry entry : entries) {
                Map<SemanticTag, Set<String>> tags = byNamespace
                        .computeIfAbsent(entry.namespace(), key -> new TreeMap<>(
                                Comparator.comparing(SemanticKind::name)))
                        .computeIfAbsent(entry.kind(), key -> new TreeMap<>(
                                Comparator.comparing(SemanticTag::id)));
                for (SemanticTag tag : entry.tags()) {
                    tags.computeIfAbsent(tag, key -> new TreeSet<>()).add(entry.id());
                }
            }
        }

        int tagFiles = 0;
        int values = 0;
        for (Map.Entry<String, Map<SemanticKind, Map<SemanticTag, Set<String>>>> namespace
                : byNamespace.entrySet()) {
            Path packRoot = root.resolve(namespace.getKey());
            try {
                writePackMeta(packRoot, namespace.getKey(), packFormat, problems);
                for (Map.Entry<SemanticKind, Map<SemanticTag, Set<String>>> kind
                        : namespace.getValue().entrySet()) {
                    for (Map.Entry<SemanticTag, Set<String>> tag : kind.getValue().entrySet()) {
                        Path file = packRoot.resolve("data").resolve("tlmm").resolve("tags")
                                .resolve(folder(kind.getKey()))
                                .resolve(tag.getKey().tagPath() + ".json");
                        JsonObject body = new JsonObject();
                        body.addProperty("replace", false);
                        JsonArray list = new JsonArray();
                        tag.getValue().forEach(list::add);
                        body.add("values", list);
                        write(file, body, problems);
                        tagFiles++;
                        values += tag.getValue().size();
                    }
                }
            } catch (IOException exception) {
                problems.add(namespace.getKey() + ": " + exception.getMessage());
            }
        }
        return new ExportReport(byNamespace.size(), tagFiles, values, root, problems);
    }

    private static void writePackMeta(Path packRoot, String namespace, int packFormat,
            List<String> problems) throws IOException {
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", packFormat);
        pack.addProperty("description", "TLMM knowledge export: " + namespace);
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        write(packRoot.resolve("pack.mcmeta"), root, problems);
    }

    private static void write(Path file, JsonObject body, List<String> problems) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(body, writer);
        }
    }

    private static String folder(SemanticKind kind) {
        return kind == null ? "item" : kind.name().toLowerCase(Locale.ROOT);
    }

    /** Removes only {@code <knowledge>/export}; anything else is refused. */
    private static void deleteTree(Path root, Path knowledgeDirectory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedParent = knowledgeDirectory.toAbsolutePath().normalize();
        if (!normalizedRoot.startsWith(normalizedParent)
                || !"export".equals(normalizedRoot.getFileName().toString())) {
            throw new IOException("refusing to delete " + normalizedRoot);
        }
        if (!Files.exists(normalizedRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
