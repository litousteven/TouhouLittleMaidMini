package com.touhoulittlemaid.mini.fabric.config;

import com.touhoulittlemaid.mini.core.LlmConfig;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/** Loads and updates the {@code [llm]} section without touching other config sections. */
public final class LlmConfigs {

    private static volatile LlmConfig current = LlmConfig.defaults();

    public static LlmConfig current() {
        return current;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve(CompanionConfigs.FILE_NAME);
        try {
            if (Files.notExists(path)) {
                return;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            LlmConfig.ParseResult result = LlmConfig.parse(text);
            for (String problem : result.problems()) {
                TlmmFabric.LOGGER.warn("[llm] {}: {}", CompanionConfigs.FILE_NAME, problem);
            }
            current = result.config();
            if (!result.sectionPresent()) {
                Files.writeString(path, text.stripTrailing() + "\n\n" + current.toToml(),
                        StandardCharsets.UTF_8);
                TlmmFabric.LOGGER.info("[llm] added default [llm] section to config/{}",
                        CompanionConfigs.FILE_NAME);
            }
            TlmmFabric.LOGGER.info("[llm] configured enabled={} model={}",
                    current.enabled(), current.model());
        } catch (IOException | RuntimeException exception) {
            current = LlmConfig.defaults();
            TlmmFabric.LOGGER.warn("[llm] could not read config/{}; using defaults ({})",
                    CompanionConfigs.FILE_NAME, exception.getClass().getSimpleName());
        }
    }

    public static void override(LlmConfig config) {
        current = config;
    }

    public static void reset() {
        current = LlmConfig.defaults();
    }

    public static void setEnabled(boolean enabled) {
        update(new LlmConfig(enabled, current.baseUrl(), current.model(),
                current.requestTimeoutSeconds(), current.maxDecisionsPerMinute(),
                current.learnedSkills()));
    }

    public static void setBaseUrl(String baseUrl) {
        update(new LlmConfig(current.enabled(), baseUrl.strip(), current.model(),
                current.requestTimeoutSeconds(), current.maxDecisionsPerMinute(),
                current.learnedSkills()));
    }

    public static void setModel(String model) {
        update(new LlmConfig(current.enabled(), current.baseUrl(), model.strip(),
                current.requestTimeoutSeconds(), current.maxDecisionsPerMinute(),
                current.learnedSkills()));
    }

    private static void update(LlmConfig config) {
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve(CompanionConfigs.FILE_NAME);
        try {
            String existing = Files.exists(path)
                    ? Files.readString(path, StandardCharsets.UTF_8) : "";
            Files.writeString(path, replaceSection(existing, config.toToml()) + "\n",
                    StandardCharsets.UTF_8);
            current = config;
            TlmmFabric.LOGGER.info("[llm] updated config/{}", CompanionConfigs.FILE_NAME);
        } catch (IOException | RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[llm] could not update config/{} ({})",
                    CompanionConfigs.FILE_NAME, exception.getClass().getSimpleName());
            throw new IllegalStateException("Could not save LLM settings", exception);
        }
    }

    static String replaceSection(String text, String section) {
        StringBuilder result = new StringBuilder();
        boolean skipping = false;
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (skipping) {
                    skipping = false;
                }
                if ("[llm]".equals(trimmed)) {
                    skipping = true;
                    continue;
                }
            }
            if (!skipping) {
                result.append(line).append('\n');
            }
        }
        while (result.length() > 0 && result.charAt(result.length() - 1) == '\n') {
            result.setLength(result.length() - 1);
        }
        return result.append("\n\n").append(section.strip()).toString();
    }

    private LlmConfigs() {
    }
}
