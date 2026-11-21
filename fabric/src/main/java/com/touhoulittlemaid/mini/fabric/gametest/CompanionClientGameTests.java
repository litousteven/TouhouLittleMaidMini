package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.client.menu.CompanionScreen;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the real companion entity and opens the management screen in a real client.
 */
public final class CompanionClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        context.restoreDefaultGameOptions();
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(true)
                .create()) {
            singleplayer.getServer().runCommand("time set noon");
            singleplayer.getServer().runCommand("execute as @a at @s run tlmm summon");

            context.waitTicks(40);
            singleplayer.getServer().runCommand("execute as @a at @s run tp @s ^ ^ ^-2");
            context.waitTicks(10);

            int companionCount = companionCount(singleplayer);
            if (companionCount < 1) {
                throw new IllegalStateException("M4 companion was not present on the server");
            }

            Path screenshot = context.takeScreenshot("m4-companion-render");
            requireScreenshot(screenshot, "M4 client screenshot");

            singleplayer.getServer().runCommand("execute as @a at @s run tp @s ^ ^ ^2");
            context.waitTicks(5);

            context.runOnClient(client -> {
                var player = client.player;
                var companion = client.level.getEntitiesOfClass(CompanionEntity.class,
                                player.getBoundingBox().inflate(16.0D))
                        .stream().findFirst().orElse(null);
                if (companion == null) {
                    throw new IllegalStateException("client could not see the summoned companion");
                }
                client.gameMode.interact(player, companion, new EntityHitResult(companion),
                        InteractionHand.MAIN_HAND);
            });

            context.waitForScreen(CompanionScreen.class);
            context.waitTicks(5);
            Path menuScreenshot = context.takeScreenshot("m4-companion-screen");
            requireScreenshot(menuScreenshot, "M4 management screen");

            boolean clicked = false;
            for (int attempt = 0; attempt < 20 && !clicked; attempt++) {
                clicked = context.tryClickScreenButton("Follow");
                if (!clicked) {
                    context.waitTick();
                }
            }
            if (!clicked) {
                throw new IllegalStateException("management screen did not expose the behaviour button");
            }
            context.waitTicks(5);

            LocalBehavior behavior = singleplayer.getServer().computeOnServer(server ->
                    server.overworld().getEntitiesOfClass(CompanionEntity.class,
                                    AABB.ofSize(Vec3.ZERO, 512.0D, 512.0D, 512.0D))
                            .stream().findFirst()
                            .map(CompanionEntity::behavior)
                            .orElseThrow(() -> new IllegalStateException(
                                    "companion disappeared after the menu click")));
            if (behavior != LocalBehavior.GUARD) {
                throw new IllegalStateException(
                        "behaviour button did not reach the server: " + behavior);
            }

            TlmmFabric.LOGGER.info("[m4] client render screenshot={} menu={} companions={}",
                    screenshot.getFileName(), menuScreenshot.getFileName(), companionCount);
        }
    }

    private static int companionCount(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server ->
                server.overworld().getEntitiesOfClass(CompanionEntity.class,
                        AABB.ofSize(Vec3.ZERO, 512.0D, 512.0D, 512.0D)).size());
    }

    private static void requireScreenshot(Path screenshot, String description) {
        try {
            if (!Files.isRegularFile(screenshot) || Files.size(screenshot) < 1_024) {
                throw new IllegalStateException(description + " was not produced");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect " + description, exception);
        }
    }
}
