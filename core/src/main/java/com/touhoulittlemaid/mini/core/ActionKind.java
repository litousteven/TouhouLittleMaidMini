package com.touhoulittlemaid.mini.core;

/**
 * The generic action families a companion body exposes.
 *
 * <p>Gameplay such as farming or fishing is a composition of these, never a new family.
 */
public enum ActionKind {

    MOVE("move"),
    OBSERVE("observe"),
    INTERACT("interact"),
    INVENTORY("inventory"),
    WAIT("wait"),
    STOP("stop");

    private final String id;

    ActionKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ActionKind fromId(String id) {
        for (ActionKind kind : values()) {
            if (kind.id.equals(id)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown action kind: " + id);
    }
}
