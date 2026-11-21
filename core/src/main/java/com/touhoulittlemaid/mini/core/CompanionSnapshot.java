package com.touhoulittlemaid.mini.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Minecraft-free snapshot of a companion's identity, local behaviour and schedule.
 */
public record CompanionSnapshot(
        UUID companionId,
        Optional<UUID> ownerId,
        String name,
        LocalBehavior behavior,
        CompanionSchedule schedule,
        boolean homeSet) {

    public CompanionSnapshot {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(behavior, "behavior");
        Objects.requireNonNull(schedule, "schedule");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public boolean hasOwner() {
        return ownerId.isPresent();
    }

    public boolean isOwnedBy(UUID candidate) {
        return ownerId.filter(candidate::equals).isPresent();
    }
}
