package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** Which hand an interaction uses. */
public enum InteractHand {

    MAIN("main"),
    OFF("off");

    private final String id;

    InteractHand(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static InteractHand fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (InteractHand hand : values()) {
            if (hand.id.equals(id.strip().toLowerCase())) {
                return hand;
            }
        }
        throw new IllegalArgumentException("Unknown interact hand: " + id);
    }
}
