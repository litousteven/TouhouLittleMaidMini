package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * When a companion is allowed to work.
 *
 * <p>The schedule only gates the local behaviour, never an explicit agent request. A
 * companion that is told to stay stays around the clock; the schedule exists so a
 * working routine can be limited to daylight or night without a per-activity task.
 */
public enum CompanionSchedule {

    ALL("all"),
    DAY("day"),
    NIGHT("night");

    private final String id;

    CompanionSchedule(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /**
     * @param brightOutside whether the dimension currently counts as daytime
     * @return whether work is allowed at that time
     */
    public boolean isActive(boolean brightOutside) {
        return switch (this) {
            case ALL -> true;
            case DAY -> brightOutside;
            case NIGHT -> !brightOutside;
        };
    }

    public static CompanionSchedule fromId(String id) {
        Objects.requireNonNull(id, "id");
        for (CompanionSchedule schedule : values()) {
            if (schedule.id.equals(id.strip().toLowerCase())) {
                return schedule;
            }
        }
        throw new IllegalArgumentException("Unknown schedule: " + id);
    }
}
