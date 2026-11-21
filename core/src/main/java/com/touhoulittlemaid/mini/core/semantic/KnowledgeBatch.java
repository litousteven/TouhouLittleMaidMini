package com.touhoulittlemaid.mini.core.semantic;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One annotation request: at most {@value #MAX_ITEMS} items, one batch id.
 *
 * <p>The batch id is what makes a model run auditable and reversible. Duplicate ids are
 * dropped while building, so a scanner that sees the same block twice cannot inflate a
 * request or the token bill.
 */
public record KnowledgeBatch(String id, List<KnowledgeCandidate> items) {

    public static final int MAX_ITEMS = 64;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    /**
     * Second resolution alone collides: two rounds can leave within the same second.
     *
     * <p>A batch id is what a rollback targets, so two batches sharing one id would make
     * {@code undo} remove somebody else's rows. The sequence keeps them distinct.
     */
    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    public KnowledgeBatch {
        id = id == null || id.isBlank() ? newId(Instant.now()) : id;
        Set<String> seen = new LinkedHashSet<>();
        List<KnowledgeCandidate> unique = new java.util.ArrayList<>();
        if (items != null) {
            for (KnowledgeCandidate item : items) {
                if (item == null || item.id().isBlank() || !seen.add(item.key())) {
                    continue;
                }
                unique.add(item);
                if (unique.size() == MAX_ITEMS) {
                    break;
                }
            }
        }
        items = List.copyOf(unique);
    }

    public static KnowledgeBatch of(List<KnowledgeCandidate> items) {
        return new KnowledgeBatch(newId(Instant.now()), items);
    }

    public static String newId(Instant now) {
        int sequence = SEQUENCE.incrementAndGet() % 10_000;
        return "kb-" + STAMP.format(now == null ? Instant.now() : now) + "-"
                + String.format(java.util.Locale.ROOT, "%04d", sequence);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        items.forEach(item -> ids.add(item.id()));
        return ids;
    }
}
