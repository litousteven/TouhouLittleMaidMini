package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollingEventLogTest {

    @TempDir
    Path directory;

    @Test
    void rotatesAndKeepsEveryQueuedLine() throws Exception {
        EventBus bus = new EventBus();
        RollingEventLog log = new RollingEventLog(directory, "events", 256L, 7);
        bus.setListener(log::append);
        for (int index = 0; index < 20; index++) {
            bus.publish("alice", "operation_completed",
                    "{\"payload\":\"0123456789012345678901234567890123456789\"}");
        }
        log.close();

        List<Path> files;
        try (Stream<Path> stream = Files.list(directory)) {
            files = stream.filter(path -> path.getFileName().toString().startsWith("events-"))
                    .toList();
        }
        assertTrue(files.size() > 1, "small maxBytes must rotate the log");
        long lines = 0;
        for (Path file : files) {
            lines += Files.readAllLines(file, StandardCharsets.UTF_8).size();
        }
        assertEquals(20, lines);
    }

    @Test
    void appendWithoutListenerStillWorks() throws Exception {
        RollingEventLog log = new RollingEventLog(directory, "events", 4_096L, 7);
        log.append("{\"event_seq\":1}");
        log.close();

        try (Stream<Path> stream = Files.list(directory)) {
            Path file = stream.filter(path -> path.getFileName().toString().startsWith("events-"))
                    .findFirst().orElseThrow();
            assertEquals("{\"event_seq\":1}", Files.readAllLines(file).get(0));
        }
    }
}
