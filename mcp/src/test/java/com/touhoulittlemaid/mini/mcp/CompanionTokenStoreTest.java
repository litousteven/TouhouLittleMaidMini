package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CompanionTokenStoreTest {

    private static final UUID ALICE =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID BOB =
            UUID.fromString("99999999-8888-7777-6666-555555555555");

    @Test
    void issuedTokenAuthenticatesForItsCompanions() {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issueFull(ALICE, Duration.ofDays(1));

        var principal = store.authenticate(issued.plaintext()).orElseThrow();
        assertEquals(issued.record().id(), principal.tokenId());
        assertTrue(principal.allowsCompanion(ALICE));
        assertFalse(principal.allowsCompanion(BOB));
        assertEquals(issued.record().digestId(), principal.digestId());
    }

    @Test
    void wrongAndBlankTokensAreRejected() {
        CompanionTokenStore store = new CompanionTokenStore();
        store.issueFull(ALICE, Duration.ofDays(1));
        assertTrue(store.authenticate("wrong").isEmpty());
        assertTrue(store.authenticate("").isEmpty());
        assertTrue(store.authenticate(null).isEmpty());
    }

    @Test
    void revokedTokensStopWorking() {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issueFull(ALICE, Duration.ofDays(1));
        assertTrue(store.revoke(issued.record().id()));
        assertTrue(store.authenticate(issued.plaintext()).isEmpty());
        assertFalse(store.revoke(issued.record().id()));
    }

    @Test
    void expiredTokensAreRejectedAndRemovedFromTheList() throws Exception {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issueFull(ALICE, Duration.ofMillis(50L));
        Thread.sleep(80L);
        assertTrue(store.authenticate(issued.plaintext()).isEmpty());
        assertTrue(store.list().isEmpty());
        assertEquals(1, store.pruneExpired());
    }

    @Test
    void readOnlyIssuanceKeepsTheObserveScope() {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issueReadOnly(ALICE, Duration.ofDays(1));
        var principal = store.authenticate(issued.plaintext()).orElseThrow();
        assertTrue(principal.scope().allows(com.touhoulittlemaid.mini.core.ActionKind.OBSERVE));
        assertFalse(principal.scope().allows(com.touhoulittlemaid.mini.core.ActionKind.INTERACT));
    }

    @Test
    void tokensCanCoverSeveralCompanions() {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issue(Set.of(ALICE, BOB), TokenScope.FULL,
                Duration.ofDays(1));
        var principal = store.authenticate(issued.plaintext()).orElseThrow();
        assertTrue(principal.allowsCompanion(ALICE));
        assertTrue(principal.allowsCompanion(BOB));
    }

    @Test
    void exportedTokensSurviveRestoreWithoutThePlaintext() {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issue(Set.of(ALICE), TokenScope.READ_ONLY,
                Duration.ofDays(1));
        var exported = store.export();
        assertEquals(1, exported.size());
        assertFalse(exported.get(0).digest().contains(issued.plaintext()));

        CompanionTokenStore restarted = new CompanionTokenStore();
        restarted.restore(exported);
        var principal = restarted.authenticate(issued.plaintext()).orElseThrow();
        assertEquals(issued.record().id(), principal.tokenId());
        assertTrue(principal.scope().allows(com.touhoulittlemaid.mini.core.ActionKind.OBSERVE));
        assertFalse(principal.scope().allows(com.touhoulittlemaid.mini.core.ActionKind.INTERACT));
        assertFalse(principal.scope().canInteractWithWorld());
    }

    @Test
    void restoreDropsExpiredEntries() {
        CompanionTokenStore store = new CompanionTokenStore();
        store.issueFull(ALICE, Duration.ofMillis(1L));
        List<CompanionTokenStore.Persisted> exported = store.export();

        CompanionTokenStore restarted = new CompanionTokenStore();
        restarted.restore(exported.stream()
                .map(entry -> new CompanionTokenStore.Persisted(entry.id(), entry.digestId(),
                        entry.digest(), entry.companionIds(), entry.allowedActions(),
                        entry.canInteractWithWorld(), 0L))
                .toList());
        assertTrue(restarted.list().isEmpty());
    }
}
