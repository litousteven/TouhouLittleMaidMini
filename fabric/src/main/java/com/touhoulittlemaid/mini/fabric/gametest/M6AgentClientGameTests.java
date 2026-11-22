package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.client.agent.AgentChatScreen;
import com.touhoulittlemaid.mini.client.agent.AgentLoop;
import com.touhoulittlemaid.mini.core.CompanionEvent;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.llm.ModelConfig;
import com.touhoulittlemaid.mini.llm.ModelConfigStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * M6.4 acceptance: a real client, a real model, a real plan.
 *
 * <p>Two halves. The first always runs: without a key the chat must refuse rather than pretend,
 * and nothing may reach the server. The second only runs when {@code TLMM_MODEL_KEY} is set,
 * because the whole point of the client brain is that the key is never in the repository — the
 * test writes it into the client's own config at run time and deletes it afterwards.
 */
public final class M6AgentClientGameTests implements FabricClientGameTest {

    private static final int MAX_WAIT_TICKS = 700;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.restoreDefaultGameOptions();
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(true)
                .create()) {
            Path gameDirectory = gameDirectory(context);
            removeKey(gameDirectory);

            singleplayer.getServer().runCommand("time set noon");
            singleplayer.getServer().runCommand("execute as @a at @s run tlmm summon");
            context.waitTicks(40);

            UUID companionId = companionId(singleplayer);
            if (companionId == null) {
                throw new IllegalStateException("no companion was summoned");
            }

            // 1. No key: the screen opens, the loop refuses, the server hears nothing.
            context.runOnClient(client -> client.gui.setScreen(new AgentChatScreen()));
            context.waitForScreen(AgentChatScreen.class);
            context.runOnClient(client -> AgentLoop.get().send("你好"));
            context.waitTicks(10);
            String refusal = context.computeOnClient(client ->
                    String.join(" | ", AgentLoop.get().lines().stream()
                            .map(AgentLoop.Line::text).toList()));
            if (!refusal.contains("Key")) {
                throw new IllegalStateException(
                        "an unconfigured client must say the key is missing, got: " + refusal);
            }
            if (!events(singleplayer, companionId).isEmpty()) {
                throw new IllegalStateException("a refused chat line reached the server");
            }
            Path shot = context.takeScreenshot("m64-chat-no-key");
            requireScreenshot(shot, "M6.4 chat screenshot");

            // 2. With a key: one sentence in, one executed plan out.
            String token = env("TLMM_MODEL_KEY");
            if (token.isBlank()) {
                TlmmFabric.LOGGER.info("[m6.4] live model check skipped: TLMM_MODEL_KEY is unset; "
                        + "screenshot={}", shot.getFileName());
                return;
            }
            writeConfig(gameDirectory, token);
            try {
                context.runOnClient(client -> AgentLoop.get().send(
                        "请用 say 模块对我说一句「我到了」，只输出一个计划，不要解释。"));
                boolean finished = false;
                for (int waited = 0; waited < MAX_WAIT_TICKS; waited += 10) {
                    context.waitTicks(10);
                    if (hasFinishedPlan(singleplayer, companionId)) {
                        finished = true;
                        break;
                    }
                }
                String transcript = context.computeOnClient(client ->
                        String.join(" | ", AgentLoop.get().lines().stream()
                                .map(line -> line.role() + ":" + line.text()).toList()));
                if (!finished) {
                    throw new IllegalStateException(
                            "the model never produced an executed plan; transcript=" + transcript);
                }
                TlmmFabric.LOGGER.info("[m6.4] live plan finished; transcript={}", transcript);
            } finally {
                removeKey(gameDirectory);
            }
        }
    }

    private static Path gameDirectory(ClientGameTestContext context) {
        AtomicReference<Path> path = new AtomicReference<>();
        context.runOnClient(client -> path.set(client.gameDirectory.toPath()));
        if (path.get() == null) {
            throw new IllegalStateException("client game directory was not available");
        }
        return path.get();
    }

    private static void writeConfig(Path gameDirectory, String token) {
        String base = env("TLMM_MODEL_BASE");
        String model = env("TLMM_MODEL_NAME");
        ModelConfig config = ModelConfig.defaults();
        if (!base.isBlank()) {
            config = config.withBaseUrl(base);
        }
        if (!model.isBlank()) {
            config = config.withModel(model);
        }
        try {
            ModelConfigStore.save(gameDirectory, config, token);
        } catch (IOException exception) {
            throw new IllegalStateException("could not write the client model config", exception);
        }
    }

    private static void removeKey(Path gameDirectory) {
        try {
            Files.deleteIfExists(ModelConfigStore.keyFile(gameDirectory));
        } catch (IOException exception) {
            throw new IllegalStateException("could not clear the client key", exception);
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.strip();
    }

    private static UUID companionId(TestSingleplayerContext singleplayer) {
        return singleplayer.getServer().computeOnServer(server -> server.overworld()
                .getEntitiesOfClass(CompanionEntity.class,
                        AABB.ofSize(Vec3.ZERO, 512.0D, 512.0D, 512.0D))
                .stream().findFirst().map(CompanionEntity::getUUID).orElse(null));
    }

    private static List<CompanionEvent> events(TestSingleplayerContext singleplayer,
            UUID companionId) {
        return singleplayer.getServer().computeOnServer(server ->
                CompanionRuntimeState.eventBus().after(companionId.toString(), 0, 200));
    }

    private static boolean hasFinishedPlan(TestSingleplayerContext singleplayer,
            UUID companionId) {
        return events(singleplayer, companionId).stream()
                .filter(event -> event.kind().equals("plan_finished"))
                .anyMatch(event -> event.data().contains("\"ok\":true"));
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
