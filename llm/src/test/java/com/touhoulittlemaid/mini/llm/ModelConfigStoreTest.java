package com.touhoulittlemaid.mini.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Client settings and the separate credential file. */
class ModelConfigStoreTest {

    @Test
    void roundTripsConfigAndKeyAsSeparateFiles(@TempDir Path directory) throws Exception {
        ModelConfig config = new ModelConfig("https://example.test/v1", "m1", 0.3D, 512, 20);
        ModelConfigStore.save(directory, config, "sk-test-1234567890");

        assertTrue(Files.isRegularFile(directory.resolve("config/tlmm-client.toml")));
        assertTrue(Files.isRegularFile(directory.resolve("config/tlmm/credentials.key")));

        var loaded = ModelConfigStore.load(directory);
        assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
        assertEquals("https://example.test/v1", loaded.config().baseUrl());
        assertEquals("m1", loaded.config().model());
        assertEquals(0.3D, loaded.config().temperature(), 0.001D);
        assertEquals(512, loaded.config().maxTokens());
        assertEquals(20, loaded.config().timeoutSeconds());
        assertEquals("sk-test-1234567890", loaded.token());
        assertTrue(loaded.ready());
    }

    @Test
    void keyIsNeverParkedInTheToml(@TempDir Path directory) throws Exception {
        ModelConfigStore.save(directory, ModelConfig.defaults(), "sk-secret-value");
        String toml = Files.readString(directory.resolve("config/tlmm-client.toml"));
        assertFalse(toml.contains("sk-secret-value"));
        assertTrue(toml.contains("[model]"));
    }

    @Test
    void missingFilesFallBackToDefaultsWithoutFailing(@TempDir Path directory) {
        var loaded = ModelConfigStore.load(directory);
        assertEquals(ModelConfig.DEFAULT_BASE_URL, loaded.config().baseUrl());
        assertFalse(loaded.hasToken());
        assertFalse(loaded.ready());
        assertTrue(loaded.problems().isEmpty());
    }

    @Test
    void commentsUnknownKeysAndBrokenNumbersAreTolerated(@TempDir Path directory) throws Exception {
        Path config = directory.resolve("config/tlmm-client.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
                # comment
                [model]
                base_url = "https://x.test/v1"   # trailing comment
                model = "m2"
                temperature = hot
                future_key = "ignored"
                """);
        var loaded = ModelConfigStore.load(directory);

        assertEquals("https://x.test/v1", loaded.config().baseUrl());
        assertEquals("m2", loaded.config().model());
        assertEquals(0.2D, loaded.config().temperature(), 0.001D);
        assertEquals(1, loaded.problems().size());
    }

    @Test
    void keyFileIgnoresBlankLinesAndComments(@TempDir Path directory) throws Exception {
        Path key = directory.resolve("config/tlmm/credentials.key");
        Files.createDirectories(key.getParent());
        Files.writeString(key, "\n# rotated 2026-11-22\nsk-live-abcdef\n");

        assertEquals("sk-live-abcdef", ModelConfigStore.load(directory).token());
    }

    @Test
    void clearingTheKeyLeavesTheConfig(@TempDir Path directory) throws Exception {
        ModelConfigStore.save(directory, ModelConfig.defaults(), "sk-x");
        ModelConfigStore.clearToken(directory);

        assertFalse(Files.exists(directory.resolve("config/tlmm/credentials.key")));
        assertTrue(Files.exists(directory.resolve("config/tlmm-client.toml")));
        assertFalse(ModelConfigStore.load(directory).hasToken());
    }

    @Test
    void redactionKeepsOnlyTheEdges() {
        assertEquals("(none)", ModelConfigStore.redact(""));
        assertEquals("****", ModelConfigStore.redact("short"));
        assertEquals("sk-E...abcd", ModelConfigStore.redact("sk-EXAMPLE0000000000000000000000abcd"));
        assertNotEquals("sk-EXAMPLE0000000000000000000000abcd",
                ModelConfigStore.redact("sk-EXAMPLE0000000000000000000000abcd"));
    }
}
