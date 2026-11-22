package com.touhoulittlemaid.mini.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.touhoulittlemaid.mini.core.agent.AgentRateLimiter.Bucket;

import org.junit.jupiter.api.Test;

class AgentRateLimiterTest {

    private static final long START = 1_000_000L;

    @Test
    void aBucketStopsAtItsLimit() {
        AgentRateLimiter limiter = new AgentRateLimiter();
        for (int index = 0; index < AgentRateLimiter.SUBMITS_PER_MINUTE; index++) {
            assertTrue(limiter.allow(Bucket.SUBMIT, START + index));
        }
        assertFalse(limiter.allow(Bucket.SUBMIT, START + 100));
        assertEquals(0, limiter.remaining(Bucket.SUBMIT, START + 100));
    }

    @Test
    void theWindowRollsForward() {
        AgentRateLimiter limiter = new AgentRateLimiter();
        for (int index = 0; index < AgentRateLimiter.SUBMITS_PER_MINUTE; index++) {
            assertTrue(limiter.allow(Bucket.SUBMIT, START));
        }
        assertFalse(limiter.allow(Bucket.SUBMIT, START + AgentRateLimiter.WINDOW_MILLIS - 1));
        assertTrue(limiter.allow(Bucket.SUBMIT, START + AgentRateLimiter.WINDOW_MILLIS));
    }

    @Test
    void bucketsDoNotShareABudget() {
        AgentRateLimiter limiter = new AgentRateLimiter();
        for (int index = 0; index < AgentRateLimiter.OBSERVATIONS_PER_MINUTE; index++) {
            assertTrue(limiter.allow(Bucket.OBSERVE, START + index));
        }
        assertFalse(limiter.allow(Bucket.OBSERVE, START + 30));
        // Reading the world must not cost plan submissions.
        assertTrue(limiter.allow(Bucket.SUBMIT, START + 30));
    }

    @Test
    void remainingCountsDownAndBack() {
        AgentRateLimiter limiter = new AgentRateLimiter();
        assertEquals(20, limiter.remaining(Bucket.OBSERVE, START));
        limiter.allow(Bucket.OBSERVE, START);
        limiter.allow(Bucket.OBSERVE, START);
        assertEquals(18, limiter.remaining(Bucket.OBSERVE, START + 1));
        assertEquals(20, limiter.remaining(Bucket.OBSERVE, START + 120_000L));
    }
}
