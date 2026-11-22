package com.touhoulittlemaid.mini.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Client-side model settings and credential file.
 *
 * <p>Two files on purpose: {@code config/tlmm-client.toml} is meant to be read and edited by
 * the player, {@code config/tlmm/credentials.key} holds the single secret line and nothing
 * else, so a shared config or a screenshot cannot leak the key by accident.
 */
public final class ModelConfigStore {

    public static final String CONFIG_FILE = "tlmm-client.toml";
    public static final String KEY_FILE = "tlmm/credentials.key";

    public record Loaded(ModelConfig config, String token, List<String> problems) {

        public Loaded {
            config = config == null ? ModelConfig.defaults() : config;
            token = token == null ? "" : token;
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean hasToken() {
            return !token.isBlank();
        }

        public boolean ready() {
            return hasToken() && config.isConfigured();
        }
    }

    private ModelConfigStore() {
    }

    public static Path configFile(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve(CONFIG_FILE);
    }

    public static Path keyFile(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve(KEY_FILE).normalize();
    }

    public static Loaded load(Path gameDirectory) {
        List<String> problems = new ArrayList<>();
        ModelConfig config = readConfig(configFile(gameDirectory), problems);
        String token = readToken(keyFile(gameDirectory), problems);
        return new Loaded(config, token, problems);
    }

    public static void save(Path gameDirectory, ModelConfig config, String token)
            throws IOException {
        Path file = configFile(gameDirectory);
        Files.createDirectories(file.getParent());
        Files.writeString(file, toToml(config), StandardCharsets.UTF_8);

        if (token != null && !token.isBlank()) {
            Path key = keyFile(gameDirectory);
            Files.createDirectories(key.getParent());
            Files.writeString(key, token.strip() + "\n", StandardCharsets.UTF_8);
        }
    }

    public static void clearToken(Path gameDirectory) throws IOException {
        Files.deleteIfExists(keyFile(gameDirectory));
    }

    /** What may safely appear in a log or a chat message. */
    public static String redact(String token) {
        if (token == null || token.isBlank()) {
            return "(none)";
        }
        String trimmed = token.strip();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    static String toToml(ModelConfig config) {
        return """
                # Touhou Little Maid Mini - client model settings.
                # The API key is NOT stored here; it lives in config/tlmm/credentials.key.
                [model]
                base_url = "%s"
                model = "%s"
                temperature = %s
                max_tokens = %d
                timeout_seconds = %d
                """.formatted(config.baseUrl(), config.model(),
                String.format(Locale.ROOT, "%.2f", config.temperature()),
                config.maxTokens(), config.timeoutSeconds());
    }

    static ModelConfig readConfig(Path file, List<String> problems) {
        if (!Files.isRegularFile(file)) {
            return ModelConfig.defaults();
        }
        String baseUrl = ModelConfig.DEFAULT_BASE_URL;
        String model = ModelConfig.DEFAULT_MODEL;
        double temperature = 0.2D;
        int maxTokens = 700;
        int timeoutSeconds = 30;
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = stripComment(raw).strip();
                int separator = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("[") || separator < 0) {
                    continue;
                }
                String key = line.substring(0, separator).strip();
                String value = unquote(line.substring(separator + 1).strip());
                try {
                    switch (key) {
                        case "base_url" -> baseUrl = value;
                        case "model" -> model = value;
                        case "temperature" -> temperature = Double.parseDouble(value);
                        case "max_tokens" -> maxTokens = Integer.parseInt(value);
                        case "timeout_seconds" -> timeoutSeconds = Integer.parseInt(value);
                        default -> {
                            // Unknown keys are ignored on purpose: forward compatibility.
                        }
                    }
                } catch (NumberFormatException exception) {
                    problems.add(key + " is not a number: " + value);
                }
            }
        } catch (IOException exception) {
            problems.add("cannot read " + file.getFileName() + ": " + exception.getMessage());
            return ModelConfig.defaults();
        }
        return new ModelConfig(baseUrl, model, temperature, maxTokens, timeoutSeconds);
    }

    static String readToken(Path file, List<String> problems) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            String first = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .findFirst()
                    .orElse("");
            return first;
        } catch (IOException exception) {
            problems.add("cannot read credentials: " + exception.getClass().getSimpleName());
            return "";
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
