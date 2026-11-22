package com.touhoulittlemaid.mini.core.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Set;

/**
 * What the client brain is allowed to take from a model answer.
 *
 * <p>The model is asked for one JSON object either way:
 * {@code {"say":"...","plan":{...}}} or {@code {"say":"...","plan":null}}. Anything that is
 * not that shape becomes an error with a code, never a guessed interpretation — a chat reply
 * that silently becomes a plan is exactly the failure mode this class exists to prevent.
 *
 * <p>Parsing is deliberately tolerant about three things models actually do: a fenced code
 * block around the object, prose before or after it, and a bare plan object without the
 * wrapper. Everything else is strict.
 */
public record AgentReply(boolean ok, String say, String planJson, String choice, String code,
        String message) {

    public static final String EMPTY_REPLY = "empty_reply";
    public static final String INVALID_REPLY = "invalid_reply";
    public static final String INVALID_PLAN = "invalid_plan";
    public static final String DESCRIBE = "describe";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int MAX_SAY_LENGTH = 400;
    private static final Set<String> WRAPPER_FIELDS = Set.of("say", "plan", "reason", "describe");

    /** The model asked for a full module schema instead of answering. */
    public record Describe(String module) {
    }

    public static AgentReply parse(String content) {
        if (content == null || content.isBlank()) {
            return failed(EMPTY_REPLY, "the model returned an empty answer");
        }
        String json = extractObject(content);
        if (json == null) {
            return failed(INVALID_REPLY, "the answer contains no JSON object");
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return failed(INVALID_REPLY, "the answer is not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException exception) {
            return failed(INVALID_REPLY, "the answer is not valid JSON");
        }

        String say = text(root.get("say"));
        if (say.length() > MAX_SAY_LENGTH) {
            say = say.substring(0, MAX_SAY_LENGTH);
        }

        // A bare plan object is accepted: some models drop the wrapper when the schema is
        // long. Treating it as a plan is safer than treating it as chat text.
        JsonElement planElement = root.get("plan");
        if (planElement == null && root.has("steps")) {
            planElement = root;
        }
        String planJson = normalizePlan(planElement);
        if (planJson == null && planElement != null && !planElement.isJsonNull()) {
            return failed(INVALID_PLAN, "the plan must be a JSON object or null");
        }
        if (planJson != null && !hasSteps(planJson)) {
            planJson = null;
        }
        if (planJson == null && say.isBlank()) {
            return failed(INVALID_REPLY, "the answer has neither text nor a plan");
        }
        return new AgentReply(true, say, planJson, text(root.get("choice")), "ok",
                "reply accepted");
    }

    /**
     * Returns the module id when the answer is a schema request, otherwise null.
     *
     * <p>Kept separate from {@link #parse} so callers cannot mistake a request for a plan.
     */
    public static String describeRequest(String content) {
        String json = extractObject(content);
        if (json == null) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonElement describe = parsed.getAsJsonObject().get("describe");
            String module = text(describe).trim();
            return module.isEmpty() ? null : module;
        } catch (JsonParseException exception) {
            return null;
        }
    }

    public boolean hasPlan() {
        return planJson != null && !planJson.isBlank();
    }

    /** A human-readable one-liner for the chat list. */
    public String display() {
        if (!ok) {
            return message;
        }
        return say.isBlank() ? "" : say;
    }

    private static String normalizePlan(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return GSON.toJson(element);
        }
        if (element.isJsonPrimitive() && ((JsonPrimitive) element).isString()) {
            String inner = element.getAsString().trim();
            String extracted = extractObject(inner);
            return extracted == null ? null : extracted;
        }
        return null;
    }

    private static boolean hasSteps(String planJson) {
        try {
            JsonElement parsed = JsonParser.parseString(planJson);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonElement steps = parsed.getAsJsonObject().get("steps");
            return steps != null && steps.isJsonArray() && !steps.getAsJsonArray().isEmpty();
        } catch (JsonParseException exception) {
            return false;
        }
    }

    private static String text(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() || primitive.isNumber() ? primitive.getAsString().trim() : "";
    }

    /**
     * Finds the first balanced JSON object, ignoring braces inside strings.
     *
     * <p>Not a full parser: it only has to survive fenced blocks and prose. The real syntax
     * check happens in {@link JsonParser} right after.
     */
    static String extractObject(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < content.length(); index++) {
            char character = content.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            switch (character) {
                case '"' -> inString = true;
                case '{' -> depth++;
                case '}' -> {
                    depth--;
                    if (depth == 0) {
                        return content.substring(start, index + 1);
                    }
                }
                default -> {
                    // keep scanning
                }
            }
        }
        return null;
    }

    private static AgentReply failed(String code, String message) {
        return new AgentReply(false, "", null, "", code, message);
    }
}
