package com.touhoulittlemaid.mini.core.plan;

/**
 * World and run state predicates may read.
 *
 * <p>Core owns the grammar; the loader owns the answers. Nothing here mutates the world, so
 * evaluating a predicate is always side-effect free and safe to call between ticks.
 */
public interface PredicateContext {

    boolean stepOk(String stepId);

    boolean stepFailed(String stepId);

    boolean boundEmpty(String name);

    int boundCount(String name);

    /** Items matching an id or {@code #tag} currently carried. */
    int inventoryCount(String item);

    /** Free slots left in the companion inventory. */
    int inventorySpace();

    int hostileCount(int radius);

    double ownerDistance();

    boolean isDay();

    boolean isRain();
}
