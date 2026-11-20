package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformStateTest {

    @Test
    void readyStatePreservesPlatformIdentity() {
        PlatformState ready = PlatformState.starting("0.1.0-alpha.1", "26.3").ready();

        assertTrue(ready.serverReady());
        assertTrue(ready.modVersion().equals("0.1.0-alpha.1"));
        assertTrue(ready.minecraftVersion().equals("26.3"));
        assertTrue(ready.message().equals("ok"));
    }

    @Test
    void startingStateIsNotReady() {
        assertFalse(PlatformState.starting("dev", "26.3").serverReady());
    }

    @Test
    void stateRejectsMissingFields() {
        assertThrows(NullPointerException.class,
                () -> new PlatformState(null, "26.3", false, "starting"));
    }
}
