package com.touhoulittlemaid.mini.core.plan;

/**
 * A module attempt in progress.
 *
 * <p>The runner calls {@link #tick()} once per server tick and never blocks. Implementations
 * must keep each tick bounded; long jobs advance a little and report
 * {@link ModuleResult#running()}.
 */
public interface ModuleExecution {

    ModuleResult tick();

    /** Idempotent. Called on plan cancel, plan timeout and companion removal. */
    default void cancel() {
    }
}
