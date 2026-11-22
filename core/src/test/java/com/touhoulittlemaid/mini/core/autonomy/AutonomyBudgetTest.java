package com.touhoulittlemaid.mini.core.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutonomyBudgetTest {

    private static final long START = 1_700_000_000_000L;

    @Test
    void aFreshBudgetAllowsOneRun() {
        AutonomyBudget budget = new AutonomyBudget();
        assertNull(budget.blockReason(START));
        budget.recordRun(START);
        assertEquals("cooldown", budget.blockReason(START + 1_000L));
        assertNull(budget.blockReason(START + AutonomyBudget.COOLDOWN_MILLIS));
    }

    @Test
    void anHourHoldsSixRuns() {
        AutonomyBudget budget = new AutonomyBudget();
        long now = START;
        for (int run = 0; run < AutonomyBudget.MAX_RUNS_PER_HOUR; run++) {
            assertTrue(budget.canRun(now), "run " + run + " was blocked");
            budget.recordRun(now);
            budget.recordSuccess();
            now += AutonomyBudget.COOLDOWN_MILLIS + 1;
        }
        assertEquals("hourly_limit", budget.blockReason(now));
        assertTrue(budget.canRun(now + 60 * 60 * 1_000L));
    }

    @Test
    void tokensRollOverAtMidnight() {
        AutonomyBudget budget = new AutonomyBudget();
        budget.recordTokens(START, 100_000);
        assertEquals(100_000, budget.tokensToday(START));
        budget.recordTokens(START + 24 * 60 * 60 * 1_000L, 10);
        assertEquals(10, budget.tokensToday(START + 24 * 60 * 60 * 1_000L));
    }

    @Test
    void theDailyTokenBudgetBlocksAndThenReleases() {
        AutonomyBudget budget = new AutonomyBudget();
        budget.recordTokens(START, (int) AutonomyBudget.MAX_TOKENS_PER_DAY);
        assertEquals("daily_tokens", budget.blockReason(START + AutonomyBudget.COOLDOWN_MILLIS));
        long tomorrow = START + 24 * 60 * 60 * 1_000L;
        assertTrue(budget.canRun(tomorrow));
    }

    @Test
    void threeFailuresTripTheBreaker() {
        AutonomyBudget budget = new AutonomyBudget();
        budget.recordFailure(START);
        budget.recordFailure(START);
        assertFalse(budget.paused(START));
        budget.recordFailure(START);
        assertTrue(budget.paused(START));
        assertEquals(0, budget.consecutiveFailures(), "the streak resets when the breaker trips");
        assertTrue(budget.blockReason(START).startsWith("paused"));
        assertTrue(budget.canRun(START + AutonomyBudget.BREAKER_MILLIS));
    }

    @Test
    void aSuccessClearsTheStreak() {
        AutonomyBudget budget = new AutonomyBudget();
        budget.recordFailure(START);
        budget.recordFailure(START);
        budget.recordSuccess();
        budget.recordFailure(START);
        assertFalse(budget.paused(START));
    }

    @Test
    void tidyingIsAllowedTwiceADayAtMost() {
        AutonomyBudget budget = new AutonomyBudget();
        assertTrue(budget.tidyAllowed(START));
        budget.recordTidy(START);
        assertFalse(budget.tidyAllowed(START + 60 * 60 * 1_000L));
        assertTrue(budget.tidyAllowed(START + AutonomyBudget.TIDY_INTERVAL_MILLIS));
    }

    @Test
    void resetForgetsEverything() {
        AutonomyBudget budget = new AutonomyBudget();
        budget.recordRun(START);
        budget.recordTokens(START, 1_000);
        budget.recordTidy(START);
        budget.reset();
        assertEquals(0, budget.runsLastHour(START));
        assertEquals(0, budget.tokensToday(START));
        assertTrue(budget.tidyAllowed(START));
    }
}
