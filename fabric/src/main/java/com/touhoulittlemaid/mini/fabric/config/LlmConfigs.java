package com.touhoulittlemaid.mini.fabric.config;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The old server-side {@code [llm]} section, kept only as a notice.
 *
 * <p>From 0.2 the model settings and the API key live on the client
 * ({@code config/tlmm-client.toml} and {@code config/tlmm/credentials.key}). The server must
 * not read a key, must not start a request and must not print a value — so this class does not
 * parse the section any more. It only says that the section is ignored, once, per start.
 */
public final class LlmConfigs {

    private LlmConfigs() {
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve(CompanionConfigs.FILE_NAME);
        try {
            if (Files.notExists(path)) {
                return;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.contains("[llm]")) {
                TlmmFabric.LOGGER.info("[llm] the server [llm] section is ignored from 0.2; "
                        + "model settings and the key now live on the client "
                        + "(config/tlmm-client.toml). Nothing was deleted.");
            }
        } catch (IOException | RuntimeException exception) {
            // A config that cannot be read is already reported by CompanionConfigs.
        }
    }
}
