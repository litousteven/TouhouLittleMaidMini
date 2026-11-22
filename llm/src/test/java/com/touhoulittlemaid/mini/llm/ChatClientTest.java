package com.touhoulittlemaid.mini.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import com.touhoulittlemaid.mini.llm.ChatClient.Transport.Response;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The transport contract: one retry for transient failures, none for a rejected key. */
class ChatClientTest {

    private static final ModelConfig CONFIG =
            new ModelConfig("https://example.test/v1", "test-model", 0.2D, 256, 10);

    @Test
    void readsTheAssistantContentAndUsage() {
        StubTransport transport = new StubTransport(ok("{\"ok\":true}", 120, 8));
        ChatClient.Reply reply = new ChatClient(transport).complete(CONFIG, "token",
                List.of(ChatClient.Message.system("s"), ChatClient.Message.user("u")));

        assertEquals("{\"ok\":true}", reply.content());
        assertEquals("stop", reply.finishReason());
        assertEquals(128, reply.usage().total());
        assertEquals("https://example.test/v1/chat/completions", transport.urls.get(0));
        assertTrue(transport.bodies.get(0).contains("\"max_tokens\":256"));
        assertTrue(transport.bodies.get(0).contains("\"role\":\"system\""));
    }

    @Test
    void acceptsContentParts() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":[
                  {"type":"text","text":"{\\"a\\":1}"}]},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":5,"completion_tokens":2}}
                """;
        ChatClient.Reply reply = new ChatClient(new StubTransport(new Response(200, body)))
                .complete(CONFIG, "token", List.of(ChatClient.Message.user("u")));
        assertEquals("{\"a\":1}", reply.content());
    }

    @Test
    void retriesOnceOnServerErrors() {
        StubTransport transport = new StubTransport(new Response(503, ""), ok("{}", 1, 1));
        ChatClient.Reply reply = new ChatClient(transport).complete(CONFIG, "token",
                List.of(ChatClient.Message.user("u")));

        assertEquals("{}", reply.content());
        assertEquals(2, transport.urls.size());
    }

    @Test
    void doesNotRetryARejectedKey() {
        StubTransport transport = new StubTransport(new Response(401, "{}"));
        var failure = assertThrows(ChatClient.ModelException.class,
                () -> new ChatClient(transport).complete(CONFIG, "token",
                        List.of(ChatClient.Message.user("u"))));

        assertEquals("http_401", failure.code());
        assertEquals(1, transport.urls.size());
    }

    @Test
    void refusesToCallWithoutCredentialsOrConfig() {
        ChatClient client = new ChatClient(new StubTransport(ok("{}", 1, 1)));
        assertEquals("missing_token", assertThrows(ChatClient.ModelException.class,
                () -> client.complete(CONFIG, " ", List.of(ChatClient.Message.user("u")))).code());
        assertEquals("missing_config", assertThrows(ChatClient.ModelException.class,
                () -> client.complete(new ModelConfig("", "", 0.2D, 100, 10), "token",
                        List.of(ChatClient.Message.user("u")))).code());
    }

    @Test
    void reportsEmptyOrBrokenAnswers() {
        assertEquals("empty_content", assertThrows(ChatClient.ModelException.class,
                () -> clientWith("{\"choices\":[{\"message\":{\"content\":\"\"}}]}")
                        .complete(CONFIG, "t", List.of(ChatClient.Message.user("u")))).code());
        assertEquals("invalid_response", assertThrows(ChatClient.ModelException.class,
                () -> clientWith("not json")
                        .complete(CONFIG, "t", List.of(ChatClient.Message.user("u")))).code());
        assertEquals("invalid_response", assertThrows(ChatClient.ModelException.class,
                () -> clientWith("{\"choices\":[]}")
                        .complete(CONFIG, "t", List.of(ChatClient.Message.user("u")))).code());
    }

    @Test
    void baseUrlIsNormalised() {
        ModelConfig trailingSlash = new ModelConfig("https://example.test/v1/", "m", 0.2D, 100, 10);
        assertEquals("https://example.test/v1/chat/completions",
                trailingSlash.chatCompletionsUrl());
        ModelConfig full = new ModelConfig("https://example.test/v1/chat/completions", "m", 0.2D,
                100, 10);
        assertEquals("https://example.test/v1/chat/completions", full.chatCompletionsUrl());
    }

    private static ChatClient clientWith(String body) {
        return new ChatClient(new StubTransport(new Response(200, body)));
    }

    private static Response ok(String content, int prompt, int completion) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return new Response(200, """
                {"choices":[{"message":{"role":"assistant","content":"%s"},
                  "finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d}}
                """.formatted(escaped, prompt, completion));
    }

    private static final class StubTransport implements ChatClient.Transport {

        private final Deque<Response> replies = new ArrayDeque<>();
        private final List<String> urls = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();

        StubTransport(Response... replies) {
            this.replies.addAll(List.of(replies));
        }

        @Override
        public Response post(String url, String token, String body, int timeoutSeconds)
                throws IOException {
            urls.add(url);
            bodies.add(body);
            if (replies.isEmpty()) {
                throw new IOException("no scripted reply");
            }
            return replies.size() == 1 ? replies.peek() : replies.poll();
        }
    }
}
