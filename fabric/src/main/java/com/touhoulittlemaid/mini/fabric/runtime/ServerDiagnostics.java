package com.touhoulittlemaid.mini.fabric.runtime;

import com.touhoulittlemaid.mini.core.ServerMetrics;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Computes a lightweight server health snapshot without touching the world on HTTP threads. */
public final class ServerDiagnostics {

    private static final AtomicInteger COMPANION_COUNT = new AtomicInteger();
    private static volatile ServerMetrics cached = ServerMetrics.empty();
    private static int tickCounter;

    private ServerDiagnostics() {
    }

    /** Refreshes world-derived values on the server thread; HTTP callers only read the cache. */
    public static void tick(MinecraftServer server) {
        if (++tickCounter % 20 != 0) {
            return;
        }
        COMPANION_COUNT.set(countCompanions(server));
        cached = build(server);
    }

    public static void reset() {
        COMPANION_COUNT.set(0);
        cached = ServerMetrics.empty();
        tickCounter = 0;
    }

    /** HTTP-thread-safe snapshot, refreshed every 20 server ticks. */
    public static ServerMetrics cachedSnapshot() {
        return cached;
    }

    /** Server-thread snapshot for commands and GameTests. */
    public static ServerMetrics snapshot(MinecraftServer server) {
        return build(server);
    }

    private static ServerMetrics build(MinecraftServer server) {
        long[] tickTimes = server.getTickTimesNanos();
        double maxMs = tickTimes.length == 0
                ? 0.0D
                : Arrays.stream(tickTimes).max().orElse(0L) / 1_000_000.0D;
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        var events = CompanionRuntimeState.eventBus();
        return new ServerMetrics(
                COMPANION_COUNT.get(),
                server.getAverageTickTimeNanos() / 1_000_000.0D,
                maxMs,
                usedMb,
                maxMb,
                threads,
                events.lastSequence(),
                events.oldestRetainedSequence(),
                CompanionRuntimeState.mcpConnected());
    }

    public static int countCompanions(MinecraftServer server) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity) {
                    count++;
                }
            }
        }
        return count;
    }
}
