package com.touhoulittlemaid.mini.mcp;

import java.util.Set;
import java.util.UUID;

/**
 * An authenticated caller.
 *
 * <p>An empty companion set means "every companion" and is reserved for the legacy
 * environment token. Issued tokens always carry an explicit set.
 */
public record TokenPrincipal(String tokenId, String digestId, TokenScope scope,
        Set<UUID> companionIds) {

    public TokenPrincipal {
        java.util.Objects.requireNonNull(tokenId, "tokenId");
        java.util.Objects.requireNonNull(digestId, "digestId");
        java.util.Objects.requireNonNull(scope, "scope");
        companionIds = Set.copyOf(companionIds);
    }

    public boolean allowsCompanion(UUID companionId) {
        return companionIds.isEmpty() || companionIds.contains(companionId);
    }
}
