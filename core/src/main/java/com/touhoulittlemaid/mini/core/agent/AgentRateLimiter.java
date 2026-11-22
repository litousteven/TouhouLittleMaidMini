package com.touhoulittlemaid.mini.core.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;

/**
 * Rolling one-minute budget per action, per player.
 *
 * <p>Pure Java so the limits are testable without a server: the numbers are part of the
 * protocol, not an implementation detail. Each bucket is independent — a client that polls the
 * observation twenty times must not use up its plan submissions.
 */
public final class AgentRateLimiter {

    public static final int SUBMITS_PER_MINUTE = 6;
    public static final int OBSERVATIONS_PER_MINUTE = 20;
    public static final int KNOWLEDGE_PER_MINUTE = 6;
    public static final long WINDOW_MILLIS = 60_000L;

    public enum Bucket {
        SUBMIT(SUBMITS_PER_MINUTE),
        OBSERVE(OBSERVATIONS_PER_MINUTE),
        KNOWLEDGE(KNOWLEDGE_PER_MINUTE);

        private final int limit;

        Bucket(int limit) {
            this.limit = limit;
        }

        public int limit() {
            return limit;
        }
    }

    private final Map<Bucket, Deque<Long>> stamps = new EnumMap<>(Bucket.class);

    /** Records one use and reports whether it was inside the budget. */
    public synchronized boolean allow(Bucket bucket, long nowMillis) {
        Deque<Long> queue = stamps.computeIfAbsent(bucket, key -> new ArrayDeque<>());
        while (!queue.isEmpty() && nowMillis - queue.peekFirst() >= WINDOW_MILLIS) {
            queue.pollFirst();
        }
        if (queue.size() >= bucket.limit()) {
            return false;
        }
        queue.addLast(nowMillis);
        return true;
    }

    /** Uses left in the current window, used by the UI hint and by tests. */
    public synchronized int remaining(Bucket bucket, long nowMillis) {
        Deque<Long> queue = stamps.get(bucket);
        if (queue == null) {
            return bucket.limit();
        }
        while (!queue.isEmpty() && nowMillis - queue.peekFirst() >= WINDOW_MILLIS) {
            queue.pollFirst();
        }
        return Math.max(0, bucket.limit() - queue.size());
    }

    public synchronized void clear() {
        stamps.clear();
    }
}
