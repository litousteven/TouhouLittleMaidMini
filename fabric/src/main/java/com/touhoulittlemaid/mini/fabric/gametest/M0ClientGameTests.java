package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public final class M0ClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.restoreDefaultGameOptions();
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(true)
                .create()) {
            singleplayer.getServer().runCommand("time set noon");
            singleplayer.getServer().runCommand("gamerule doDaylightCycle false");
            singleplayer.getServer().runCommand("gamemode creative");
            singleplayer.getServer().runCommand(
                    "execute at @a run summon tlmm:debug_companion ^ ^ ^4");

            context.waitTicks(40);
            Path screenshot = context.takeScreenshot("m0-fabric-render");
            try {
                if (!Files.isRegularFile(screenshot) || Files.size(screenshot) < 1_024) {
                    throw new IllegalStateException("M0 client screenshot was not produced");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not inspect M0 client screenshot", exception);
            }
            TlmmFabric.LOGGER.info("[m0] client render screenshot={}", screenshot.getFileName());
        }
    }
}
