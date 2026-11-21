package com.touhoulittlemaid.mini.core.semantic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One namespace of the knowledge layer, on disk.
 *
 * <p>Files are human-editable: a player can open them, delete a line, or restore a backup.
 * Writes go through a temp file and an atomic replace so a crash cannot leave half a file.
 */
public final class KnowledgeFile {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private KnowledgeFile() {
    }

    public record Snapshot(String namespace, String updatedAt,
            Map<String, SemanticEntry> entries, Map<String, SemanticEntry> pending) {

        public Snapshot {
            namespace = namespace == null ? "minecraft" : namespace;
            updatedAt = updatedAt == null ? Instant.now().toString() : updatedAt;
            entries = entries == null ? Map.of() : Map.copyOf(entries);
            pending = pending == null ? Map.of() : Map.copyOf(pending);
        }

        public static Snapshot empty(String namespace) {
            return new Snapshot(namespace, Instant.now().toString(), Map.of(), Map.of());
        }
    }

    public record ReadResult(Snapshot snapshot, List<String> problems) {
    }

    /** Reads a file; a missing file is an empty snapshot, a broken one is reported. */
    public static ReadResult read(Path path) {
        String fallbackNamespace = namespaceOf(path);
        List<String> problems = new ArrayList<>();
        if (!Files.isRegularFile(path)) {
            return new ReadResult(Snapshot.empty(fallbackNamespace), problems);
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                problems.add(path.getFileName() + ": root is not an object");
                return new ReadResult(Snapshot.empty(fallbackNamespace), problems);
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | JsonParseException exception) {
            problems.add(path.getFileName() + ": " + exception.getMessage());
            return new ReadResult(Snapshot.empty(fallbackNamespace), problems);
        }

        String namespace = root.has("namespace") && root.get("namespace").isJsonPrimitive()
                ? root.get("namespace").getAsString() : fallbackNamespace;
        String updatedAt = root.has("updated_at") && root.get("updated_at").isJsonPrimitive()
                ? root.get("updated_at").getAsString() : Instant.now().toString();
        Map<String, SemanticEntry> entries = readEntries(root.get("entries"), problems,
                path.getFileName().toString());
        Map<String, SemanticEntry> pending = readEntries(root.get("pending"), problems,
                path.getFileName().toString());
        return new ReadResult(new Snapshot(namespace, updatedAt, entries, pending), problems);
    }

    public static void write(Path path, Snapshot snapshot) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("namespace", snapshot.namespace());
        root.addProperty("updated_at", Instant.now().toString());
        root.add("entries", writeEntries(snapshot.entries()));
        root.add("pending", writeEntries(snapshot.pending()));

        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, SemanticEntry> readEntries(JsonElement element, List<String> problems,
            String fileName) {
        Map<String, SemanticEntry> entries = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return entries;
        }
        for (Map.Entry<String, JsonElement> row : element.getAsJsonObject().entrySet()) {
            if (!row.getValue().isJsonObject()) {
                problems.add(fileName + ": " + row.getKey() + " is not an object");
                continue;
            }
            JsonObject object = row.getValue().getAsJsonObject();
            try {
                SemanticKind kind = SemanticKind.fromId(requiredString(object, "kind"));
                List<SemanticTag> tags = new ArrayList<>();
                if (object.has("tags") && object.get("tags").isJsonArray()) {
                    JsonArray array = object.getAsJsonArray("tags");
                    for (JsonElement tagElement : array) {
                        if (!tagElement.isJsonPrimitive()) {
                            continue;
                        }
                        SemanticTag.byId(tagElement.getAsString()).ifPresent(tags::add);
                    }
                }
                SemanticSource source = object.has("source")
                        ? SemanticSource.valueOf(object.get("source").getAsString()
                                .toUpperCase(java.util.Locale.ROOT))
                        : SemanticSource.MODEL;
                double confidence = object.has("confidence")
                        ? object.get("confidence").getAsDouble() : 0.0D;
                String batch = object.has("batch") ? object.get("batch").getAsString() : "";
                String reason = object.has("reason") ? object.get("reason").getAsString() : "";
                entries.put(row.getKey(), new SemanticEntry(row.getKey(), kind,
                        java.util.Set.copyOf(tags), source, clamp(confidence), batch, reason));
            } catch (RuntimeException exception) {
                problems.add(fileName + ": " + row.getKey() + " is invalid ("
                        + exception.getClass().getSimpleName() + ")");
            }
        }
        return entries;
    }

    private static JsonObject writeEntries(Map<String, SemanticEntry> entries) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, SemanticEntry> row : entries.entrySet()) {
            SemanticEntry entry = row.getValue();
            JsonObject value = new JsonObject();
            value.addProperty("kind", entry.kind().name().toLowerCase(java.util.Locale.ROOT));
            JsonArray tags = new JsonArray();
            entry.tags().forEach(tag -> tags.add(tag.id()));
            value.add("tags", tags);
            value.addProperty("source", entry.source().name().toLowerCase(
                    java.util.Locale.ROOT));
            value.addProperty("confidence", entry.confidence());
            if (!entry.batch().isEmpty()) {
                value.addProperty("batch", entry.batch());
            }
            if (!entry.reason().isEmpty()) {
                value.addProperty("reason", entry.reason());
            }
            object.add(row.getKey(), value);
        }
        return object;
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + field);
        }
        return object.get(field).getAsString();
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static String namespaceOf(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
    }
}
