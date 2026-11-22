package com.touhoulittlemaid.mini.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Optional real-endpoint check. Skipped unless the three {@code TLMM_MODEL_*} variables are
 * set, so a normal build never spends tokens or needs network access.
 */
class LiveModelProbe {

    @Test
    void realEndpointAnswersWithJson() {
        String key = System.getenv("TLMM_MODEL_KEY");
        String base = System.getenv("TLMM_MODEL_BASE");
        String model = System.getenv("TLMM_MODEL_NAME");
        assumeTrue(key != null && !key.isBlank(), "TLMM_MODEL_KEY is not set");
        assumeTrue(base != null && !base.isBlank(), "TLMM_MODEL_BASE is not set");
        assumeTrue(model != null && !model.isBlank(), "TLMM_MODEL_NAME is not set");

        ModelConfig config = new ModelConfig(base, model, 0.2D, 120, 45);
        ChatClient.Reply reply = new ChatClient().complete(config, key, List.of(
                ChatClient.Message.system("Reply with JSON only, no prose."),
                ChatClient.Message.user("Return {\"ok\":true}")));

        assertNotNull(reply.content());
        assertFalse(reply.content().isBlank());
        assertFalse(reply.usage().total() <= 0, "usage should be reported");
    }
}
