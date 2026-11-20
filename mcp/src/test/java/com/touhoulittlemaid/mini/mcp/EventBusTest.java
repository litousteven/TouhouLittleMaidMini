package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class EventBusTest {

    @Test
    void afterFiltersByCompanion() {
        EventBus bus = new EventBus();
        bus.publish("alice", "behavior_changed", "{}");
        bus.publish("bob", "behavior_changed", "{}");
        bus.publish("alice", "operation_completed", "{}");

        List<com.touhoulittlemaid.mini.core.CompanionEvent> alice =
                bus.after("alice", 0, 10);
        assertEquals(2, alice.size());
        assertTrue(alice.stream().allMatch(event -> "alice".equals(event.companionId())));
        assertEquals(1, bus.after("bob", 0, 10).size());
        assertEquals(1, bus.after("alice", 1, 10).size());
    }

    @Test
    void awaitWakesUpWhenAnEventArrives() throws Exception {
        EventBus bus = new EventBus();
        Thread publisher = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            bus.publish("alice", "operation_completed", "{}");
        });

        long startedAt = System.nanoTime();
        List<com.touhoulittlemaid.mini.core.CompanionEvent> events =
                bus.await("alice", 0, 2_000L, 10);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        publisher.join();

        assertEquals(1, events.size());
        assertTrue(elapsedMillis < 1_500L, "await should return before the timeout");
    }

    @Test
    void awaitTimesOutWithoutEvents() {
        EventBus bus = new EventBus();
        List<com.touhoulittlemaid.mini.core.CompanionEvent> events =
                bus.await("alice", 0, 120L, 10);
        assertTrue(events.isEmpty());
    }

    @Test
    void awaitIgnoresOtherCompanions() throws Exception {
        EventBus bus = new EventBus();
        Thread publisher = Thread.ofVirtual().start(() ->
                bus.publish("bob", "operation_completed", "{}"));
        publisher.join();

        List<com.touhoulittlemaid.mini.core.CompanionEvent> events =
                bus.await("alice", 0, 120L, 10);
        assertTrue(events.isEmpty());
        assertFalse(bus.cursorExpired(0));
    }
}
