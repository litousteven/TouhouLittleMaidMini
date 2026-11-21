package com.touhoulittlemaid.mini.core.semantic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies a model answer to the knowledge store.
 *
 * <p>This is the only place where a model claim can become behaviour, so every rule that
 * matters lives here: it must answer about content we asked for, it must survive the
 * validator, and a hostile claim is never applied without the player.
 */
public final class KnowledgeIngest {

    public record IngestReport(String batch, int accepted, int pending, int rejected,
            Map<String, Integer> codes, List<String> details) {

        public IngestReport {
            codes = codes == null ? Map.of() : Map.copyOf(new TreeMap<>(codes));
            details = details == null ? List.of() : List.copyOf(details);
        }

        public static IngestReport empty() {
            return new IngestReport("", 0, 0, 0, Map.of(), List.of());
        }

        public String summary() {
            return "batch " + batch + ": " + accepted + " accepted, " + pending
                    + " pending, " + rejected + " rejected";
        }
    }

    private KnowledgeIngest() {
    }

    public static IngestReport ingest(KnowledgeBatch batch, KnowledgeBatchJson.Response response,
            RealFacts facts, KnowledgeStore store) {
        if (batch == null || response == null || store == null) {
            return IngestReport.empty();
        }
        Map<String, KnowledgeCandidate> requested = new LinkedHashMap<>();
        batch.items().forEach(item -> requested.put(item.key(), item));
        Set<String> unknown = new java.util.HashSet<>(response.unknown());

        int accepted = 0;
        int pending = 0;
        int rejected = 0;
        Map<String, Integer> codes = new HashMap<>();
        List<String> details = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();

        for (KnowledgeBatchJson.Claim claim : response.claims()) {
            KnowledgeCandidate candidate = find(requested, claim.id());
            if (candidate == null) {
                rejected++;
                codes.merge(KnowledgeCodes.UNEXPECTED_ID, 1, Integer::sum);
                details.add(claim.id() + ": rejected (unexpected_id)");
                continue;
            }
            if (!seen.add(candidate.key())) {
                details.add(claim.id() + ": duplicate claim ignored");
                continue;
            }
            SemanticEntry entry = new SemanticEntry(claim.id(), candidate.kind(), claim.tags(),
                    SemanticSource.MODEL, claim.confidence(), batch.id(), claim.reason());
            var validation = KnowledgeValidator.validate(entry, facts, store);
            if (validation.outcome() == KnowledgeValidator.Outcome.REJECTED) {
                rejected++;
                codes.merge(validation.code(), 1, Integer::sum);
                details.add(claim.id() + ": rejected (" + validation.code() + ") "
                        + validation.message());
                continue;
            }

            String forcedPending = null;
            if (entry.has(SemanticTag.HOSTILE) && facts.hostileExcluded(candidate.kind(),
                    candidate.id())) {
                rejected++;
                codes.merge(KnowledgeCodes.HOSTILE_EXCLUDED, 1, Integer::sum);
                details.add(claim.id() + ": rejected (hostile_excluded)");
                continue;
            }
            if (entry.has(SemanticTag.HOSTILE)) {
                forcedPending = KnowledgeCodes.HOSTILE_UNCONFIRMED;
            } else if (unknown.contains(claim.id())) {
                forcedPending = KnowledgeCodes.MODEL_UNKNOWN;
            } else if (validation.outcome() == KnowledgeValidator.Outcome.PENDING) {
                forcedPending = validation.code();
            }

            if (forcedPending == null) {
                store.put(entry);
                accepted++;
                details.add(claim.id() + ": accepted as " + entry.tags());
            } else {
                store.putPending(entry);
                pending++;
                codes.merge(forcedPending, 1, Integer::sum);
                details.add(claim.id() + ": pending (" + forcedPending + ")");
            }
        }

        for (String id : response.unknown()) {
            if (find(requested, id) == null) {
                details.add(id + ": unknown list mentions content that was not requested");
            }
        }
        return new IngestReport(batch.id(), accepted, pending, rejected, codes, details);
    }

    private static KnowledgeCandidate find(Map<String, KnowledgeCandidate> requested, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (KnowledgeCandidate candidate : requested.values()) {
            if (candidate.id().equals(id)) {
                return candidate;
            }
        }
        return null;
    }
}
