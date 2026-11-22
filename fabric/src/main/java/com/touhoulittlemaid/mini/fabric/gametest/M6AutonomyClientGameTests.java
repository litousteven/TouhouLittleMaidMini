package com.touhoulittlemaid.mini.fabric.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.touhoulittlemaid.mini.client.agent.AgentLoop;
import com.touhoulittlemaid.mini.core.autonomy.Opportunity;
import com.touhoulittlemaid.mini.fabric.autonomy.Director;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.llm.ModelConfig;
import com.touhoulittlemaid.mini.llm.ModelConfigStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * M6.5 client acceptance: one autonomy round, end to end.
 *
 * <p>A local stub serves the model response, so this test is deterministic and needs no
 * network or API key. Everything else is real: the director scans the world and sends
 * {@code agent_opportunities}, the client builds the autonomy prompt, calls the chat
 * transport, parses the choice, locally gates the plan and submits it, and the server
 * re-validates the choice against its own round before executing the plan.
 */
public final class M6AutonomyClientGameTests implements FabricClientGameTest {

    private static final int MAX_WAIT_TICKS = 600;

    @Override
    public void runTest(ClientGameTestContext context) {
        context.restoreDefaultGameOptions();
        StubModel stub = StubModel.start();
        try (TestSingleplayerContext world = context.worldBuilder()
                .setUseConsistentSettings(true).create()) {
            Path directory = gameDirectory(context);
            removeKey(directory);
            try {
                try {
                    ModelConfigStore.save(directory, ModelConfig.defaults()
                                    .withBaseUrl("http://127.0.0.1:" + stub.port() + "/v1")
                                    .withModel("stub-model"),
                            "sk-STUB-LOCAL-ONLY");
                } catch (IOException exception) {
                    throw new IllegalStateException("could not write the stub config", exception);
                }
                world.getServer().runCommand("time set noon");
                world.getServer().runCommand("execute as @a at @s run tlmm summon");
                context.waitTicks(40);

                UUID companionId = companionId(world);
                if (companionId == null) {
                    throw new IllegalStateException("no companion was summoned");
                }
                List<String> offered = world.getServer().computeOnServer(server -> {
                    CompanionEntity companion = server.overworld()
                            .getEntitiesOfClass(CompanionEntity.class,
                                    AABB.ofSize(Vec3.ZERO, 512.0D, 512.0D, 512.0D))
                            .stream().filter(row -> row.getUUID().equals(companionId))
                            .findFirst().orElse(null);
                    if (companion == null) {
                        return List.<String>of();
                    }
                    companion.setWorkRadius(6);
                    companion.hunger().setLevel(20);
                    companion.backpack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 12));
                    ServerLevel level = server.overworld();
                    BlockPos feet = companion.blockPosition();
                    for (int index = 0; index < 8; index++) {
                        BlockPos soil = feet.offset(1 + index % 3, 0, 1 + index / 3);
                        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
                        level.setBlockAndUpdate(soil.above(), Blocks.WHEAT.defaultBlockState()
                                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
                    }
                    return Director.evaluate(companion, System.currentTimeMillis()).stream()
                            .map(Opportunity::id).toList();
                });
                if (!offered.contains("harvest_farm")) {
                    throw new IllegalStateException("the director offered no harvest: "
                            + offered + "; reason=" + world.getServer().computeOnServer(server ->
                            Director.state(companionId).lastReason()));
                }

                boolean finished = false;
                for (int waited = 0; waited < MAX_WAIT_TICKS; waited += 10) {
                    context.waitTicks(10);
                    if (hasAutonomyPlan(world, companionId)) {
                        finished = true;
                        break;
                    }
                }
                String transcript = context.computeOnClient(client ->
                        String.join(" | ", AgentLoop.get().lines().stream()
                                .map(line -> line.role() + ":" + line.text()).toList()));
                if (!finished) {
                    throw new IllegalStateException("no autonomy plan finished; stub calls="
                            + stub.calls() + "; transcript=" + transcript);
                }
                if (stub.calls() == 0) {
                    throw new IllegalStateException("the client never called the model");
                }
                long tokens = world.getServer().computeOnServer(server ->
                        Director.state(companionId).budget()
                                .tokensToday(System.currentTimeMillis()));
                if (tokens < 160) {
                    throw new IllegalStateException("the token budget did not record the round: "
                            + tokens);
                }

                Path shot = context.takeScreenshot("m65-autonomy-round");
                try {
                    if (!Files.isRegularFile(shot) || Files.size(shot) < 1_024) {
                        throw new IllegalStateException("autonomy screenshot was not produced");
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("could not inspect the screenshot", exception);
                }
            } finally {
                removeKey(directory);
            }
        } finally {
            stub.close();
        }
    }

