package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** What a move target refers to. */
public enum MoveTargetKind {

    POSITION("position"),
    BLOCK("block"),
    ENTITY("entity");

    private final String id;

    MoveTargetKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static MoveTargetKind fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (MoveTargetKind kind : values()) {
            if (kind.id.equals(id.strip().toLowerCase())) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown move target: " + id);
    }
}
