package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PermissionBrokerTest {

    @Test
    void requestWaitsUntilApprovedThenAllowsOnce() {
        PermissionBroker broker = new PermissionBroker();
        PermissionBroker.Ticket pending = broker.authorize("alice", "inventory.drop:wheat", 5_000L);
        assertEquals(PermissionBroker.Decision.PENDING, pending.decision());

        PermissionBroker.Ticket approved = broker.decide(pending.id(), true);
        assertEquals(PermissionBroker.Decision.ALLOWED, approved.decision());

        PermissionBroker.Ticket allowed = broker.authorize("alice", "inventory.drop:wheat", 5_000L);
        assertEquals(PermissionBroker.Decision.ALLOWED, allowed.decision());

        PermissionBroker.Ticket second = broker.authorize("alice", "inventory.drop:wheat", 5_000L);
        assertEquals(PermissionBroker.Decision.PENDING, second.decision());
    }

    @Test
    void denialIsRememberedUntilItExpires() {
        PermissionBroker broker = new PermissionBroker();
        PermissionBroker.Ticket pending = broker.authorize("alice", "inventory.drop:dirt", 5_000L);
        broker.decide(pending.id(), false);

        PermissionBroker.Ticket denied = broker.authorize("alice", "inventory.drop:dirt", 5_000L);
        assertEquals(PermissionBroker.Decision.DENIED, denied.decision());
    }

    @Test
    void decisionsAreScopedByCompanionAndAction() {
        PermissionBroker broker = new PermissionBroker();
        PermissionBroker.Ticket pending = broker.authorize("alice", "inventory.drop:dirt", 5_000L);
        broker.decide(pending.id(), true);

        assertEquals(PermissionBroker.Decision.PENDING,
                broker.authorize("bob", "inventory.drop:dirt", 5_000L).decision());
        assertEquals(PermissionBroker.Decision.PENDING,
                broker.authorize("alice", "inventory.drop:wheat", 5_000L).decision());
    }

    @Test
    void expiredRequestsDisappear() throws Exception {
        PermissionBroker broker = new PermissionBroker();
        PermissionBroker.Ticket pending = broker.authorize("alice", "inventory.drop:dirt", 1_000L);
        Thread.sleep(1_100L);
        assertEquals(PermissionBroker.Decision.UNKNOWN, broker.status(pending.id()).decision());
        assertEquals(PermissionBroker.Decision.PENDING,
                broker.authorize("alice", "inventory.drop:dirt", 5_000L).decision());
    }

    @Test
    void unknownDecisionIsReported() {
        PermissionBroker broker = new PermissionBroker();
        assertEquals(PermissionBroker.Decision.UNKNOWN, broker.decide("missing", true).decision());
        assertTrue(broker.status("missing").companionId().isEmpty());
    }
}
