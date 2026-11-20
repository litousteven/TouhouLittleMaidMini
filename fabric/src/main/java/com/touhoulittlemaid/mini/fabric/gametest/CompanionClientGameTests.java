package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Renders the real companion entity in a real client and captures a screenshot.
 *
 * <p>M0 rendered the debug probe. M1 renders {@code tlmm:companion} so the shipped
 * entity, its synced name and its renderer registration are covered.
 */
public final class CompanionClientGameTests implements FabricClientGameTest {

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
                    "execute at @a run summon tlmm:companion ^ ^ ^4");

            context.waitTicks(40);

            int companionCount = singleplayer.getServer().computeOnServer(server ->
                    server.overworld().getEntitiesOfClass(CompanionEntity.class,
                            net.minecraft.world.phys.AABB.ofSize(
                                    net.minecraft.world.phys.Vec3.ZERO, 512.0D, 512.0D, 512.0D)).size());
            if (companionCount < 1) {
                throw new IllegalStateException("M1 companion was not present on the server");
            }

            Path screenshot = context.takeScreenshot("m1-companion-render");
            try {
                if (!Files.isRegularFile(screenshot) || Files.size(screenshot) < 1_024) {
                    throw new IllegalStateException("M1 client screenshot was not produced");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not inspect M1 client screenshot", exception);
            }
            TlmmFabric.LOGGER.info("[m1] client render screenshot={} companions={}",
                    screenshot.getFileName(), companionCount);
        }
    }
}
