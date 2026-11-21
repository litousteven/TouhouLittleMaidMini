package com.touhoulittlemaid.mini.core.semantic;

import com.touhoulittlemaid.mini.core.plan.FailureCodes;

import java.util.Set;

/** The answer of {@link SemanticTags}: known tags plus where they came from. */
public record SemanticResolution(String id, SemanticKind kind, Set<SemanticTag> tags,
        SemanticSource source, double confidence) {

    public SemanticResolution {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        source = source == null ? SemanticSource.UNKNOWN : source;
    }

    public static SemanticResolution unknown(String id, SemanticKind kind) {
        return new SemanticResolution(id, kind, Set.of(), SemanticSource.UNKNOWN, 0.0D);
    }

    public boolean isKnown() {
        return !tags.isEmpty() && source != SemanticSource.UNKNOWN;
    }

    public boolean has(SemanticTag tag) {
        return tags.contains(tag);
    }

    /** Failure code a module should report when this content cannot be interpreted. */
    public String failureCode() {
        return isKnown() ? FailureCodes.OK : FailureCodes.UNKNOWN_CATEGORY;
    }
}
