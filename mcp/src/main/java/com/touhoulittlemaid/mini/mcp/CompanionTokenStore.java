package com.touhoulittlemaid.mini.mcp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory per-companion bearer tokens.
 *
 * <p>Only a SHA-256 digest is stored. The plaintext is returned once at issue time and
 * can never be recovered. Tokens expire and can be revoked by id.
 */
public final class CompanionTokenStore implements TokenAuthenticator {

    public record Issued(String plaintext, Record record) {
    }

    /**
     * The non-secret state needed to survive a server restart.
     *
     * <p>{@code digest} stays a one-way hash: persisting it does not let anyone recover
     * the plaintext, so a restart can keep existing tokens valid without storing secrets.
     */
    public record Persisted(String id, String digestId, String digest, Set<UUID> companionIds,
            Set<com.touhoulittlemaid.mini.core.ActionKind> allowedActions,
            boolean canInteractWithWorld, long expiresAtEpochMilli) {

        public Persisted {
            companionIds = Set.copyOf(companionIds);
            allowedActions = Set.copyOf(allowedActions);
        }
    }

    public record Record(String id, String digestId, Set<UUID> companionIds,
            TokenScope scope, Instant expiresAt) {

        public Record {
            companionIds = Set.copyOf(companionIds);
        }

        public boolean isExpired(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    private record Stored(Record record, byte[] digest) {
    }

    private final Map<String, Stored> tokens = new LinkedHashMap<>();

    public synchronized Issued issue(Set<UUID> companionIds, TokenScope scope, Duration ttl) {
        String plaintext = newToken();
        String id = UUID.randomUUID().toString();
        Record record = new Record(id, AuditLog.digestId(plaintext), companionIds, scope,
                Instant.now().plus(ttl));
        tokens.put(id, new Stored(record, sha256(plaintext)));
        return new Issued(plaintext, record);
    }

    /** Development convenience: read-only token for one companion. */
    public synchronized Issued issueReadOnly(UUID companionId, Duration ttl) {
        return issue(Set.of(companionId), new TokenScope(
                Set.of(com.touhoulittlemaid.mini.core.ActionKind.OBSERVE), false), ttl);
    }

    public synchronized Optional<TokenPrincipal> authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) {
            return Optional.empty();
        }
        byte[] candidate = sha256(bearerToken);
        Instant now = Instant.now();
        for (Stored stored : tokens.values()) {
            if (stored.record().isExpired(now)) {
                continue;
            }
            if (!MessageDigest.isEqual(stored.digest(), candidate)) {
                continue;
            }
            Record record = stored.record();
            return Optional.of(new TokenPrincipal(record.id(), record.digestId(),
                    record.scope(), record.companionIds()));
        }
        return Optional.empty();
    }

    public synchronized boolean revoke(String id) {
        return tokens.remove(id) != null;
    }

    public synchronized List<Record> list() {
        Instant now = Instant.now();
        List<Record> result = new ArrayList<>();
        for (Stored stored : tokens.values()) {
            if (!stored.record().isExpired(now)) {
                result.add(stored.record());
            }
        }
        return List.copyOf(result);
    }

    public synchronized int pruneExpired() {
        Instant now = Instant.now();
        int before = tokens.size();
        tokens.entrySet().removeIf(entry -> entry.getValue().record().isExpired(now));
        return before - tokens.size();
    }

    /** Non-secret snapshot for persistence. Expired tokens are omitted. */
    public synchronized List<Persisted> export() {
        Instant now = Instant.now();
        List<Persisted> snapshot = new ArrayList<>();
        for (Stored stored : tokens.values()) {
            Record record = stored.record();
            if (record.isExpired(now)) {
                continue;
            }
            snapshot.add(new Persisted(record.id(), record.digestId(),
                    Base64.getEncoder().encodeToString(stored.digest()),
                    record.companionIds(), record.scope().allowedActions(),
                    record.scope().canInteractWithWorld(), record.expiresAt().toEpochMilli()));
        }
        return List.copyOf(snapshot);
    }

    /** Replaces the in-memory set with persisted entries; expired ones are dropped. */
    public synchronized void restore(List<Persisted> persisted) {
        tokens.clear();
        Instant now = Instant.now();
        for (Persisted entry : persisted) {
            Instant expiresAt = Instant.ofEpochMilli(entry.expiresAtEpochMilli());
            if (!now.isBefore(expiresAt)) {
                continue;
            }
            Record record = new Record(entry.id(), entry.digestId(), entry.companionIds(),
                    new TokenScope(entry.allowedActions(), entry.canInteractWithWorld()),
                    expiresAt);
            tokens.put(entry.id(), new Stored(record, Base64.getDecoder().decode(entry.digest())));
        }
    }

    /** Test helper: the scope a stored token carries. */
    public synchronized Optional<TokenScope> scopeOf(String id) {
        Stored stored = tokens.get(id);
        return stored == null ? Optional.empty() : Optional.of(stored.record().scope());
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Kept for callers that want a full-scope token without importing TokenScope. */
    public synchronized Issued issueFull(UUID companionId, Duration ttl) {
        return issue(Set.of(companionId), TokenScope.FULL, ttl);
    }

}
