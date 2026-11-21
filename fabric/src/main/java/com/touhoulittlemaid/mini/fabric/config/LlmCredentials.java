package com.touhoulittlemaid.mini.fabric.config;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

/** Stores the model API token in a separate local file that is never logged. */
public final class LlmCredentials {

    public static final String FILE_NAME = "tlmm-llm-credentials.toml";

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static String load() {
        try {
            Path file = path();
            if (Files.notExists(file)) {
                return "";
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("api_token")) {
                    int equals = trimmed.indexOf('=');
                    if (equals > 0) {
                        return unescape(trimmed.substring(equals + 1).strip());
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[llm] could not read config/{} ({})", FILE_NAME,
                    exception.getClass().getSimpleName());
        }
        return "";
    }

    public static void save(String token) {
        String safe = token == null ? "" : token.strip();
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            Files.writeString(file, "api_token = \"" + escape(safe) + "\"\n",
                    StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Windows uses ACLs; filesystem access remains the operator's responsibility.
            }
            TlmmFabric.LOGGER.info("[llm] saved credentials to config/{}", FILE_NAME);
        } catch (IOException | RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[llm] could not save config/{} ({})", FILE_NAME,
                    exception.getClass().getSimpleName());
        }
    }

    public static boolean hasToken() {
        return !load().isBlank();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private LlmCredentials() {
    }
}
