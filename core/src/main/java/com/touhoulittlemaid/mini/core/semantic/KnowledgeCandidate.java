package com.touhoulittlemaid.mini.core.semantic;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One piece of content the world has shown and the semantic layer cannot classify.
 *
 * <p>Only registry facts travel here — id, kind, the real data pack tags and a few read-only
 * numbers. The display name is absent on purpose: the server has no idea which language the
 * player reads, so the client fills it in just before calling the model.
 */
public record KnowledgeCandidate(
        String id,
        SemanticKind kind,
        Set<String> tags,
        Map<String, Object> data,
        String name) {

    public KnowledgeCandidate {
        id = id == null ? "" : id;
        kind = kind == null ? SemanticKind.ITEM : kind;
        Set<String> normalized = new LinkedHashSet<>();
        if (tags != null) {
            tags.stream()
                    .filter(tag -> tag != null && !tag.isBlank())
                    .map(KnowledgeCandidate::normalizeTag)
                    .sorted()
                    .forEach(normalized::add);
        }
        tags = Set.copyOf(normalized);
        Map<String, Object> copy = new LinkedHashMap<>();
        if (data != null) {
            data.entrySet().stream()
                    .filter(row -> row.getKey() != null)
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(row -> copy.put(row.getKey(), row.getValue()));
        }
        data = Collections.unmodifiableMap(copy);
        name = name == null ? "" : name;
    }

    public static KnowledgeCandidate of(String id, SemanticKind kind, Set<String> tags,
            Map<String, Object> data) {
        return new KnowledgeCandidate(id, kind, tags, data, "");
    }

    /** Called by the client once it knows the player's language. */
    public KnowledgeCandidate withName(String displayName) {
        return new KnowledgeCandidate(id, kind, tags, data, displayName);
    }

    public boolean hasName() {
        return !name.isBlank();
    }

    public String key() {
        return key(kind, id);
    }

    public static String key(SemanticKind kind, String id) {
        return (kind == null ? "item" : kind.name().toLowerCase(Locale.ROOT)) + "|" + id;
    }

    private static String normalizeTag(String tag) {
        return tag.startsWith("#") ? tag : "#" + tag;
    }
}
