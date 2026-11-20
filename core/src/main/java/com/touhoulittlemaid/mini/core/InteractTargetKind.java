package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** What an interaction targets. */
public enum InteractTargetKind {

    BLOCK("block"),
    ENTITY("entity"),
    AIR("air");

    private final String id;

    InteractTargetKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static InteractTargetKind fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (InteractTargetKind kind : values()) {
            if (kind.id.equals(id.strip().toLowerCase())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown interact target: " + id);
    }
}
