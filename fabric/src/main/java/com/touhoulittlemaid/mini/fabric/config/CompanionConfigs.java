package com.touhoulittlemaid.mini.fabric.config;

import com.touhoulittlemaid.mini.core.CompanionServerConfig;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Loads and stores the server configuration.
 *
 * <p>The log never contains the absolute path of the local machine; only the
 * {@code config/} relative name is reported.
 */
public final class CompanionConfigs {

    static final String FILE_NAME = "touhou-little-maid-mini-server.toml";

    private static volatile CompanionServerConfig current = CompanionServerConfig.defaults();

    private CompanionConfigs() {
    }

    public static CompanionServerConfig current() {
        return current;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, CompanionServerConfig.defaults().toToml(),
                        StandardCharsets.UTF_8);
                current = CompanionServerConfig.defaults();
                TlmmFabric.LOGGER.info("[config] wrote default config/{}", FILE_NAME);
                return;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            CompanionServerConfig.ParseResult result = CompanionServerConfig.parse(text);
            for (String problem : result.problems()) {
                TlmmFabric.LOGGER.warn("[config] {}: {}", FILE_NAME, problem);
            }
            current = result.config();
            TlmmFabric.LOGGER.info("[config] loaded config/{}", FILE_NAME);
        } catch (IOException | RuntimeException exception) {
            current = CompanionServerConfig.defaults();
            TlmmFabric.LOGGER.warn("[config] could not read config/{}; using defaults ({})",
                    FILE_NAME, exception.getClass().getSimpleName());
        }
    }

    /** Test seam: replace the active configuration without touching the filesystem. */
    public static void override(CompanionServerConfig config) {
        current = config;
    }

    public static void reset() {
        current = CompanionServerConfig.defaults();
    }
}
