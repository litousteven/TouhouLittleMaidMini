package com.touhoulittlemaid.mini.core.semantic;

import java.util.Optional;
import java.util.Set;

/**
 * The single resolver every semantic question goes through.
 *
 * <p>Order is fixed: real data, real tags, player overlay, model overlay, heuristic, then
 * unknown. An overlay can add meaning; it can never subtract or contradict registry truth.
 */
public final class SemanticTags {

    public static final double HEURISTIC_CONFIDENCE = 0.4D;

    private SemanticTags() {
    }

    public static SemanticResolution resolve(String id, SemanticKind kind, RealFacts facts,
            KnowledgeStore store) {
        if (id == null || id.isBlank() || kind == null) {
            return SemanticResolution.unknown(String.valueOf(id), kind);
        }
        Set<SemanticTag> data = facts.realData(kind, id);
        if (!data.isEmpty()) {
            return new SemanticResolution(id, kind, data, SemanticSource.DATA, 1.0D);
        }
        Set<SemanticTag> tags = facts.realTags(kind, id);
        if (!tags.isEmpty()) {
            return new SemanticResolution(id, kind, tags, SemanticSource.TAG, 1.0D);
        }
        Optional<SemanticEntry> user = store.lookup(id, kind, SemanticSource.USER);
        if (user.isPresent()) {
            SemanticEntry entry = user.get();
            return new SemanticResolution(id, kind, entry.tags(), SemanticSource.USER,
                    entry.confidence());
        }
        Optional<SemanticEntry> model = store.lookup(id, kind, SemanticSource.MODEL);
        if (model.isPresent()) {
            SemanticEntry entry = model.get();
            return new SemanticResolution(id, kind, entry.tags(), SemanticSource.MODEL,
                    entry.confidence());
        }
        Set<SemanticTag> heuristic = facts.heuristic(kind, id);
        if (!heuristic.isEmpty()) {
            return new SemanticResolution(id, kind, heuristic, SemanticSource.HEURISTIC,
                    HEURISTIC_CONFIDENCE);
        }
        return SemanticResolution.unknown(id, kind);
    }
}