    private static boolean hasAutonomyPlan(TestSingleplayerContext world, UUID companionId) {
        return world.getServer().computeOnServer(server ->
                CompanionRuntimeState.eventBus()
                        .after(companionId.toString(), 0, 400).stream()
                        .filter(event -> event.kind().equals("plan_finished"))
                        .anyMatch(event -> event.data().contains("\"source\":\"autonomy\"")));
    }

    private static Path gameDirectory(ClientGameTestContext context) {
        AtomicReference<Path> path = new AtomicReference<>();
        context.runOnClient(client -> path.set(client.gameDirectory.toPath()));
        if (path.get() == null) {
            throw new IllegalStateException("client game directory was not available");
        }
        return path.get();
    }

    private static void removeKey(Path directory) {
        try {
            Files.deleteIfExists(ModelConfigStore.keyFile(directory));
        } catch (IOException exception) {
            throw new IllegalStateException("could not clear the stub key", exception);
        }
    }

    private static UUID companionId(TestSingleplayerContext world) {
        return world.getServer().computeOnServer(server -> server.overworld()
                .getEntitiesOfClass(CompanionEntity.class,
                        AABB.ofSize(Vec3.ZERO, 512.0D, 512.0D, 512.0D))
                .stream().findFirst().map(CompanionEntity::getUUID).orElse(null));
    }

    /** A one-line HTTP server on loopback: enough of OpenAI's shape, nothing else. */
    private static final class StubModel implements AutoCloseable {

        private final ServerSocket socket;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean running = true;

        private StubModel(ServerSocket socket) {
            this.socket = socket;
        }

        static StubModel start() {
            try {
                ServerSocket socket = new ServerSocket(0, 8,
                        java.net.InetAddress.getLoopbackAddress());
                StubModel stub = new StubModel(socket);
                Thread.ofVirtual().start(stub::serve);
                return stub;
            } catch (IOException exception) {
                throw new IllegalStateException("could not start the stub model", exception);
            }
        }

        int port() {
            return socket.getLocalPort();
        }

        int calls() {
            return calls.get();
        }

        private void serve() {
            while (running) {
                try (Socket connection = socket.accept()) {
                    connection.setSoTimeout(5_000);
                    readHeaders(connection.getInputStream());
                    byte[] body = response().getBytes(StandardCharsets.UTF_8);
                    OutputStream out = connection.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(body);
                    out.flush();
                    calls.incrementAndGet();
                } catch (IOException exception) {
                    if (running) {
                        // A client that gave up is not a test failure; the next round retries.
                    }
                }
            }
        }

        private static void readHeaders(InputStream in) throws IOException {
            int matched = 0;
            while (matched < 4) {
                int value = in.read();
                if (value < 0) {
                    return;
                }
                matched = switch (matched) {
                    case 0 -> value == '\r' ? 1 : 0;
                    case 1 -> value == '\n' ? 2 : (value == '\r' ? 1 : 0);
                    case 2 -> value == '\r' ? 3 : 0;
                    default -> value == '\n' ? 4 : (value == '\r' ? 1 : 0);
                };
            }
        }

        private static String response() {
            String answer = """
                    {"choice":"harvest_farm","say":"我去收菜","plan":{"version":1,
                    "goal":"harvest_farm","steps":[{"module":"harvest_area",
                    "params":{"radius":4,"replant":false,"max":1}}]}}
                    """.strip().replace("\n", "");
            JsonObject message = new JsonObject();
            message.addProperty("content", answer);
            JsonObject choice = new JsonObject();
            choice.add("message", message);
            choice.addProperty("finish_reason", "stop");
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject usage = new JsonObject();
            usage.addProperty("prompt_tokens", 120);
            usage.addProperty("completion_tokens", 40);
            JsonObject root = new JsonObject();
            root.add("choices", choices);
            root.add("usage", usage);
            return root.toString();
        }

        @Override
        public void close() {
            running = false;
            try {
                socket.close();
            } catch (IOException exception) {
                // Nothing else to do; the JVM is about to exit anyway.
            }
        }
    }
}
