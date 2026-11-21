package com.touhoulittlemaid.mini.core.semantic;

import java.util.Set;

/**
 * Registry truth, supplied by the loader.
 *
 * <p>Core owns the priority rules; the loader owns the answers. Keeping the split here is
 * what lets the resolver be unit tested without Minecraft.
 */
public interface RealFacts {

    boolean exists(SemanticKind kind, String id);

    /** Stage 1: components and other registry values (FOOD, burn time, block class). */
    Set<SemanticTag> realData(SemanticKind kind, String id);

    /** Stage 2: data pack tags the mod itself ships. */
    Set<SemanticTag> realTags(SemanticKind kind, String id);

    /** Stage 5: built-in fallback, confidence 0.4. */
    Set<SemanticTag> heuristic(SemanticKind kind, String id);

    /**
     * Raw data pack tags, as {@code #namespace:path} strings.
     *
     * <p>These are evidence for a model, not a classification: the resolver never reads
     * them. They are what lets the model say "this is in {@code #c:ores} but I do not know
     * which ore" instead of guessing from the name alone.
     */
    default Set<String> rawTags(SemanticKind kind, String id) {
        return Set.of();
    }

    /**
     * Content that must never be classified as hostile, whatever a model says.
     *
     * <p>Players, villagers and bosses are type-level exclusions. Instance-level ones — a
     * named pet, a tamed animal — are checked by the guard itself, because the knowledge
     * layer describes types, not instances.
     */
    default boolean hostileExcluded(SemanticKind kind, String id) {
        return false;
    }
}
