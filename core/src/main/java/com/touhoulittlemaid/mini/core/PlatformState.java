package com.touhoulittlemaid.mini.core;

import java.util.Objects;

/**
 * Minecraft-independent state exposed by the M0 diagnostics endpoint.
 */
public record PlatformState(
        String modVersion,
        String minecraftVersion,
        boolean serverReady,
        String message) {

    public PlatformState {
        Objects.requireNonNull(modVersion, "modVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(message, "message");
    }

    public static PlatformState starting(String modVersion, String minecraftVersion) {
        return new PlatformState(modVersion, minecraftVersion, false, "starting");
    }

    public PlatformState ready() {
        return new PlatformState(modVersion, minecraftVersion, true, "ok");
    }
}
