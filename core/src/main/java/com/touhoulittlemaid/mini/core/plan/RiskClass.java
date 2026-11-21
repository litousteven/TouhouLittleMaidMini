package com.touhoulittlemaid.mini.core.plan;

/**
 * How much a module can change the world.
 *
 * <p>The gate uses this to decide what an autonomous tier may run. Ordering matters:
 * {@link #ordinal()} grows with destructive potential.
 */
public enum RiskClass {

    /** Reads state only. */
    OBSERVE,

    /** Moves the companion or picks items up without changing blocks. */
    MOVE,

    /** Changes blocks, containers or inventories without destroying value. */
    MODIFY,

    /** Removes blocks or container contents. Needs explicit consent outside command help. */
    DESTROY
}
