package com.touhoulittlemaid.mini.core.plan;

/**
 * Bounded repetition of one step.
 *
 * <p>{@code max} is capped by {@link PlanLimits#MAX_REPEAT}; {@code whileCondition} is a
 * whitelisted predicate that ends the loop early. There is no nested loop.
 */
public record RepeatSpec(int max, String whileCondition) {

    public static final RepeatSpec ONCE = new RepeatSpec(1, "");

    public RepeatSpec {
        if (max < 1 || max > PlanLimits.MAX_REPEAT) {
            throw new IllegalArgumentException("repeat max must be within 1.."
                    + PlanLimits.MAX_REPEAT);
        }
        whileCondition = whileCondition == null ? "" : whileCondition;
    }

    public boolean isOnce() {
        return max == 1 && whileCondition.isEmpty();
    }

    public boolean hasCondition() {
        return !whileCondition.isEmpty();
    }
}
