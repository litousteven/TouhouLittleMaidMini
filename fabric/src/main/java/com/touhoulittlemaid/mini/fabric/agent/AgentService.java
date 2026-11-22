package com.touhoulittlemaid.mini.fabric.agent;

import com.touhoulittlemaid.mini.core.agent.AgentRateLimiter;
import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.core.plan.PlanLimits;
import com.touhoulittlemaid.mini.core.plan.PlanRunner;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatch;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeBatchJson;
import com.touhoulittlemaid.mini.core.semantic.KnowledgeIngest;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.network.AgentClientPayloads;
import com.touhoulittlemaid.mini.fabric.network.AgentServerPayloads;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.semantic.KnowledgeService;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Server side of the client brain: the boundary that trusts nothing.
 *
 * <p>The client may have called any model with any prompt; by the time a plan arrives here it
 * is just text. This class re-parses it, re-gates it with the authoritative module registry and
 * only then starts it. Rate limits are per player and separate per action, so a chatty client
 * cannot use plan submission as a cheap world scan.
 */
public final class AgentService {

    private static final int STATE_INTERVAL_TICKS = 40;

    private static final Map<UUID, AgentRateLimiter> LIMITERS = new ConcurrentHashMap<>();
    private static volatile MinecraftServer server;

    private AgentService() {
    }

