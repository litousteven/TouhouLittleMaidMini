package com.touhoulittlemaid.mini.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Minecraft-free settings for the optional in-game model client.
 *
 * <p>The API token is deliberately absent: it lives in a separate local credential file
 * and must never appear in this configuration, logs, audits or chat output.
 */
public record LlmConfig(
        boolean enabled,
        String baseUrl,
        String model,
        int requestTimeoutSeconds,
        int maxDecisionsPerMinute,
        String learnedSkills) {

    public static final String DEFAULT_BASE_URL =
            "https://api.openai.com/v1/chat/completions";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final String REVIEW = "review";

    public LlmConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(learnedSkills, "learnedSkills");
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://localhost")
                && !baseUrl.startsWith("http://127.0.0.1")) {
            throw new IllegalArgumentException("LLM base URL must use HTTPS or loopback HTTP");
        }
        if (model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        requestTimeoutSeconds = clamp(requestTimeoutSeconds, 5, 300, 30);
        maxDecisionsPerMinute = clamp(maxDecisionsPerMinute, 1, 120, 12);
        if (!REVIEW.equals(learnedSkills)) {
            learnedSkills = REVIEW;
        }
    }

    public static LlmConfig defaults() {
        return new LlmConfig(false, DEFAULT_BASE_URL, DEFAULT_MODEL, 30, 12, REVIEW);
    }

    /**
     * Reads only the {@code [llm]} section. Unknown keys are ignored, and missing or
     * invalid values fall back to defaults with a reported problem.
     */
    public static ParseResult parse(String text) {
        Objects.requireNonNull(text, "text");
        List<String> problems = new ArrayList<>();
        String section = "";
        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (String rawLine : text.split("\\R")) {
            String line = stripComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String value = unquote(line.substring(equals + 1).strip());
            values.put(section.isEmpty() ? key : section + "." + key, value);
        }

        boolean sectionPresent = values.keySet().stream()
                .anyMatch(key -> key.startsWith("llm."));
        boolean enabled = bool(values, "llm.enabled", false, problems);
        String baseUrl = values.getOrDefault("llm.base_url", DEFAULT_BASE_URL);
        if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://localhost")
                && !baseUrl.startsWith("http://127.0.0.1")) {
            problems.add("llm.base_url must use HTTPS or loopback HTTP; using default");
            baseUrl = DEFAULT_BASE_URL;
        }
        String model = values.getOrDefault("llm.model", DEFAULT_MODEL);
        if (model.isBlank()) {
            problems.add("llm.model must not be blank; using default");
            model = DEFAULT_MODEL;
        }
        int timeout = integer(values, "llm.request_timeout_seconds", 30, 5, 300, problems);
        int maxDecisions = integer(values, "llm.max_decisions_per_minute", 12, 1, 120, problems);
        String learned = values.getOrDefault("llm.learned_skills", REVIEW);
        if (!REVIEW.equals(learned)) {
            problems.add("llm.learned_skills must be review; using review");
            learned = REVIEW;
        }
        LlmConfig config = new LlmConfig(enabled, baseUrl, model, timeout, maxDecisions, learned);
        return new ParseResult(config, sectionPresent, List.copyOf(problems));
    }

    public String toToml() {
        return """
                [llm]
                enabled = %s
                base_url = "%s"
                model = "%s"
                request_timeout_seconds = %d
                max_decisions_per_minute = %d
                learned_skills = "%s"
                """.formatted(enabled, baseUrl, model, requestTimeoutSeconds,
                maxDecisionsPerMinute, learnedSkills);
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value < minimum || value > maximum) {
            return fallback;
        }
        return value;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (character == '#' && !quoted) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean bool(java.util.Map<String, String> values, String key,
            boolean fallback, List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        problems.add(key + " must be true or false; using " + fallback);
        return fallback;
    }

    private static int integer(java.util.Map<String, String> values, String key, int fallback,
            int minimum, int maximum, List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.strip());
            if (parsed < minimum || parsed > maximum) {
                problems.add(key + " must be between " + minimum + " and " + maximum
                        + "; using " + fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            problems.add(key + " is not an integer; using " + fallback);
            return fallback;
        }
    }

    /** Parsed LLM settings plus whether the section existed in the source file. */
    public record ParseResult(LlmConfig config, boolean sectionPresent, List<String> problems) {

        public ParseResult {
            Objects.requireNonNull(config, "config");
            problems = List.copyOf(problems);
        }
    }
}
