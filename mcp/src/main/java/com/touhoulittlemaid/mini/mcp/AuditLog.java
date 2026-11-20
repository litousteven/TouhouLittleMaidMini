package com.touhoulittlemaid.mini.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * Structured audit line for every tool request.
 *
 * <p>Logs only a request id, a token digest id, the tool name, the companion id and the
 * outcome. The token itself, chat text, inventories and absolute paths never appear.
 */
public final class AuditLog {

    /** Receives formatted audit records. */
    @FunctionalInterface
    public interface Sink {
        void record(AuditEvent event);
    }

    /**
     * @param tokenDigestId short, non-reversible identifier for the presented token
     */
    public record AuditEvent(
            String requestId,
            String tokenDigestId,
            String toolName,
            String companionId,
            String status,
            String code,
            long durationMillis) {

        public AuditEvent {
            Objects.requireNonNull(tokenDigestId, "tokenDigestId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(code, "code");
        }

        public String format() {
            return "audit request=" + (requestId == null ? "-" : requestId)
                    + " token=" + tokenDigestId
                    + " tool=" + toolName
                    + " companion=" + (companionId == null ? "-" : companionId)
                    + " status=" + status
                    + " code=" + code
                    + " durationMs=" + durationMillis;
        }
    }

    private AuditLog() {
    }

    /** Stable, non-reversible short id for a bearer token. */
    public static String digestId(String token) {
        Objects.requireNonNull(token, "token");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** Extracts the companion id from decoded arguments, when present. */
    public static Optional<String> companionId(java.util.Map<String, Object> arguments) {
        Object raw = arguments.get("companion_id");
        return raw instanceof String text ? Optional.of(text) : Optional.empty();
    }
}
