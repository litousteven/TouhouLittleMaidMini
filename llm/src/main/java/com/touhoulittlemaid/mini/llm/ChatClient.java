package com.touhoulittlemaid.mini.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * OpenAI-compatible chat completions, without an SDK.
 *
 * <p>One retry only, and only for failures that can plausibly succeed again (connection
 * errors, 429 and 5xx). A 401 is returned immediately: retrying a rejected key costs the
 * player time and teaches nothing.
 */
public final class ChatClient {

    public record Message(String role, String content) {

        public Message {
            role = role == null || role.isBlank() ? "user" : role;
            content = content == null ? "" : content;
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    public record Usage(int promptTokens, int completionTokens) {

        public int total() {
            return promptTokens + completionTokens;
        }
    }

    public record Reply(String content, String finishReason, Usage usage) {

        public Reply {
            content = content == null ? "" : content;
            finishReason = finishReason == null ? "" : finishReason;
            usage = usage == null ? new Usage(0, 0) : usage;
        }
    }

    /** A status and a body, so the caller can decide whether a retry makes sense. */
    public interface Transport {

        Response post(String url, String token, String body, int timeoutSeconds)
                throws IOException, InterruptedException;

        record Response(int status, String body) {
        }
    }

    private final Transport transport;

    public ChatClient() {
        this(new HttpTransport());
    }

    public ChatClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Reply complete(ModelConfig config, String token, List<Message> messages)
            throws ModelException {
        Objects.requireNonNull(config, "config");
        if (token == null || token.isBlank()) {
            throw new ModelException("missing_token");
        }
        if (!config.isConfigured()) {
            throw new ModelException("missing_config");
        }
        if (messages == null || messages.isEmpty()) {
            throw new ModelException("missing_messages");
        }

        String body = requestBody(config, messages);
        Transport.Response response;
        try {
            response = transport.post(config.chatCompletionsUrl(), token, body,
                    config.timeoutSeconds());
            if (shouldRetry(response.status())) {
                response = transport.post(config.chatCompletionsUrl(), token, body,
                        config.timeoutSeconds());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModelException("interrupted");
        } catch (IOException exception) {
            throw new ModelException("transport_error");
        }
        if (response == null) {
            throw new ModelException("transport_error");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new ModelException("http_" + response.status());
        }
        return parse(response.body());
    }

    private static boolean shouldRetry(int status) {
        return status == 429 || status >= 500;
    }

    private static String requestBody(ModelConfig config, List<Message> messages) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        payload.add("messages", array);
        payload.addProperty("temperature", config.temperature());
        payload.addProperty("max_tokens", config.maxTokens());
        return payload.toString();
    }

    /** Accepts both a plain string and a content-part array, as vendors differ. */
    static Reply parse(String body) throws ModelException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(body == null ? "" : body);
            if (!parsed.isJsonObject()) {
                throw new ModelException("invalid_response");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            throw new ModelException("invalid_response");
        }

        JsonElement choices = root.get("choices");
        if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty()) {
            throw new ModelException("invalid_response");
        }
        JsonObject first = choices.getAsJsonArray().get(0).getAsJsonObject();
        JsonElement message = first.get("message");
        if (message == null || !message.isJsonObject()) {
            throw new ModelException("invalid_response");
        }
        String content = contentOf(message.getAsJsonObject());
        if (content.isBlank()) {
            throw new ModelException("empty_content");
        }
        String finish = first.has("finish_reason") && first.get("finish_reason").isJsonPrimitive()
                ? first.get("finish_reason").getAsString() : "";
        return new Reply(content, finish, usageOf(root.get("usage")));
    }

    private static String contentOf(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (content.isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement part : content.getAsJsonArray()) {
                if (part.isJsonObject() && part.getAsJsonObject().has("text")) {
                    text.append(part.getAsJsonObject().get("text").getAsString());
                }
            }
            return text.toString();
        }
        return "";
    }

    private static Usage usageOf(JsonElement usage) {
        if (usage == null || !usage.isJsonObject()) {
            return new Usage(0, 0);
        }
        JsonObject object = usage.getAsJsonObject();
        return new Usage(intOf(object, "prompt_tokens"), intOf(object, "completion_tokens"));
    }

    private static int intOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsInt() : 0;
    }

    private static final class HttpTransport implements Transport {

        @Override
        public Response post(String url, String token, String body, int timeoutSeconds)
                throws IOException, InterruptedException {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        }
    }

    /** Failures are codes, not sentences: the UI translates them. */
    public static final class ModelException extends RuntimeException {

        private final String code;

        public ModelException(String code) {
            super(code, null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }
}
