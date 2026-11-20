package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** Direction of a move relative to its target. */
public enum MoveMode {

    GOTO("goto"),
    AWAY("away");

    private final String id;

    MoveMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static MoveMode fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (MoveMode mode : values()) {
            if (mode.id.equals(id.strip().toLowerCase())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown move mode: " + id);
    }
}
