package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** Supported inventory operations. */
public enum InventoryAction {

    LIST("list"),
    READ("read"),
    MOVE("move"),
    EQUIP("equip"),
    DROP("drop");

    private final String id;

    InventoryAction(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static InventoryAction fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (InventoryAction action : values()) {
            if (action.id.equals(id.strip().toLowerCase())) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown inventory action: " + id);
    }
}
