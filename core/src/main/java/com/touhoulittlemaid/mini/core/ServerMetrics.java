package com.touhoulittlemaid.mini.core;

/**
 * Minecraft-free server health snapshot.
 *
 * <p>The MCP transport exposes this from {@code ping}; the soak harness records it once
 * per interval without adding another tool to the public surface.
 */
public record ServerMetrics(
        int companionCount,
        double msptAverage,
        double msptMax,
        long heapUsedMb,
        long heapMaxMb,
        int threadCount,
        long lastEventSequence,
        long oldestEventSequence,
        boolean mcpConnected) {

    public static ServerMetrics empty() {
        return new ServerMetrics(0, 0.0D, 0.0D, 0, 0, 0, 0, 0, false);
    }

    public long retainedEventCount() {
        if (lastEventSequence <= 0L) {
            return 0L;
        }
        long oldest = oldestEventSequence <= 0L ? lastEventSequence : oldestEventSequence;
        return Math.max(0L, lastEventSequence - oldest + 1L);
    }
}
