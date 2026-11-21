package com.touhoulittlemaid.mini.core;

/**
 * Local behaviour a companion runs without an external agent.
 *
 * <p>These are the only behaviours the body owns. Everything else is composed by the
 * agent from generic actions.
 */
public enum LocalBehavior {

    /** Stay near the home position. */
    STAY("stay"),

    /** Follow the owner within a bounded distance. */
    FOLLOW("follow"),

    /** Stay near home and defend the owner and self from hostile targets. */
    GUARD("guard"),

    /** Work near home: harvest mature crops and replant them from carried seeds. */
    FARM("farm");

    private final String id;

    LocalBehavior(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static LocalBehavior fromId(String id) {
        for (LocalBehavior behavior : values()) {
            if (behavior.id.equals(id)) {
                return behavior;
            }
        }
        throw new IllegalArgumentException("Unknown local behaviour: " + id);
    }
}
