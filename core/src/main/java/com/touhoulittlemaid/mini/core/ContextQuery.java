package com.touhoulittlemaid.mini.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A bounded, typed observation request.
 *
 * <p>The agent must ask for one category at a time instead of receiving the whole
 * world. Filters are IDs and tags only so an unknown mod can be described without
 * new Java code.
 */
public record ContextQuery(
        ContextCategory category,
        int radius,
        List<String> tags,
        List<String> blockIds,
        List<String> entityTypes,
        int maxResults) {

    public static final int DEFAULT_RADIUS = 16;
    public static final int MAX_RADIUS = 64;
    public static final int DEFAULT_MAX_RESULTS = 128;
    public static final int MAX_RESULTS = 512;

    public ContextQuery {
        Objects.requireNonNull(category, "category");
        radius = Math.max(1, Math.min(radius, MAX_RADIUS));
        maxResults = Math.max(1, Math.min(maxResults, MAX_RESULTS));
        tags = normalize(tags);
        blockIds = normalize(blockIds);
        entityTypes = normalize(entityTypes);
    }

    public static ContextQuery blocks(int radius) {
        return new ContextQuery(ContextCategory.BLOCKS, radius, List.of(), List.of(),
                List.of(), DEFAULT_MAX_RESULTS);
    }

    public boolean hasBlockFilters() {
        return !tags.isEmpty() || !blockIds.isEmpty();
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String normalized = value.strip().toLowerCase();
            if (normalized.startsWith("#")) {
                normalized = normalized.substring(1);
            }
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}
