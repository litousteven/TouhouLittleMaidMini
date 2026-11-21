package com.touhoulittlemaid.mini.fabric.runtime;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.config.LlmConfigs;
import com.touhoulittlemaid.mini.fabric.config.LlmCredentials;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.llm.LlmClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.CropBlock;

/**
 * Proactive model requests for the optional in-game LLM brain.
 *
 * <p>Model calls never run on the server thread. Every accepted decision is validated,
 * mapped onto a known local behaviour and published through the same event bus as MCP
 * actions. Network failures degrade to the deterministic baseline brain.
 */
public final class LlmRuntime {

    private static final LlmClient CLIENT = new LlmClient();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicLong LAST_DECISION_AT = new AtomicLong(0L);
    private static volatile MinecraftServer server;

    public static void install(MinecraftServer value) {
        server = value;
    }

    public static void reset() {
        server = null;
        IN_FLIGHT.set(false);
        LAST_DECISION_AT.set(0L);
    }

    public static boolean available() {
        return LlmConfigs.current().enabled() && !LlmCredentials.load().isBlank();
    }

    /** Called once per server tick; requests are rate limited and never block the tick. */
    public static void tick(net.minecraft.server.MinecraftServer ignored) {
        MinecraftServer currentServer = server;
        if (currentServer == null || !available() || IN_FLIGHT.get()) {
            return;
        }
        if (currentServer.getTickCount() % 200 != 0) {
            return;
        }
        int perMinute = LlmConfigs.current().maxDecisionsPerMinute();
        long interval = Math.max(1_000L, 60_000L / perMinute);
        long now = System.currentTimeMillis();
        long previous = LAST_DECISION_AT.get();
        if (now - previous < interval
                || !LAST_DECISION_AT.compareAndSet(previous, now)) {
            return;
        }
        findFirstCompanion().ifPresent(companion ->
                requestDecision(companion.getUUID(), "periodic_check"));
    }

    public static void requestDecision(UUID companionId, String reason) {
        MinecraftServer currentServer = server;
        if (currentServer == null || !available() || !IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        String token = LlmCredentials.load();
        if (token.isBlank()) {
            IN_FLIGHT.set(false);
            return;
        }
        Optional<CompanionEntity> found = findCompanion(companionId);
        if (found.isEmpty()) {
            IN_FLIGHT.set(false);
            return;
        }
        CompanionEntity companion = found.get();
        String system = """
                You control one Minecraft companion. Reply with JSON only.
                Keys: behavior (stay|follow|guard|farm), message (<=280 chars),
                optional learned_skill with name and steps.
                Never output commands, URLs, scripts, Java code or tool names.
                Prefer the current behavior unless the context clearly requires a change.
                If you are unsure, choose stay and explain briefly.
                """;
        String user = contextJson(companion, reason);
        Thread.ofVirtual().start(() -> {
            try {
                LlmClient.Decision decision = CLIENT.decide(
                        LlmConfigs.current(), token, system, user);
                currentServer.execute(() -> apply(companionId, reason, decision));
            } catch (LlmClient.LlmException exception) {
                IN_FLIGHT.set(false);
                TlmmFabric.LOGGER.warn("[llm] request failed: {}", exception.code());
            } catch (RuntimeException exception) {
                IN_FLIGHT.set(false);
                TlmmFabric.LOGGER.warn("[llm] request failed: internal_error");
            }
        });
    }

    private static void apply(UUID companionId, String reason, LlmClient.Decision decision) {
        try {
            IN_FLIGHT.set(false);
            Optional<CompanionEntity> found = findCompanion(companionId);
            if (found.isEmpty()) {
                return;
            }
            CompanionEntity companion = found.get();
            LocalBehavior behavior = decision.localBehavior();
            LocalBehavior previous = companion.behavior();
            companion.setBehavior(behavior);
            CompanionRuntimeState.publish(companionId, "llm_decision_applied",
                    "{\"reason\":\"" + escape(reason) + "\",\"previous\":\""
                            + previous.id() + "\",\"behavior\":\"" + behavior.id() + "\"}");
            if (!decision.message().isBlank() && companion.getOwner() instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.literal(decision.message()));
            }
            if (decision.hasSkill() && "review".equals(LlmConfigs.current().learnedSkills())) {
                saveProposal(decision.skill());
            }
        } catch (RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[llm] could not apply decision: invalid_response");
        }
    }

    private static void saveProposal(LlmClient.SkillProposal proposal) {
        try {
            Path directory = FabricLoader.getInstance().getConfigDir()
                    .resolve("tlmm").resolve("learned-skills");
            Files.createDirectories(directory);
            String name = proposal.name().toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9_-]", "-");
            Path file = directory.resolve(name + ".md");
            Files.writeString(file, proposal.toMarkdown(), StandardCharsets.UTF_8);
            TlmmFabric.LOGGER.info("[llm] saved Skill proposal as config/tlmm/learned-skills/{}",
                    file.getFileName());
        } catch (IOException | RuntimeException exception) {
            TlmmFabric.LOGGER.warn("[llm] could not save Skill proposal");
        }
    }

    private static Optional<CompanionEntity> findFirstCompanion() {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return Optional.empty();
        }
        for (var level : currentServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion) {
                    return Optional.of(companion);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<CompanionEntity> findCompanion(UUID companionId) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return Optional.empty();
        }
        for (var level : currentServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion
                        && companion.getUUID().equals(companionId)) {
                    return Optional.of(companion);
                }
            }
        }
        return Optional.empty();
    }

    private static String contextJson(CompanionEntity companion, String reason) {
        var owner = companion.getOwner();
        int hostiles = companion.level().getEntitiesOfClass(Monster.class,
                companion.getBoundingBox().inflate(companion.workRadius())).size();
        int crops = matureCrops(companion);
        return "{\"reason\":\"" + escape(reason) + "\",\"behavior\":\""
                + companion.behavior().id() + "\",\"health\":" + companion.getHealth()
                + ",\"max_health\":" + companion.getMaxHealth()
                + ",\"owner_health\":" + (owner == null ? 0 : owner.getHealth())
                + ",\"hostiles\":" + hostiles + ",\"mature_crops\":" + crops
                + ",\"work_radius\":" + companion.workRadius() + "}";
    }

    private static int matureCrops(CompanionEntity companion) {
        BlockPos origin = companion.blockPosition();
        int radius = Math.min(companion.workRadius(), 12);
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!companion.level().isLoaded(pos)) {
                        continue;
                    }
                    var state = companion.level().getBlockState(pos);
                    if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private LlmRuntime() {
    }
}
