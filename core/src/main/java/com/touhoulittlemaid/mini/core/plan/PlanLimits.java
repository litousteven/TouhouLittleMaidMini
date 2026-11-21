package com.touhoulittlemaid.mini.core.plan;

/**
 * Hard limits from the plan contract.
 *
 * <p>Every value here is a security boundary as much as a UX boundary: the gate rejects a
 * plan that exceeds them before a single tick runs.
 */
public final class PlanLimits {

    public static final int MAX_PLAN_BYTES = 16 * 1024;
    public static final int MAX_STEPS = 24;
    public static final int MAX_REPEAT = 8;
    public static final int MAX_RETRIES = 2;
    public static final int MAX_ACTIONS = 400;
    public static final int MAX_TICKS = 600 * 20;
    public static final int MAX_DESTROYED_BLOCKS = 128;
    public static final int MAX_PREDICATE_LENGTH = 160;
    public static final int VERSION = 1;

    private PlanLimits() {
    }
}
