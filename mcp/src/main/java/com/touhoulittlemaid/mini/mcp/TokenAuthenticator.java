package com.touhoulittlemaid.mini.mcp;

import java.util.Optional;

/** Resolves a bearer token into a principal. */
@FunctionalInterface
public interface TokenAuthenticator {

    Optional<TokenPrincipal> authenticate(String bearerToken);

    /** Development fallback: one environment token with the requested global scope. */
    static TokenAuthenticator staticToken(String token, TokenScope scope) {
        String digestId = AuditLog.digestId(token);
        byte[] digest = CompanionTokenStore.sha256(token);
        TokenPrincipal principal = new TokenPrincipal("env", digestId, scope, java.util.Set.of());
        return candidate -> java.security.MessageDigest.isEqual(digest,
                CompanionTokenStore.sha256(candidate))
                ? Optional.of(principal)
                : Optional.empty();
    }
}
