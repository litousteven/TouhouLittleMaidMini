package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionScheduleTest {

    @Test
    void allIsAlwaysActive() {
        assertTrue(CompanionSchedule.ALL.isActive(true));
        assertTrue(CompanionSchedule.ALL.isActive(false));
    }

    @Test
    void dayOnlyRunsWhileBright() {
        assertTrue(CompanionSchedule.DAY.isActive(true));
        assertFalse(CompanionSchedule.DAY.isActive(false));
    }

    @Test
    void nightOnlyRunsWhileDark() {
        assertFalse(CompanionSchedule.NIGHT.isActive(true));
        assertTrue(CompanionSchedule.NIGHT.isActive(false));
    }

    @Test
    void parsesIdsCaseAndWhitespaceInsensitively() {
        assertEquals(CompanionSchedule.DAY, CompanionSchedule.fromId(" DAY "));
        assertEquals(CompanionSchedule.NIGHT, CompanionSchedule.fromId("night"));
        assertEquals(CompanionSchedule.ALL, CompanionSchedule.fromId("all"));
    }

    @Test
    void rejectsUnknownSchedule() {
        assertThrows(IllegalArgumentException.class, () -> CompanionSchedule.fromId("noon"));
    }
}
