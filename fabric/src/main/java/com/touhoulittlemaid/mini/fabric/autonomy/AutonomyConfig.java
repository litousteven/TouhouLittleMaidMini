package com.touhoulittlemaid.mini.fabric.autonomy;

import com.touhoulittlemaid.mini.core.autonomy.AutonomyTier;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The autonomy tier, server-side and authoritative.
 *
 * <p>Kept in its own small file ({@code config/tlmm-autonomy.toml}) instead of growing the M1
 * server config parser: the tier is read on every director tick, and a parse failure must not
 * be able to take the MCP endpoint down with it. Missing or unreadable means the default,
 * which is {@code chores}.
 */
public final class AutonomyConfig {

    public static final String FILE_NAME = "tlmm-autonomy.toml";
    private static final AutonomyTier DEFAULT = AutonomyTier.CHORES;

    private static volatile AutonomyTier tier = DEFAULT;

    private AutonomyConfig() {
    }

    public static AutonomyTier tier() {
        return tier;
    }

    public static void override(AutonomyTier value) {
        tier = value == null ? DEFAULT : value;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        try {
            if (Files.notExists(path)) {
                tier = DEFAULT;
                write(path);
                return;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Optional<AutonomyTier> parsed = Optional.empty();
            for (String line : text.split("\\R")) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                String key = trimmed.substring(0, trimmed.indexOf('=')).strip();
                String value = unquote(trimmed.substring(trimmed.indexOf('=') + 1).strip());
                if ("tier".equals(key)) {
                    parsed = AutonomyTier.parse(value);
                    if (parsed.isEmpty()) {
                        TlmmFabric.LOGGER.warn("[autonomy] unknown tier {} in {}; using {}",
                                value, FILE_NAME, DEFAULT.id());
                    }
                }
            }
            tier = parsed.orElse(DEFAULT);
            TlmmFabric.LOGGER.info("[autonomy] tier={}", tier.id());
        } catch (IOException | RuntimeException exception) {
            tier = DEFAULT;
            TlmmFabric.LOGGER.warn("[autonomy] could not read {}; using {}",
                    FILE_NAME, DEFAULT.id());
        }
    }

    public static void set(AutonomyTier value) throws IOException {
        tier = value == null ? DEFAULT : value;
        write(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
    }

    public static void reset() {
        tier = DEFAULT;
    }

    private static void write(Path path) {
        String text = """
                # How much this companion may do without being asked.
                # off | suggest | chores | free
                tier = "%s"
                """.formatted(tier.id());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            TlmmFabric.LOGGER.warn("[autonomy] could not write {}: {}", FILE_NAME,
                    exception.getClass().getSimpleName());
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
