package com.touhoulittlemaid.mini.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks when an agent last touched a companion and whether it had started a body
 * operation. Pure model so the grace window can be unit tested without a server.
 */
public final class AgentPresence {

    private record Entry(long lastSeenMillis, boolean activeOperation, boolean disconnected) {
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final java.util.function.LongSupplier clock;

    public AgentPresence() {
        this(System::currentTimeMillis);
    }

    /** Test seam for a deterministic clock. */
    public AgentPresence(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void recordRead(UUID companionId) {
        Entry previous = entries.get(companionId);
        entries.put(companionId, new Entry(clock.getAsLong(),
                previous != null && previous.activeOperation(), false));
    }

    public synchronized void recordWrite(UUID companionId) {
        entries.put(companionId, new Entry(clock.getAsLong(), true, false));
    }

    public synchronized void recordStop(UUID companionId) {
        entries.put(companionId, new Entry(clock.getAsLong(), false, false));
    }

    /** Companions whose active operation outlived the grace window. */
    public synchronized List<UUID> dueForDisconnect(long graceMillis) {
        long now = clock.getAsLong();
        List<UUID> due = new ArrayList<>();
        for (Map.Entry<UUID, Entry> entry : entries.entrySet()) {
            Entry value = entry.getValue();
            if (!value.disconnected() && value.activeOperation()
                    && now - value.lastSeenMillis() > Math.max(0L, graceMillis)) {
                due.add(entry.getKey());
            }
        }
        return due;
    }

    public synchronized void markDisconnected(UUID companionId) {
        Entry previous = entries.get(companionId);
        if (previous != null) {
            entries.put(companionId, new Entry(previous.lastSeenMillis(), false, true));
        }
    }

    public synchronized boolean isDisconnected(UUID companionId) {
        Entry entry = entries.get(companionId);
        return entry != null && entry.disconnected();
    }

    public synchronized void clear() {
        entries.clear();
    }
}
