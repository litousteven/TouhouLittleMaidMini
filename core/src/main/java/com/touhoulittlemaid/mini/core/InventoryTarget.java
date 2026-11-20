package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** Where an inventory operation reads from or writes to. */
public enum InventoryTarget {

    INVENTORY("inventory"),
    MAIN_HAND("main_hand"),
    OFF_HAND("off_hand"),
    HEAD("head"),
    CHEST("chest"),
    LEGS("legs"),
    FEET("feet");

    private final String id;

    InventoryTarget(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean isEquipment() {
        return this != INVENTORY;
    }

    public static InventoryTarget fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (InventoryTarget target : values()) {
            if (target.id.equals(id.strip().toLowerCase())) {
                return target;
            }
        }
        throw new IllegalArgumentException("Unknown inventory target: " + id);
    }
}
