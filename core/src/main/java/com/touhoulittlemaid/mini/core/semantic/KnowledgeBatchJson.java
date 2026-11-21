package com.touhoulittlemaid.mini.core.semantic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The wire format of one annotation round trip.
 *
 * <p>Request and response are both strict and both total: anything malformed is reported as a
 * problem line instead of throwing, because both sides are untrusted — a model can hallucinate
 * and a client can send garbage.
 */
public final class KnowledgeBatchJson {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private KnowledgeBatchJson() {
    }

    /** What one model claim looks like before validation. */
    public record Claim(String id, Set<SemanticTag> tags, double confidence, String reason) {

        public Claim {
            id = id == null ? "" : id;
            tags = tags == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(tags));
            confidence = Math.max(0.0D, Math.min(1.0D, confidence));
            reason = reason == null ? "" : reason;
        }
    }

    /** A parsed model answer. Problems never abort the batch; they ride along for the log. */
    public record Response(List<Claim> claims, List<String> unknown, List<String> problems) {

        public Response {
            claims = claims == null ? List.of() : List.copyOf(claims);
            unknown = unknown == null ? List.of() : List.copyOf(unknown);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean isEmpty() {
            return claims.isEmpty();
        }
    }

    public static String toRequestJson(KnowledgeBatch batch) {
        JsonObject root = new JsonObject();
        root.addProperty("batch", batch.id());
        JsonArray items = new JsonArray();
        for (KnowledgeCandidate candidate : batch.items()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", candidate.id());
            item.addProperty("kind", candidate.kind().name().toLowerCase(Locale.ROOT));
            if (candidate.hasName()) {
                item.addProperty("name", candidate.name());
            }
            JsonArray tags = new JsonArray();
            candidate.tags().forEach(tags::add);
            item.add("tags", tags);
            JsonObject data = new JsonObject();
            candidate.data().forEach((key, value) -> data.add(key, GSON.toJsonTree(value)));
            item.add("data", data);
            items.add(item);
        }
        root.add("items", items);
        return GSON.toJson(root);
    }

    /** Parses a request that a client sent back, e.g. after filling in display names. */
    public static KnowledgeBatch parseRequest(String json, List<String> problems) {
        List<String> bucket = problems == null ? new ArrayList<>() : problems;
        JsonObject root = rootObject(json, bucket);
        if (root == null) {
            return new KnowledgeBatch(null, List.of());
        }
        String batchId = string(root, "batch");
        List<KnowledgeCandidate> items = new ArrayList<>();
        JsonElement itemsElement = root.get("items");
        if (itemsElement != null && itemsElement.isJsonArray()) {
            for (JsonElement element : itemsElement.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    bucket.add("item is not an object");
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = string(object, "id");
                SemanticKind kind;
                try {
                    kind = SemanticKind.fromId(string(object, "kind"));
                } catch (RuntimeException exception) {
                    bucket.add(id + ": unknown kind");
                    continue;
                }
                Set<String> tags = stringSet(object.get("tags"));
                Map<String, Object> data = new LinkedHashMap<>();
                JsonElement dataElement = object.get("data");
                if (dataElement != null && dataElement.isJsonObject()) {
                    dataElement.getAsJsonObject().entrySet().forEach(row ->
                            data.put(row.getKey(), GSON.fromJson(row.getValue(), Object.class)));
                }
                items.add(new KnowledgeCandidate(id, kind, tags, data, string(object, "name")));
            }
        }
        return new KnowledgeBatch(batchId, items);
    }

    /**
     * Parses a model answer.
     *
     * <p>Unknown tag strings are dropped and recorded; an entry whose tags all drop still
     * reaches the validator, which rejects it as {@code bad_tag}. Nothing here decides what
     * is true.
     */
    public static Response parseResponse(String json) {
        List<String> problems = new ArrayList<>();
        JsonObject root = rootObject(json, problems);
        if (root == null) {
            return new Response(List.of(), List.of(), problems);
        }
        List<Claim> claims = new ArrayList<>();
        JsonElement entries = root.get("entries");
        if (entries != null && entries.isJsonArray()) {
            for (JsonElement element : entries.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    problems.add("entry is not an object");
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String id = string(object, "id");
                if (id.isBlank()) {
                    problems.add("entry without id");
                    continue;
                }
                Set<SemanticTag> tags = new LinkedHashSet<>();
                for (String raw : stringSet(object.get("tags"))) {
                    SemanticTag.byId(raw.toLowerCase(Locale.ROOT)).ifPresentOrElse(tags::add,
                            () -> problems.add(id + ": unknown tag " + raw));
                }
                double confidence = number(object, "confidence");
                claims.add(new Claim(id, tags, confidence, string(object, "reason")));
            }
        }
        List<String> unknown = new ArrayList<>(stringSet(root.get("unknown")));
        return new Response(claims, unknown, problems);
    }

    private static JsonObject rootObject(String json, List<String> problems) {
        if (json == null || json.isBlank()) {
            problems.add("empty payload");
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                problems.add("root is not an object");
                return null;
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            problems.add("invalid json: " + exception.getMessage());
            return null;
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static double number(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            return 0.0D;
        }
        return element.getAsDouble();
    }

    private static Set<String> stringSet(JsonElement element) {
        Set<String> values = new LinkedHashSet<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                if (value.isJsonPrimitive()) {
                    values.add(value.getAsString());
                }
            }
        }
        return values;
    }
}
