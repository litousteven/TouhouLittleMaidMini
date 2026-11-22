package com.touhoulittlemaid.mini.llm;

/**
 * Where the model lives and how much it may spend on one call.
 *
 * <p>Kept as a value object with no I/O so the client brain can store it, show it and pass it
 * around without dragging a file system or a UI into the transport.
 */
public record ModelConfig(String baseUrl, String model, double temperature, int maxTokens,
        int timeoutSeconds) {

    public static final String DEFAULT_BASE_URL = "https://token.sensenova.cn/v1";
    public static final String DEFAULT_MODEL = "sensenova-6.8-flash";

    public ModelConfig {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        model = model == null ? "" : model.strip();
        temperature = Math.max(0.0D, Math.min(2.0D, temperature));
        maxTokens = maxTokens <= 0 ? 700 : Math.min(maxTokens, 4096);
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : Math.min(timeoutSeconds, 300);
    }

    public static ModelConfig defaults() {
        return new ModelConfig(DEFAULT_BASE_URL, DEFAULT_MODEL, 0.2D, 700, 30);
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !model.isBlank();
    }

    /** Full chat-completions endpoint, tolerating a base that already includes the path. */
    public String chatCompletionsUrl() {
        String trimmed = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/chat/completions";
    }

    public ModelConfig withBaseUrl(String value) {
        return new ModelConfig(value, model, temperature, maxTokens, timeoutSeconds);
    }

    public ModelConfig withModel(String value) {
        return new ModelConfig(baseUrl, value, temperature, maxTokens, timeoutSeconds);
    }

    public ModelConfig withMaxTokens(int value) {
        return new ModelConfig(baseUrl, model, temperature, value, timeoutSeconds);
    }
}
