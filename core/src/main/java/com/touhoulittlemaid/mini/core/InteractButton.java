package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/** Which click a generic interaction performs. */
public enum InteractButton {

    RIGHT("right"),
    LEFT("left");

    private final String id;

    InteractButton(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static InteractButton fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (InteractButton button : values()) {
            if (button.id.equals(id.strip().toLowerCase())) {
                return button;
            }
        }
        throw new IllegalArgumentException("Unknown interact button: " + id);
    }
}
