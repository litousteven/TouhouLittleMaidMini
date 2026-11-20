package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * Kinds of bounded context an agent can request.
 *
 * <p>There are no per-mod categories such as {@code farm} or {@code machine}. Unknown
 * content is understood through block/entity IDs, tags and block state.
 */
public enum ContextCategory {

    SELF("self"),
    OWNER("owner"),
    INVENTORY("inventory"),
    BLOCKS("blocks"),
    ENTITIES("entities"),
    CONTAINERS("containers"),
    HAZARDS("hazards");

    private final String id;

    ContextCategory(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ContextCategory fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (ContextCategory category : values()) {
            if (category.id.equals(id.strip().toLowerCase())) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown context category: " + id);
    }
}
