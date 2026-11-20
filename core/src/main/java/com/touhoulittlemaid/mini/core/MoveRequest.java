package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * Typed request for {@code companion_move}.
 *
 * <p>{@code distance} is the stop distance in blocks. {@code replace = false} lets an
 * agent avoid interrupting an active body operation.
 */
public record MoveRequest(
        MoveTargetKind targetKind,
        int x,
        int y,
        int z,
        String entityId,
        MoveMode mode,
        double distance,
        boolean replace) {

    public MoveRequest {
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(mode, "mode");
        entityId = entityId == null ? "" : entityId.strip().toLowerCase();
        distance = Math.max(0.0D, Math.min(distance, 64.0D));
    }

    public boolean hasEntity() {
        return !entityId.isEmpty();
    }
}
