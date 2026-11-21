package com.touhoulittlemaid.mini.core.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cost control: asked memory, one batch per minute, per session and per day budgets. */
class ScanLedgerTest {

    private static final Instant START = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void askedContentIsNotPaidForTwiceWithinAWeek() {
        ScanLedger ledger = new ScanLedger();
        ledger.markAsked(SemanticKind.BLOCK, "mod:bush", START);

        assertTrue(ledger.recentlyAsked(SemanticKind.BLOCK, "mod:bush", START.plusSeconds(60)));
        assertTrue(ledger.recentlyAsked(SemanticKind.BLOCK, "mod:bush",
                START.plus(ScanLedger.ASKED_TTL).minusSeconds(1)));
        assertFalse(ledger.recentlyAsked(SemanticKind.BLOCK, "mod:bush",
                START.plus(ScanLedger.ASKED_TTL).plusSeconds(1)));
        assertFalse(ledger.recentlyAsked(SemanticKind.ITEM, "mod:bush", START),
                "the same id as another kind is different content");
    }

    @Test
    void onlyOneBatchLeavesPerMinute() {
        ScanLedger ledger = new ScanLedger();
        ledger.beginSession("s1");
        ledger.recordBatch(START);

        var denied = ledger.quota(START.plusSeconds(59));
        assertFalse(denied.allowed());
        assertEquals(KnowledgeCodes.RATE_LIMITED, denied.code());
        assertTrue(ledger.quota(START.plusSeconds(60)).allowed());
    }

    @Test
    void aSessionGetsFourBatches() {
        ScanLedger ledger = new ScanLedger();
        ledger.beginSession("s1");
        Instant now = START;
        for (int index = 0; index < ScanLedger.MAX_PER_SESSION; index++) {
            assertTrue(ledger.quota(now).allowed(), "batch " + index + " should be allowed");
            ledger.recordBatch(now);
            now = now.plusSeconds(61);
        }

        var denied = ledger.quota(now);
        assertFalse(denied.allowed());
        assertEquals(KnowledgeCodes.SESSION_QUOTA, denied.code());

        ledger.beginSession("s2");
        assertTrue(ledger.quota(now).allowed(), "a new login gets a new session budget");
    }

    @Test
    void aDayGetsTwentyBatchesAndResetsAtUtcMidnight() {
        ScanLedger ledger = new ScanLedger();
        Instant now = START;
        int issued = 0;
        // The day budget is the only thing under test, so start a fresh login whenever the
        // session budget runs out instead of letting the loop spin on a denial.
        while (issued < ScanLedger.MAX_PER_DAY) {
            ledger.beginSession("session-" + (issued / ScanLedger.MAX_PER_SESSION));
            if (ledger.quota(now).allowed()) {
                ledger.recordBatch(now);
                issued++;
            }
            now = now.plusSeconds(61);
            if (now.isAfter(START.plusSeconds(86_400))) {
                throw new AssertionError("the daily budget never allowed a batch");
            }
        }

        // A fresh login has a full session budget, so the only thing left is the day.
        ledger.beginSession("final");
        var denied = ledger.quota(now);
        assertFalse(denied.allowed());
        assertEquals(KnowledgeCodes.DAILY_QUOTA, denied.code());
        assertEquals(ScanLedger.MAX_PER_DAY, ledger.batchesToday(now));

        Instant tomorrow = now.plusSeconds(86_400);
        assertTrue(ledger.quota(tomorrow).allowed());
        assertEquals(0, ledger.batchesToday(tomorrow));
    }

    @Test
    void expiredMemoryIsPruned() {
        ScanLedger ledger = new ScanLedger();
        ledger.markAsked(SemanticKind.BLOCK, "mod:old", START);
        ledger.markAsked(SemanticKind.BLOCK, "mod:new", START.plusSeconds(10));

        assertEquals(1, ledger.prune(START.plus(ScanLedger.ASKED_TTL).plusSeconds(1)));
        assertEquals(1, ledger.askedCount());
        assertTrue(ledger.recentlyAsked(SemanticKind.BLOCK, "mod:new",
                START.plus(ScanLedger.ASKED_TTL).plusSeconds(2)));
    }

    @Test
    void ledgerSurvivesARestart(@TempDir Path directory) throws Exception {
        ScanLedger ledger = new ScanLedger();
        ledger.beginSession("s1");
        ledger.markAsked(SemanticKind.ITEM, "mod:cabbage", START);
        ledger.recordBatch(START);
        ledger.save(directory);

        assertTrue(Files.isRegularFile(directory.resolve("ledger.json")));
        ScanLedger reloaded = ScanLedger.load(directory);
        assertTrue(reloaded.recentlyAsked(SemanticKind.ITEM, "mod:cabbage", START.plusSeconds(60)));
        assertEquals(1, reloaded.batchesToday(START.plusSeconds(60)));
        assertFalse(reloaded.quota(START.plusSeconds(30)).allowed(),
                "the one-per-minute gap survives a restart");
    }

    @Test
    void missingOrBrokenLedgerIsJustAnEmptyOne(@TempDir Path directory) throws Exception {
        assertEquals(0, ScanLedger.load(directory).askedCount());

        Files.writeString(directory.resolve("ledger.json"), "{broken");
        assertEquals(0, ScanLedger.load(directory).askedCount());
    }
}
