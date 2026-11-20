package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoveInteractRequestTest {

    @Test
    void moveEnumsParse() {
        for (MoveTargetKind kind : MoveTargetKind.values()) {
            assertEquals(kind, MoveTargetKind.fromId(kind.id().toUpperCase()));
        }
        for (MoveMode mode : MoveMode.values()) {
            assertEquals(mode, MoveMode.fromId(mode.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> MoveMode.fromId("sidestep"));
    }

    @Test
    void moveRequestNormalisesEntityAndClampsDistance() {
        MoveRequest request = new MoveRequest(MoveTargetKind.ENTITY, 0, 0, 0,
                "  ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB ", MoveMode.AWAY, 999.0D, false);
        assertEquals("abcdefab-cdef-abcd-efab-cdefabcdefab", request.entityId());
        assertEquals(64.0D, request.distance());
        assertTrue(request.hasEntity());
        assertFalse(request.replace());

        MoveRequest negative = new MoveRequest(MoveTargetKind.BLOCK, 1, 2, 3,
                "", MoveMode.GOTO, -4.0D, true);
        assertEquals(0.0D, negative.distance());
        assertFalse(negative.hasEntity());
    }

    @Test
    void interactEnumsParse() {
        for (InteractTargetKind kind : InteractTargetKind.values()) {
            assertEquals(kind, InteractTargetKind.fromId(kind.id()));
        }
        for (InteractButton button : InteractButton.values()) {
            assertEquals(button, InteractButton.fromId(button.id()));
        }
        for (InteractHand hand : InteractHand.values()) {
            assertEquals(hand, InteractHand.fromId(hand.id()));
        }
        assertThrows(IllegalArgumentException.class, () -> InteractButton.fromId("middle"));
    }

    @Test
    void interactRequestKeepsFlags() {
        InteractRequest request = new InteractRequest(InteractTargetKind.BLOCK, 4, 64, -3,
                "", InteractButton.RIGHT, InteractHand.OFF, true, false);
        assertEquals(InteractTargetKind.BLOCK, request.targetKind());
        assertEquals(InteractButton.RIGHT, request.button());
        assertEquals(InteractHand.OFF, request.hand());
        assertTrue(request.sneak());
        assertFalse(request.replace());
        assertFalse(request.hasEntity());
    }
}
