package com.touhoulittlemaid.mini.core.semantic;

import java.util.Optional;

/**
 * Checks a proposed classification against registry truth and existing player entries.
 *
 * <p>A model's claim is a proposal, not a fact. Anything that would contradict a real tag,
 * a real component or a player's own line is dropped; anything the model is merely unsure
 * about is parked in {@code pending} until the player looks at it.
 */
public final class KnowledgeValidator {

    public enum Outcome {
        ACCEPTED,
        PENDING,
        REJECTED
    }

    public record Validation(Outcome outcome, String code, String message) {

        public static Validation accepted() {
            return new Validation(Outcome.ACCEPTED, KnowledgeCodes.OK, "");
        }

        public static Validation pending(String code, String message) {
            return new Validation(Outcome.PENDING, code, message);
        }

        public static Validation rejected(String code, String message) {
            return new Validation(Outcome.REJECTED, code, message);
        }

        public boolean ok() {
            return outcome == Outcome.ACCEPTED;
        }
    }

    private KnowledgeValidator() {
    }

    public static Validation validate(SemanticEntry entry, RealFacts facts,
            KnowledgeStore store) {
        if (entry == null) {
            return Validation.rejected(KnowledgeCodes.BAD_TAG, "entry is missing");
        }
        if (!facts.exists(entry.kind(), entry.id())) {
            return Validation.rejected(KnowledgeCodes.UNKNOWN_ID,
                    entry.id() + " is not a registered " + entry.kind());
        }
        if (entry.tags().isEmpty()) {
            return Validation.rejected(KnowledgeCodes.BAD_TAG, "entry has no tags");
        }
        for (SemanticTag tag : entry.tags()) {
            if (!tag.supports(entry.kind())) {
                return Validation.rejected(KnowledgeCodes.KIND_MISMATCH,
                        tag.id() + " is not meaningful for a " + entry.kind());
            }
        }

        var realTags = facts.realTags(entry.kind(), entry.id());
        var realMaterial = realTags.stream()
                .filter(SemanticTag::isMaterial)
                .collect(java.util.stream.Collectors.toSet());
        if (!realMaterial.isEmpty()) {
            for (SemanticTag tag : entry.tags()) {
                if (SemanticTag.isMaterial(tag) && !realMaterial.contains(tag)) {
                    return Validation.rejected(KnowledgeCodes.TAG_CONFLICT,
                            entry.id() + " is really " + realMaterial + ", so it cannot be "
                                    + tag.id());
                }
            }
        }

        if (entry.source() != SemanticSource.USER
                && store.lookup(entry.id(), entry.kind(), SemanticSource.USER).isPresent()) {
            return Validation.rejected(KnowledgeCodes.USER_LOCKED,
                    "the player already classified " + entry.id());
        }

        if (entry.confidence() < KnowledgeCodes.PENDING_THRESHOLD) {
            return Validation.pending(KnowledgeCodes.LOW_CONFIDENCE,
                    "confidence " + entry.confidence() + " is below "
                            + KnowledgeCodes.PENDING_THRESHOLD);
        }
        return Validation.accepted();
    }

    /** Convenience for tests and callers that only have a store. */
    public static Optional<SemanticTag> firstMismatch(SemanticEntry entry) {
        return entry.tags().stream().filter(tag -> !tag.supports(entry.kind())).findFirst();
    }
}
