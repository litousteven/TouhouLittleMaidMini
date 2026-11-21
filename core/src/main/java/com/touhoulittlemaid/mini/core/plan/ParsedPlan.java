package com.touhoulittlemaid.mini.core.plan;

/**
 * A parsed plan together with the exact payload size it came from.
 *
 * <p>The gate needs the size but it is not part of the semantic model, so it travels
 * alongside rather than inside {@link Plan}.
 */
public record ParsedPlan(Plan plan, int bytes) {

    public ParsedPlan {
        if (plan == null) {
            throw new IllegalArgumentException("plan is required");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
    }
}
