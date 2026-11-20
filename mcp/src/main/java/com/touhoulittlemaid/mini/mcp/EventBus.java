package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.CompanionEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, monotonic event log backing {@code companion_wait_events}.
 *
 * <p>A consumer passes the last sequence it saw and receives only newer events. Cursors
 * older than the retention window are reported as expired rather than silently skipped,
 * so the agent rebuilds state with {@code companion_state}.
 */
public final class EventBus {

    /** Default number of events kept per companion. */
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private long lastSequence;
    private final Deque<CompanionEvent> events = new ArrayDeque<>();

    public EventBus() {
        this(DEFAULT_CAPACITY);
    }

    public EventBus(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized CompanionEvent publish(String companionId, String kind, String data) {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(data, "data");

        lastSequence++;
        CompanionEvent event = new CompanionEvent(lastSequence, companionId, kind, data);
        events.addLast(event);
        while (events.size() > capacity) {
            events.removeFirst();
        }
        return event;
    }

    public synchronized long lastSequence() {
        return lastSequence;
    }

    /** Oldest sequence still retained, or the next sequence when the log is empty. */
    public synchronized long oldestRetainedSequence() {
        return events.isEmpty() ? lastSequence + 1 : events.peekFirst().sequence();
    }

    /**
     * Events after {@code afterSequence}, capped at {@code maxEvents}.
     *
     * @return the events, or an empty list when the cursor has expired; callers must
     *         check {@link #cursorExpired(long)} first to distinguish the two
     */
    public synchronized List<CompanionEvent> after(long afterSequence, int maxEvents) {
        List<CompanionEvent> selected = new ArrayList<>();
        for (CompanionEvent event : events) {
            if (event.sequence() <= afterSequence) {
                continue;
            }
            if (selected.size() >= maxEvents) {
                break;
            }
            selected.add(event);
        }
        return selected;
    }

    /**
     * A cursor is expired when it is behind the retention window. A cursor equal to or
     * newer than the newest sequence is simply caught up, not expired.
     */
    public synchronized boolean cursorExpired(long afterSequence) {
        if (afterSequence < 0) {
            return false;
        }
        return afterSequence < oldestRetainedSequence() - 1 && !events.isEmpty();
    }
}
