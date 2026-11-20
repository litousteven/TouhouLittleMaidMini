package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * Typed request for {@code companion_interact}.
 *
 * <p>This is one player-like click, not a whole gameplay routine. Farming and similar
 * flows are compositions the agent builds from move, interact and inventory actions.
 */
public record InteractRequest(
        InteractTargetKind targetKind,
        int x,
        int y,
        int z,
        String entityId,
        InteractButton button,
        InteractHand hand,
        boolean sneak,
        boolean replace) {

    public InteractRequest {
        Objects.requireNonNull(targetKind, "targetKind");
        Objects.requireNonNull(button, "button");
        Objects.requireNonNull(hand, "hand");
        entityId = entityId == null ? "" : entityId.strip().toLowerCase();
    }

    public boolean hasEntity() {
        return !entityId.isEmpty();
    }
}