    /**
     * Wires payload types, receivers and the task progress tick.
     *
     * <p>Called from the common initializer, so both sides register the same codecs before any
     * connection exists. The size cap is explicit: a batch request carries up to 64 items, and
     * the default custom-payload limit is too small for that on a large mod pack.
     */
    public static void register() {
        var clientbound = net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
                .clientboundPlay();
        var serverbound = net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
                .serverboundPlay();
        clientbound.register(AgentClientPayloads.Observation.TYPE,
                AgentClientPayloads.Observation.STREAM_CODEC);
        clientbound.register(AgentClientPayloads.TaskState.TYPE,
                AgentClientPayloads.TaskState.STREAM_CODEC);
        clientbound.register(AgentClientPayloads.TaskResult.TYPE,
                AgentClientPayloads.TaskResult.STREAM_CODEC);
        clientbound.register(AgentClientPayloads.Notice.TYPE,
                AgentClientPayloads.Notice.STREAM_CODEC);
        clientbound.registerLarge(AgentClientPayloads.KnowledgeRequest.TYPE,
                AgentClientPayloads.KnowledgeRequest.STREAM_CODEC, 64 * 1024);
        serverbound.register(AgentServerPayloads.SubmitPlan.TYPE,
                AgentServerPayloads.SubmitPlan.STREAM_CODEC);
        serverbound.registerLarge(AgentServerPayloads.KnowledgeProposal.TYPE,
                AgentServerPayloads.KnowledgeProposal.STREAM_CODEC, 64 * 1024);
        serverbound.register(AgentServerPayloads.Cancel.TYPE,
                AgentServerPayloads.Cancel.STREAM_CODEC);
        serverbound.register(AgentServerPayloads.ObservationRequest.TYPE,
                AgentServerPayloads.ObservationRequest.STREAM_CODEC);

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                AgentServerPayloads.SubmitPlan.TYPE,
                (payload, context) -> onSubmitPlan(context.player(), payload));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                AgentServerPayloads.Cancel.TYPE,
                (payload, context) -> onCancel(context.player(), payload));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                AgentServerPayloads.ObservationRequest.TYPE,
                (payload, context) -> onObservation(context.player(), payload));
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(
                AgentServerPayloads.KnowledgeProposal.TYPE,
                (payload, context) -> onKnowledgeProposal(context.player(), payload));

        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                AgentService::onTick);
        PlanRuntime.addCompletionListener(AgentService::onPlanFinished);
        // The brain is the player's; when the player leaves, the plan has nobody to report to.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, currentServer) -> onPlayerLeft(handler.player));
    }

    public static void install(MinecraftServer value) {
        server = value;
    }

    public static void reset() {
        server = null;
        LIMITERS.clear();
    }

    // ---- plan submission -------------------------------------------------------------------

    private static void onSubmitPlan(ServerPlayer player,
            AgentServerPayloads.SubmitPlan payload) {
        CompanionEntity companion = companionOf(player);
        if (companion == null) {
            send(player, AgentClientPayloads.Notice.error("no_companion",
                    "You have no companion nearby to give this to."));
            return;
        }
        if (!allow(player, AgentRateLimiter.Bucket.SUBMIT)) {
            send(player, AgentClientPayloads.Notice.error("rate_limited",
                    "Too many plans in one minute; wait a little."));
            return;
        }
        String json = payload.planJson() == null ? "" : payload.planJson();
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > PlanLimits.MAX_PLAN_BYTES) {
            result(player, payload.requestId(), false, FailureCodes.PLAN_TOO_LARGE,
                    "plan is " + bytes + " bytes, limit is " + PlanLimits.MAX_PLAN_BYTES);
            return;
        }
        ParsedPlan parsed;
        try {
            parsed = PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            result(player, payload.requestId(), false, exception.code(), exception.getMessage());
            return;
        }
        // The policy is chosen by the confirmation flag, never by the client's claim about it.
        PlanGate.Policy policy = payload.confirmed() ? PlanGate.Policy.commandConfirmed()
                : PlanGate.Policy.command();
        PlanRuntime.SubmitResult submitted;
        try {
            submitted = PlanRuntime.submit(companion, parsed, policy);
        } catch (RuntimeException exception) {
            result(player, payload.requestId(), false, FailureCodes.INTERRUPTED,
                    "plan could not start: " + exception.getClass().getSimpleName());
            return;
        }
        if (!submitted.ok()) {
            result(player, payload.requestId(), false, submitted.code(), submitted.message());
            return;
        }
        PlanRuntime.activePlanId(companion.getUUID()).ifPresent(id ->
                send(player, new AgentClientPayloads.TaskState(id, 0, parsed.plan().stepCount(),
                        "", 0, 0)));
    }

    private static void onCancel(ServerPlayer player, AgentServerPayloads.Cancel payload) {
        CompanionEntity companion = companionOf(player);
        if (companion == null) {
            return;
        }
        String requested = payload.planId() == null ? "" : payload.planId();
        Optional<String> active = PlanRuntime.activePlanId(companion.getUUID());
        if (active.isEmpty()) {
            send(player, AgentClientPayloads.Notice.info("Nothing is running."));
            return;
        }
        if (!requested.isBlank() && !requested.equals(active.get())) {
            send(player, AgentClientPayloads.Notice.info(
                    "That plan already finished; the companion is doing something else."));
            return;
        }
        PlanRuntime.cancel(companion.getUUID(), "cancelled by owner");
    }

    // ---- observation -----------------------------------------------------------------------

    private static void onObservation(ServerPlayer player,
            AgentServerPayloads.ObservationRequest payload) {
        CompanionEntity companion = companionOf(player);
        if (companion == null) {
            send(player, new AgentClientPayloads.Observation(payload.requestId(), "{}"));
            return;
        }
        if (!allow(player, AgentRateLimiter.Bucket.OBSERVE)) {
            send(player, new AgentClientPayloads.Observation(payload.requestId(),
                    "{\"error\":\"rate_limited\"}"));
            return;
        }
        send(player, new AgentClientPayloads.Observation(payload.requestId(),
                AgentObserver.observe(companion, payload.category())));
    }

    // ---- knowledge proposals ---------------------------------------------------------------

    private static void onKnowledgeProposal(ServerPlayer player,
            AgentServerPayloads.KnowledgeProposal payload) {
        if (!allow(player, AgentRateLimiter.Bucket.KNOWLEDGE)) {
            return;
        }
        KnowledgeBatch batch = KnowledgeService.lastBatch();
        if (batch == null || !batch.id().equals(payload.batchId())) {
            send(player, AgentClientPayloads.Notice.error("stale_batch",
                    "That annotation batch is no longer open; run a scan again."));
            return;
        }
        KnowledgeIngest.IngestReport report = KnowledgeService.ingest(batch, payload.json());
        send(player, AgentClientPayloads.Notice.info("batch " + batch.id() + ": "
                + report.accepted() + " applied, " + report.pending() + " pending, "
                + report.rejected() + " rejected"));
        com.touhoulittlemaid.mini.fabric.network.KnowledgeNetworking.sendView(player);
    }

    /**
     * Stops the plan of a companion whose owner just left.
     *
     * <p>Kept separate from the packet path so it can be tested with a UUID alone. The
     * companion itself stays where it is and falls back to its local behaviour, which is what
     * "no client brain" is supposed to look like.
     */
    public static boolean cancelOwnedPlan(UUID ownerId, String reason) {
        MinecraftServer currentServer = server;
        if (currentServer == null || ownerId == null) {
            return false;
        }
        for (ServerLevel level : currentServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion
                        && companion.getOwner() != null
                        && ownerId.equals(companion.getOwner().getUUID())
                        && PlanRuntime.isRunning(companion.getUUID())) {
                    PlanRuntime.cancel(companion.getUUID(), reason);
                    return true;
                }
            }
        }
        return false;
    }

    private static void onPlayerLeft(ServerPlayer player) {
        if (player == null) {
            return;
        }
        if (cancelOwnedPlan(player.getUUID(), "owner left the game")) {
            TlmmFabric.LOGGER.info("[agent] stopped the plan of {} after disconnect",
                    player.getName().getString());
        }
        LIMITERS.remove(player.getUUID());
    }

    // ---- progress --------------------------------------------------------------------------

    private static void onTick(MinecraftServer currentServer) {
        if (currentServer.getTickCount() % STATE_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                    AgentClientPayloads.TaskState.TYPE)) {
                continue;
            }
            CompanionEntity companion = companionOf(player);
            if (companion == null) {
                continue;
            }
            PlanRuntime.snapshot(companion.getUUID()).ifPresent(snapshot ->
                    send(player, new AgentClientPayloads.TaskState(snapshot.planId(),
                            snapshot.step(), snapshot.steps(), snapshot.module(),
                            snapshot.actions(), snapshot.ticks())));
        }
    }

    private static void onPlanFinished(UUID companionId, String planId,
            PlanRunner.Summary summary) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        CompanionEntity companion = findCompanion(companionId);
        if (companion == null) {
            return;
        }
        for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
            if (player == companion.getOwner()) {
                send(player, new AgentClientPayloads.TaskResult(planId, summary.ok(),
                        summary.code(), summary.message(), summary.stepsCompleted(),
                        summary.stepsFailed(), summary.stepsSkipped()));
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * The companion this player controls.
     *
     * <p>Ownership is the permission model: no packet names an entity id, so a modified client
     * cannot aim a plan at somebody else's companion.
     */
    public static CompanionEntity companionOf(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel origin)
                || origin.getServer() == null) {
            return null;
        }
        for (ServerLevel level : origin.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion && companion.isAlive()
                        && !companion.isRemoved() && companion.isOwner(player)) {
                    return companion;
                }
            }
        }
        return null;
    }

    private static CompanionEntity findCompanion(UUID companionId) {
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            return null;
        }
        for (ServerLevel level : currentServer.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CompanionEntity companion
                        && companion.getUUID().equals(companionId)) {
                    return companion;
                }
            }
        }
        return null;
    }

    private static boolean allow(ServerPlayer player, AgentRateLimiter.Bucket bucket) {
        AgentRateLimiter limiter = LIMITERS.computeIfAbsent(player.getUUID(),
                key -> new AgentRateLimiter());
        return limiter.allow(bucket, System.currentTimeMillis());
    }

    private static void result(ServerPlayer player, String requestId, boolean ok, String code,
            String message) {
        send(player, new AgentClientPayloads.TaskResult(requestId == null ? "" : requestId, ok,
                code, message, 0, 0, 0));
        if (!ok) {
            TlmmFabric.LOGGER.info("[agent] refused plan for {}: {} ({})",
                    player.getName().getString(), code, message);
        }
    }

    private static void send(ServerPlayer player, AgentClientPayloads.Observation payload) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                AgentClientPayloads.Observation.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, AgentClientPayloads.TaskState payload) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                AgentClientPayloads.TaskState.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, AgentClientPayloads.TaskResult payload) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                AgentClientPayloads.TaskResult.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, AgentClientPayloads.KnowledgeRequest payload) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                AgentClientPayloads.KnowledgeRequest.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }

    private static void send(ServerPlayer player, AgentClientPayloads.Notice payload) {
        if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                AgentClientPayloads.Notice.TYPE)) {
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
        }
    }

    /** Sends an annotation batch to a client that can call a model. */
    public static void sendKnowledgeRequest(ServerPlayer player, KnowledgeBatch batch) {
        if (batch == null || player == null) {
            return;
        }
        send(player, new AgentClientPayloads.KnowledgeRequest(batch.id(),
                KnowledgeBatchJson.toRequestJson(batch)));
    }
}
