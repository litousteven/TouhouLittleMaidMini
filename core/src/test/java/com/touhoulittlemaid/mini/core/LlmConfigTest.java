package com.touhoulittlemaid.mini.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmConfigTest {

    @Test
    void parsesLlmSectionAndKeepsTokenAbsent() {
        String toml = """
                config_version = 1
                enabled = true

                [llm]
                enabled = true
                base_url = "https://example.invalid/v1/chat/completions"
                model = "demo-model"
                request_timeout_seconds = 45
                max_decisions_per_minute = 6
                learned_skills = "review"
                """;
        LlmConfig.ParseResult result = LlmConfig.parse(toml);
        assertTrue(result.sectionPresent());
        assertTrue(result.config().enabled());
        assertEquals("demo-model", result.config().model());
        assertEquals(45, result.config().requestTimeoutSeconds());
        assertFalse(result.config().toToml().contains("token"));
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void rejectsPublicHttpBaseUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> new LlmConfig(true, "http://example.invalid/v1", "model", 30, 12, "review"));
    }

    @Test
    void defaultsAreDisabledAndReviewOnly() {
        LlmConfig config = LlmConfig.defaults();
        assertFalse(config.enabled());
        assertEquals("review", config.learnedSkills());
        assertEquals(30, config.requestTimeoutSeconds());
    }
}
