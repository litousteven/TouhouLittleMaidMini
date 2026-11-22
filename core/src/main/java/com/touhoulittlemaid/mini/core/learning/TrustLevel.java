package com.touhoulittlemaid.mini.core.learning;

import java.util.Locale;

/** How much a reviewed plan template may do without being watched. */
public enum TrustLevel {

    /** Adopted but every run needs the owner to confirm it first. */
    SUPERVISED,
    /** Earned after two successful supervised runs; may run on its own. */
    TRUSTED,
    /** Rolled back or retired; never runs. */
    DISABLED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TrustLevel fromId(String value) {
        if (value != null) {
            for (TrustLevel level : values()) {
                if (level.id().equalsIgnoreCase(value.strip())) {
                    return level;
                }
            }
        }
        return DISABLED;
    }
}
