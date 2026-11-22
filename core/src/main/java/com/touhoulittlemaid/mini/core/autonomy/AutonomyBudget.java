package com.touhoulittlemaid.mini.core.autonomy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What a companion may spend on acting on its own.
 *
 * <p>Three separate brakes, because they fail differently: a cooldown stops the loop from
 * spinning, the hourly count stops a busy world from becoming a busy model, and the daily token
 * budget stops a long session from costing real money. A circuit breaker sits on top: three
 * consecutive failures pause autonomy for ten minutes instead of retrying forever.
 *
 * <p>Pure Java and time-injected, so the numbers can be tested without waiting an hour.
 */
public final class AutonomyBudget {

    public static final long COOLDOWN_MILLIS = 90_000L;
    public static final int MAX_RUNS_PER_HOUR = 6;
    public static final long MAX_TOKENS_PER_DAY = 150_000L;
    public static final long TIDY_INTERVAL_MILLIS = 12 * 60 * 60 * 1_000L;
    public static final int BREAKER_FAILURES = 3;
    /** docs/10 §11.4: three consecutive failures stop autonomy for fifteen minutes. */
    public static final long BREAKER_MILLIS = 15 * 60 * 1_000L;

    private final Deque<Long> runs = new ArrayDeque<>();
    private long lastRunAt;
    private long tidyLastAt;
    private long day = Long.MIN_VALUE;
    private long tokensToday;
    private int consecutiveFailures;
    private long pausedUntil;

    /** Why a run is not allowed right now, or null when it is. */
    public String blockReason(long now) {
        if (now < pausedUntil) {
            long seconds = Math.max(1, (pausedUntil - now) / 1_000L);
            return "paused for another " + seconds + "s";
        }
        if (lastRunAt > 0 && now - lastRunAt < COOLDOWN_MILLIS) {
            return "cooldown";
        }
        prune(now);
        if (runs.size() >= MAX_RUNS_PER_HOUR) {
            return "hourly_limit";
        }
        if (tokensToday(now) >= MAX_TOKENS_PER_DAY) {
            return "daily_tokens";
        }
        return null;
    }

    public boolean canRun(long now) {
        return blockReason(now) == null;
    }

    public void recordRun(long now) {
        lastRunAt = now;
        runs.addLast(now);
        prune(now);
    }

    public void recordTokens(long now, int tokens) {
        if (tokens <= 0) {
            return;
        }
        rollDay(now);
        tokensToday += tokens;
    }

    public long tokensToday(long now) {
        rollDay(now);
        return tokensToday;
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
        pausedUntil = 0;
    }

    /** Counts one failure and trips the breaker when the streak is long enough. */
    public void recordFailure(long now) {
        consecutiveFailures++;
        if (consecutiveFailures >= BREAKER_FAILURES) {
            pause(now, BREAKER_MILLIS);
            consecutiveFailures = 0;
        }
    }

    /** Stops all runs for {@code millis}, used by the breaker and by the operator pause. */
    public void pause(long now, long millis) {
        pausedUntil = Math.max(pausedUntil, now + Math.max(0L, millis));
    }

    public long pausedUntil() {
        return pausedUntil;
    }

    public boolean paused(long now) {
        return now < pausedUntil;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }

    public boolean tidyAllowed(long now) {
        return tidyLastAt == 0 || now - tidyLastAt >= TIDY_INTERVAL_MILLIS;
    }

    public void recordTidy(long now) {
        tidyLastAt = now;
    }

    public int runsLastHour(long now) {
        prune(now);
        return runs.size();
    }

    /** Used by tests and by {@code /tlmm autonomy reset}. */
    public void reset() {
        runs.clear();
        lastRunAt = 0;
        tidyLastAt = 0;
        tokensToday = 0;
        day = Long.MIN_VALUE;
        consecutiveFailures = 0;
        pausedUntil = 0;
    }

    private void prune(long now) {
        while (!runs.isEmpty() && now - runs.peekFirst() >= 60 * 60 * 1_000L) {
            runs.pollFirst();
        }
    }

    private void rollDay(long now) {
        long today = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toEpochDay();
        if (today != day) {
            day = today;
            tokensToday = 0;
        }
    }
}
