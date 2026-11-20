package com.touhoulittlemaid.mini.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One-shot human authorization for a destructive or authoritative action.
 *
 * <p>The agent can request but never approve. A decision applies to one companion and
 * one action fingerprint. Approval is consumed by the next matching request, so a
 * retried MCP call runs at most once.
 */
public final class PermissionBroker {

    public enum Decision {
        PENDING,
        ALLOWED,
        DENIED,
        UNKNOWN
    }

    public record Ticket(String id, String companionId, String action, Decision decision,
            long expiresAtMillis) {

        public Ticket {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(companionId, "companionId");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(decision, "decision");
        }
    }

    private record Entry(String companionId, String action, long expiresAtMillis,
            Decision decision, boolean consumed) {
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Returns an approved ticket once, a pending ticket while waiting, or a denied
     * ticket while the denial is still remembered.
     */
    public synchronized Ticket authorize(String companionId, String action, long ttlMillis) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(action, "action");
        prune();
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            Entry entry = candidate.getValue();
            if (!entry.companionId().equals(companionId) || !entry.action().equals(action)) {
                continue;
            }
            if (entry.decision() == Decision.ALLOWED) {
                entries.put(candidate.getKey(), new Entry(entry.companionId(), entry.action(),
                        entry.expiresAtMillis(), Decision.ALLOWED, true));
                return ticket(candidate.getKey(), entry, Decision.ALLOWED);
            }
            if (!entry.consumed()) {
                return ticket(candidate.getKey(), entry, entry.decision());
            }
        }
        String id = UUID.randomUUID().toString();
        long expiresAt = System.currentTimeMillis() + Math.max(1_000L, ttlMillis);
        Entry pending = new Entry(companionId, action, expiresAt, Decision.PENDING, false);
        entries.put(id, pending);
        return ticket(id, pending, Decision.PENDING);
    }

    public synchronized Ticket decide(String id, boolean allow) {
        prune();
        Entry entry = entries.get(id);
        if (entry == null || entry.consumed()) {
            return new Ticket(id, "", "", Decision.UNKNOWN, 0L);
        }
        Entry updated = new Entry(entry.companionId(), entry.action(), entry.expiresAtMillis(),
                allow ? Decision.ALLOWED : Decision.DENIED, false);
        entries.put(id, updated);
        return ticket(id, updated, updated.decision());
    }

    public synchronized Ticket status(String id) {
        prune();
        Entry entry = entries.get(id);
        return entry == null
                ? new Ticket(id, "", "", Decision.UNKNOWN, 0L)
                : ticket(id, entry, entry.decision());
    }

    private void prune() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(candidate ->
                candidate.getValue().expiresAtMillis() < now
                        || candidate.getValue().consumed());
    }

    private static Ticket ticket(String id, Entry entry, Decision decision) {
        return new Ticket(id, entry.companionId(), entry.action(), decision,
                entry.expiresAtMillis());
    }
}
