package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CompanionModelTest {

    @Test
    void behaviorIdsRoundTrip() {
        for (LocalBehavior behavior : LocalBehavior.values()) {
            assertEquals(behavior, LocalBehavior.fromId(behavior.id()));
        }
        assertEquals(LocalBehavior.FOLLOW, LocalBehavior.fromId("follow"));
    }

    @Test
    void unknownBehaviorIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LocalBehavior.fromId("mine"));
    }

    @Test
    void inventoryLayoutCoversBackpackAndEquipment() {
        assertEquals(9, CompanionInventoryLayout.BACKPACK_SLOTS);
        assertEquals(6, CompanionInventoryLayout.EQUIPMENT_SLOTS);
        assertEquals(15, CompanionInventoryLayout.TOTAL_SLOTS);
    }

    @Test
    void snapshotReportsOwnership() {
        UUID companion = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        CompanionSnapshot snapshot = new CompanionSnapshot(
                companion, Optional.of(owner), "Alice", LocalBehavior.STAY, true);

        assertTrue(snapshot.hasOwner());
        assertTrue(snapshot.isOwnedBy(owner));
        assertFalse(snapshot.isOwnedBy(UUID.randomUUID()));
        assertTrue(snapshot.homeSet());
    }

    @Test
    void snapshotWithoutOwnerNeverMatches() {
        CompanionSnapshot snapshot = new CompanionSnapshot(
                UUID.randomUUID(), Optional.empty(), "Stray", LocalBehavior.GUARD, false);

        assertFalse(snapshot.hasOwner());
        assertFalse(snapshot.isOwnedBy(UUID.randomUUID()));
        assertFalse(snapshot.homeSet());
    }

    @Test
    void snapshotRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionSnapshot(
                UUID.randomUUID(), Optional.empty(), "  ", LocalBehavior.STAY, false));
        assertThrows(NullPointerException.class, () -> new CompanionSnapshot(
                null, Optional.empty(), "Alice", LocalBehavior.STAY, false));
        assertThrows(NullPointerException.class, () -> new CompanionSnapshot(
                UUID.randomUUID(), null, "Alice", LocalBehavior.STAY, false));
    }
}
