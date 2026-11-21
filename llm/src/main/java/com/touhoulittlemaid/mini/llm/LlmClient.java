package com.touhoulittlemaid.mini.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.touhoulittlemaid.mini.core.LlmConfig;
import com.touhoulittlemaid.mini.core.LocalBehavior;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Minimal OpenAI-compatible chat client.
 *
 * <p>No SDK is bundled. The model may only return one of the known local behaviours, an
 * optional short line to the owner and an optional skill proposal. It cannot name tools,
 * commands, scripts or URLs for execution.
 */
public final class LlmClient {

    private static final Set<String> ALLOWED_BEHAVIORS = Set.of("stay", "follow", "guard", "farm");

    private final Transport transport;

    public LlmClient() {
        this(new HttpTransport());
    }

    public LlmClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Decision decide(LlmConfig config, String token, String systemPrompt, String userPrompt)
            throws LlmException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(systemPrompt, "systemPrompt");
        Objects.requireNonNull(userPrompt, "userPrompt");
        if (token == null || token.isBlank()) {
            throw new LlmException("missing_token");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", config.model());
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemPrompt));
        messages.add(message("user", userPrompt));
        payload.add("messages", messages);
        payload.addProperty("temperature", 0.2D);
        payload.addProperty("max_tokens", 300);

        String body;
        try {
            body = transport.send(config.baseUrl(), token, payload.toString(),
                    config.requestTimeoutSeconds());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted");
        } catch (IOException exception) {
            throw new LlmException("transport_error");
        }
        return Decision.parse(body);
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    /** Injectable transport so tests never touch the network. */
    @FunctionalInterface
    public interface Transport {

        String send(String url, String token, String payload, int timeoutSeconds)
                throws IOException, InterruptedException;
    }

    private static final class HttpTransport implements Transport {

        @Override
        public String send(String url, String token, String payload, int timeoutSeconds)
                throws IOException, InterruptedException {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new LlmException("http_" + status);
            }
            return response.body();
        }
    }

    /** A validated model decision. Unknown behavior ids are rejected, not guessed. */
    public record Decision(String behavior, String message, SkillProposal skill) {

        public Decision {
            Objects.requireNonNull(behavior, "behavior");
            Objects.requireNonNull(message, "message");
        }

        public LocalBehavior localBehavior() {
            return LocalBehavior.fromId(behavior);
        }

        public boolean hasSkill() {
            return skill != null;
        }

        static Decision parse(String response) throws LlmException {
            try {
                JsonElement root = JsonParser.parseString(response);
                if (!root.isJsonObject()) {
                    throw new LlmException("invalid_response");
                }
                JsonObject object = root.getAsJsonObject();
                JsonElement choices = object.get("choices");
                if (choices != null && choices.isJsonArray() && !choices.getAsJsonArray().isEmpty()) {
                    JsonElement first = choices.getAsJsonArray().get(0);
                    JsonElement content = first.getAsJsonObject()
                            .getAsJsonObject("message").get("content");
                    object = JsonParser.parseString(content.getAsString()).getAsJsonObject();
                }
                String behavior = text(object, "behavior");
                if (!ALLOWED_BEHAVIORS.contains(behavior.toLowerCase(Locale.ROOT))) {
                    throw new LlmException("unsupported_behavior");
                }
                String message = text(object, "message");
                if (message.length() > 280) {
                    message = message.substring(0, 280);
                }
                SkillProposal skill = null;
                JsonElement learned = object.get("learned_skill");
                if (learned != null && learned.isJsonObject()) {
                    skill = SkillProposal.parse(learned.getAsJsonObject());
                }
                return new Decision(behavior.toLowerCase(Locale.ROOT), message, skill);
            } catch (LlmException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new LlmException("invalid_response");
            }
        }

        private static String text(JsonObject object, String key) throws LlmException {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new LlmException("missing_" + key);
            }
            return value.getAsString();
        }
    }

    /** A markdown Skill proposal. It is saved for review, never executed immediately. */
    public record SkillProposal(String name, List<String> steps) {

        public SkillProposal {
            Objects.requireNonNull(name, "name");
            steps = List.copyOf(steps);
            if (name.isBlank() || name.length() > 64 || steps.isEmpty() || steps.size() > 32) {
                throw new IllegalArgumentException("invalid skill proposal");
            }
        }

        static SkillProposal parse(JsonObject object) throws LlmException {
            String name = Decision.text(object, "name");
            JsonElement steps = object.get("steps");
            if (steps == null || !steps.isJsonArray()) {
                throw new LlmException("invalid_learned_skill");
            }
            List<String> values = new ArrayList<>();
            for (JsonElement step : steps.getAsJsonArray()) {
                if (!step.isJsonPrimitive() || !step.getAsJsonPrimitive().isString()) {
                    throw new LlmException("invalid_learned_skill");
                }
                String value = step.getAsString();
                if (value.length() > 280) {
                    throw new LlmException("invalid_learned_skill");
                }
                values.add(value);
            }
            try {
                return new SkillProposal(name, values);
            } catch (IllegalArgumentException exception) {
                throw new LlmException("invalid_learned_skill");
            }
        }

        public String toMarkdown() {
            StringBuilder markdown = new StringBuilder("# ").append(name).append("\n\n");
            for (String step : steps) {
                markdown.append("- ").append(step).append('\n');
            }
            return markdown.append("\n> status: proposal - not executed until reviewed\n")
                    .toString();
        }
    }

    public static final class LlmException extends RuntimeException {

        private final String code;

        public LlmException(String code) {
            super(code, null, false, false);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }
}
