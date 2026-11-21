package com.touhoulittlemaid.mini.core.semantic;

/**
 * Where a classification came from. Lower ordinal means higher priority.
 *
 * <p>Real data and real tags are not overridable; user entries beat model proposals;
 * heuristics only fill gaps.
 */
public enum SemanticSource {

    /** Registry facts such as the FOOD component or block hardness. */
    DATA,
    /** Real data pack tags. */
    TAG,
    /** Hand-written by the player. */
    USER,
    /** Proposed by a model and accepted. */
    MODEL,
    /** Built-in fallback with confidence 0.4. */
    HEURISTIC,
    UNKNOWN;

    public boolean overrides(SemanticSource other) {
        return ordinal() < other.ordinal();
    }
}
