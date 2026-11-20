package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class AgentPresenceTest {

    private static final UUID ALICE =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void readsDoNotCreateAnActiveOperation() {
        AtomicLong clock = new AtomicLong(0L);
        AgentPresence presence = new AgentPresence(clock::get);
        presence.recordRead(ALICE);
        clock.addAndGet(10_000L);
        assertTrue(presence.dueForDisconnect(1_000L).isEmpty());
    }

    @Test
    void activeWritesBecomeDueAfterTheGraceWindow() {
        AtomicLong clock = new AtomicLong(0L);
        AgentPresence presence = new AgentPresence(clock::get);
        presence.recordWrite(ALICE);
        clock.addAndGet(500L);
        assertTrue(presence.dueForDisconnect(1_000L).isEmpty());

        clock.addAndGet(600L);
        assertEquals(1, presence.dueForDisconnect(1_000L).size());
        presence.markDisconnected(ALICE);
        assertTrue(presence.isDisconnected(ALICE));
        assertTrue(presence.dueForDisconnect(1_000L).isEmpty());
    }

    @Test
    void newActivityReactivatesAfterDisconnect() {
        AtomicLong clock = new AtomicLong(0L);
        AgentPresence presence = new AgentPresence(clock::get);
        presence.recordWrite(ALICE);
        clock.addAndGet(5_000L);
        presence.markDisconnected(ALICE);
        presence.recordRead(ALICE);
        assertFalse(presence.isDisconnected(ALICE));
    }

    @Test
    void stopClearsTheActiveOperation() {
        AtomicLong clock = new AtomicLong(0L);
        AgentPresence presence = new AgentPresence(clock::get);
        presence.recordWrite(ALICE);
        presence.recordStop(ALICE);
        clock.addAndGet(10_000L);
        assertTrue(presence.dueForDisconnect(1_000L).isEmpty());
    }
}
