package com.touhoulittlemaid.mini.core.autonomy;

import com.touhoulittlemaid.mini.core.plan.PlanGate;

import java.util.Locale;
import java.util.Optional;

/**
 * How much a companion may do without being asked.
 *
 * <p>Four levels, no fifth. {@code suggest} exists because "she asked me first" is a different
 * product from "she just does it", and both are reasonable defaults.
 */
public enum AutonomyTier {

    /** Only commands. */
    OFF,
    /** Scans and proposes a plan, waits for the player to confirm. */
    SUGGEST,
    /** Runs the chore whitelist on its own. */
    CHORES,
    /** May use the whole module library, still gated and budgeted. */
    FREE;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AutonomyTier fromId(String id) {
        if (id == null || id.isBlank()) {
            return CHORES;
        }
        for (AutonomyTier tier : values()) {
            if (tier.id().equalsIgnoreCase(id.strip())) {
                return tier;
            }
        }
        return CHORES;
    }

    public static Optional<AutonomyTier> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        for (AutonomyTier tier : values()) {
            if (tier.id().equalsIgnoreCase(id.strip())) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }

    /** Whether a plan from this tier starts without a human in the loop. */
    public boolean autoRuns() {
        return this == CHORES || this == FREE;
    }

    public boolean scans() {
        return this != OFF;
    }

    /**
     * The gate policy this tier gives a plan the model chose by itself.
     *
     * <p>Commands use {@link PlanGate.Policy#command()}; this only covers autonomy.
     */
    public PlanGate.Policy policy() {
        return switch (this) {
            case CHORES -> PlanGate.Policy.chores();
            case FREE -> PlanGate.Policy.free();
            case SUGGEST -> PlanGate.Policy.command();
            case OFF -> PlanGate.Policy.command();
        };
    }
}
