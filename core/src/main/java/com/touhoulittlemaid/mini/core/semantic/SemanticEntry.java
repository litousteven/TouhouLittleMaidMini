package com.touhoulittlemaid.mini.core.semantic;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One classification row from a knowledge file.
 *
 * <p>It carries provenance on purpose: the player has to be able to see whether a line came
 * from a real tag, themselves, or a model, and undo a whole batch by {@code batch}.
 */
public record SemanticEntry(
        String id,
        SemanticKind kind,
        Set<SemanticTag> tags,
        SemanticSource source,
        double confidence,
        String batch,
        String reason) {

    public SemanticEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        tags = tags == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(tags));
        source = source == null ? SemanticSource.MODEL : source;
        if (confidence < 0.0D || confidence > 1.0D) {
            throw new IllegalArgumentException("confidence must be within 0..1");
        }
        batch = batch == null ? "" : batch;
        reason = reason == null ? "" : reason;
    }

    public boolean has(SemanticTag tag) {
        return tags.contains(tag);
    }

    public SemanticEntry withSource(SemanticSource newSource) {
        return new SemanticEntry(id, kind, tags, newSource, confidence, batch, reason);
    }

    /** Namespace part of a namespaced id, or {@code minecraft}. */
    public String namespace() {
        int separator = id.indexOf(':');
        return separator <= 0 ? "minecraft" : id.substring(0, separator);
    }
}
