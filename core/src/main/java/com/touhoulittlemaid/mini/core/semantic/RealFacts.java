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
}
