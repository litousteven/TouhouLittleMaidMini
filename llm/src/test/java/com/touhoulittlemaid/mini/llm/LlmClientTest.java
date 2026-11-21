package com.touhoulittlemaid.mini.llm;

import com.touhoulittlemaid.mini.core.LlmConfig;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientTest {

    private static final LlmConfig CONFIG = new LlmConfig(true,
            "https://example.invalid/v1/chat/completions", "test-model", 30, 12, "review");

    @Test
    void parsesOpenAiCompatibleResponse() throws Exception {
        String response = """
                {"choices":[{"message":{"content":
                "{\\"behavior\\":\\"farm\\",\\"message\\":\\"我去收割小麦\\"}"}}]}
                """;
        LlmClient client = new LlmClient((url, token, payload, timeout) -> {
            assertTrue(payload.contains("test-model"));
            assertFalse(payload.contains("secret-token"));
            return response;
        });
        LlmClient.Decision decision = client.decide(CONFIG, "secret-token", "system", "user");
        assertEquals(LocalBehavior.FARM, decision.localBehavior());
        assertEquals("我去收割小麦", decision.message());
        assertFalse(decision.hasSkill());
    }

    @Test
    void acceptsSkillProposalAsReviewData() throws Exception {
        String response = """
                {"behavior":"guard","message":"我守着主人","learned_skill":
                {"name":"test-crop","steps":["observe crops","interact block"]}}
                """;
        LlmClient client = new LlmClient((url, token, payload, timeout) -> response);
        LlmClient.Decision decision = client.decide(CONFIG, "token", "system", "user");
        assertTrue(decision.hasSkill());
        assertEquals(List.of("observe crops", "interact block"), decision.skill().steps());
        assertTrue(decision.skill().toMarkdown().contains("status: proposal"));
    }

    @Test
    void rejectsUnknownBehavior() {
        LlmClient client = new LlmClient((url, token, payload, timeout) ->
                "{\"behavior\":\"execute_command\",\"message\":\"no\"}");
        LlmClient.LlmException exception = assertThrows(LlmClient.LlmException.class,
                () -> client.decide(CONFIG, "token", "system", "user"));
        assertEquals("unsupported_behavior", exception.code());
    }
}
