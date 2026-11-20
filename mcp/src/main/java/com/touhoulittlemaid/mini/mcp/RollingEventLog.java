package com.touhoulittlemaid.mini.mcp;

import com.touhoulittlemaid.mini.core.CompanionEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Bounded, rotating JSON-lines event log.
 *
 * <p>Delivery goes through a bounded queue so a slow disk can never stall the server
 * thread. Events contain only request-independent companion data; tokens, full
 * inventories and chat text never reach this log.
 */
public final class RollingEventLog implements AutoCloseable {

    private static final int QUEUE_CAPACITY = 2_048;

    private final Path directory;
    private final String prefix;
    private final long maxBytes;
    private final long retentionMillis;
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;

    private volatile boolean running = true;
    private BufferedWriter writer;
    private long currentSize;
    private int index;

    public RollingEventLog(Path directory, String prefix, long maxBytes, int retentionDays) {
        this.directory = directory;
        this.prefix = prefix;
        this.maxBytes = Math.max(1_024L, maxBytes);
        this.retentionMillis = Duration.ofDays(Math.max(0, retentionDays)).toMillis();
        this.worker = Thread.ofPlatform()
                .daemon(true)
                .name("tlmm-event-log")
                .start(this::run);
    }

    /** Queues one event; drops it when the writer is behind rather than blocking. */
    public void append(CompanionEvent event) {
        if (running) {
            queue.offer(event.toJson());
        }
    }

    public void append(String line) {
        if (running) {
            queue.offer(line);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            // Let the worker drain the queue; it polls with a bounded timeout.
            worker.join(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            worker.interrupt();
        }
    }

    private void run() {
        try {
            Files.createDirectories(directory);
            prune();
            openNextFile();
            while (running || !queue.isEmpty()) {
                String line = queue.poll(250L, TimeUnit.MILLISECONDS);
                if (line != null) {
                    write(line);
                }
            }
        } catch (IOException exception) {
            // The log is diagnostic; never fail the server because of it.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            closeWriter();
        }
    }

    private void write(String line) throws IOException {
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (writer == null || currentSize + bytes.length > maxBytes) {
            rotate();
        }
        writer.write(line);
        writer.newLine();
        writer.flush();
        currentSize += bytes.length;
    }

    private void rotate() throws IOException {
        closeWriter();
        openNextFile();
    }

    private void openNextFile() throws IOException {
        Path path = directory.resolve(prefix + "-" + index++ + ".log");
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        currentSize = Files.size(path);
    }

    private void prune() {
        if (retentionMillis <= 0L) {
            return;
        }
        Instant cutoff = Instant.now().minusMillis(retentionMillis);
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().startsWith(prefix + "-"))
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // Keep the log usable even if an old file cannot be removed.
                        }
                    });
        } catch (IOException ignored) {
            // A missing or unreadable directory is handled by openNextFile.
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Closing is best effort.
            }
            writer = null;
        }
    }
}
