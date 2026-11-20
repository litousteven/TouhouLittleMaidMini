package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * A monotonic event the agent can consume through long polling.
 *
 * <p>Sequence numbers never repeat and never move backwards, so a reconnecting client
 * resumes from its own cursor instead of replaying consumed events.
 */
public record CompanionEvent(long sequence, String companionId, String kind, String data) {

    public CompanionEvent {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(data, "data");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    public String toJson() {
        return "{\"event_seq\":" + sequence
                + ",\"companion_id\":\"" + companionId + '"'
                + ",\"kind\":\"" + kind + '"'
                + ",\"data\":" + data + '}';
    }
}
