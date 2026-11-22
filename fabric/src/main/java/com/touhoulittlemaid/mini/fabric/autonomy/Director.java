package com.touhoulittlemaid.mini.fabric.autonomy;

import com.touhoulittlemaid.mini.core.autonomy.AutonomyBudget;
import com.touhoulittlemaid.mini.core.autonomy.AutonomyTier;
import com.touhoulittlemaid.mini.core.autonomy.Opportunity;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.network.AgentClientPayloads;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * The director loop: gates, candidates, arbitration, budget.
 *
 * <p>It never decides <em>what</em> to do in the sense of choosing a task; that is the model's
 * one job, limited to the candidates this class found. What it does decide — and refuses to let
 * anyone else override — is when autonomy may run at all, and when it must stop. A threat,
 * a hurt owner and a critically hurt companion all preempt autonomy. A plan the owner asked
 * for in chat is <em>not</em> preempted: the owner's command outranks the companion's chores.
 */
public final class Director {

    private static final int EVALUATE_INTERVAL_TICKS = 100;
    private static final int ARBITRATION_INTERVAL_TICKS = 20;
    /** How long an unanswered candidate list stays valid; a new round replaces it anyway. */
    private static final long ROUND_TTL_MILLIS = 10 * 60 * 1_000L;
    /** docs/10 §11.4: after a threat clears, wait 90 seconds before offering chores again. */
    private static final long THREAT_COOLDOWN_MILLIS = 90_000L;
    /** docs/10 §11.4: owner hit / companion below 30% health stops autonomy for 15 minutes. */
    private static final long HURT_PAUSE_MILLIS = 15 * 60 * 1_000L;
    private static final float MIN_HEALTH_FRACTION = 0.6F;
    private static final float CRITICAL_HEALTH_FRACTION = 0.3F;
    private static final double THREAT_RADIUS = 16.0D;

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /** Owners and companions that were actually hit, for the 15-minute hurt pause. */
    private static final Map<UUID, Long> HURT_AT = new ConcurrentHashMap<>();
    private static volatile long globalPausedUntil;
    private static volatile String globalPauseReason = "";

    private Director() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(Director::tick);
        PlanRuntime.addCompletionListener(Director::onPlanFinished);
        ServerLivingEntityEvents.AFTER_DAMAGE.register(Director::onDamage);
    }

    public static void reset() {
        STATES.clear();
        HURT_AT.clear();
        globalPausedUntil = 0;
        globalPauseReason = "";
    }

    /** {@code /tlmm ai pause}: stops every companion's autonomy for a while. */
    public static long pauseGlobally(long now, long millis, String reason) {
        globalPausedUntil = Math.max(globalPausedUntil, now + Math.max(0L, millis));
        globalPauseReason = reason == null || reason.isBlank() ? "paused by the owner" : reason;
        return globalPausedUntil;
    }

    public static void resumeGlobally() {
        globalPausedUntil = 0;
        globalPauseReason = "";
    }

    public static long globalPausedUntil() {
        return globalPausedUntil;
    }

    public static String globalPauseReason() {
        return globalPauseReason;
    }

    public static boolean globallyPaused(long now) {
        return now < globalPausedUntil;
    }

    /** Test hook for the damage path without having to actually hurt an entity. */
    public static void noteHurt(UUID entityId, long now) {
        HURT_AT.put(entityId, now);
        pruneHurt(now);
    }

    private static void onDamage(LivingEntity entity, DamageSource source, float baseDamage,
            float dealt, boolean blocked) {
        if (blocked || dealt <= 0 || entity == null) {
            return;
        }
        if (entity instanceof ServerPlayer || entity instanceof CompanionEntity) {
            long now = System.currentTimeMillis();
            HURT_AT.put(entity.getUUID(), now);
            pruneHurt(now);
        }
    }

    private static void pruneHurt(long now) {
        if (HURT_AT.size() <= 256) {
            return;
        }
        HURT_AT.entrySet().removeIf(row -> now - row.getValue() >= HURT_PAUSE_MILLIS);
    }

    private static boolean ownerHurt(CompanionEntity companion, long now) {
        if (!(companion.getOwner() instanceof ServerPlayer owner)) {
            return false;
        }
        Long at = HURT_AT.get(owner.getUUID());
        return at != null && now - at < HURT_PAUSE_MILLIS;
    }

    /** Per-companion memory: budget, the current round and the plan it produced. */
    public static final class State {

        private final AutonomyBudget budget = new AutonomyBudget();
        private List<Opportunity> round = List.of();
        private long roundAt;
        private long lastThreatAt;
        private String lastReason = "not evaluated yet";
        private String autoPlanId = "";
        private String autoGoal = "";

        public AutonomyBudget budget() {
            return budget;
        }

        public List<Opportunity> round() {
            return round;
        }

        public String lastReason() {
            return lastReason;
        }
    }

    public static State state(UUID companionId) {
        return STATES.computeIfAbsent(companionId, key -> new State());
    }

    public static AutonomyTier tier() {
        return AutonomyConfig.tier();
    }

    // ---- the loop ---------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        AutonomyTier tier = AutonomyConfig.tier();
        if (!tier.scans()) {
            return;
        }
        boolean arbitrate = server.getTickCount() % ARBITRATION_INTERVAL_TICKS == 0;
        boolean evaluate = server.getTickCount() % EVALUATE_INTERVAL_TICKS == 0;
        if (!arbitrate && !evaluate) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof CompanionEntity companion) || !companion.isAlive()) {
                    continue;
                }
                if (!(companion.getOwner() instanceof ServerPlayer owner) || owner.isRemoved()) {
                    continue;
                }
                try {
                    if (arbitrate) {
                        arbitrate(companion, now);
                    }
                    if (evaluate) {
                        evaluate(companion, now);
                    }
                } catch (RuntimeException exception) {
                    TlmmFabric.LOGGER.warn("[autonomy] evaluation failed for {}: {}",
                            companion.getUUID(), exception.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Stops an autonomous plan when something more important is happening.
     *
     * <p>Only autonomy is preempted here: a plan the player asked for keeps running, because
     * the owner's command outranks the companion's own chores.
     */
    public static void arbitrate(CompanionEntity companion, long now) {
        State state = state(companion.getUUID());
        boolean threat = hasThreat(companion);
        if (threat) {
            state.lastThreatAt = now;
        }
        String reason = null;
        if (globallyPaused(now)) {
            reason = globalPauseReason;
        } else if (companion.getHealth()
                < companion.getMaxHealth() * CRITICAL_HEALTH_FRACTION) {
            state.budget.pause(now, HURT_PAUSE_MILLIS);
            reason = "critically hurt";
        } else if (ownerHurt(companion, now)) {
            state.budget.pause(now, HURT_PAUSE_MILLIS);
            reason = "owner was hurt";
        } else if (threat) {
            reason = "threat nearby";
        }
        if (reason == null) {
            return;
        }
        state.lastReason = reason;
        final String cause = reason;
        PlanRuntime.snapshot(companion.getUUID())
                .filter(snapshot -> PlanRuntime.SOURCE_AUTONOMY.equals(snapshot.source()))
                .ifPresent(snapshot -> {
                    PlanRuntime.cancel(companion.getUUID(), cause);
                    CompanionRuntimeState.publish(companion.getUUID(), "autonomy_interrupted",
                            "{\"reason\":" + quote(cause) + ",\"plan_id\":"
                                    + quote(snapshot.planId()) + "}");
                });
    }

    /**
     * Runs the gates and produces the candidate list.
     *
     * <p>Returns what it found even when it will not send it, so tests and {@code /tlmm
     * autonomy status} can explain why she is idle instead of guessing.
     */
    public static List<Opportunity> evaluate(CompanionEntity companion, long now) {
        State state = state(companion.getUUID());
        AutonomyTier tier = AutonomyConfig.tier();
        if (!tier.scans()) {
            state.lastReason = "tier is off";
            return List.of();
        }
        if (PlanRuntime.isRunning(companion.getUUID())) {
            state.lastReason = "already running a plan";
            return List.of();
        }
        if (globallyPaused(now)) {
            state.lastReason = globalPauseReason;
            return List.of();
        }
        if (companion.getHealth() < companion.getMaxHealth() * CRITICAL_HEALTH_FRACTION
                || ownerHurt(companion, now)) {
            state.budget.pause(now, HURT_PAUSE_MILLIS);
            state.lastReason = "paused after a hit";
            return List.of();
        }
        if (hasThreat(companion)) {
            state.lastReason = "threat nearby";
            return List.of();
        }
        if (now - state.lastThreatAt < THREAT_COOLDOWN_MILLIS) {
            state.lastReason = "waiting after combat";
            return List.of();
        }
        if (companion.getHealth() < companion.getMaxHealth() * MIN_HEALTH_FRACTION) {
            state.lastReason = "hurt";
            return List.of();
        }
        if (companion.hunger().needsFood()) {
            state.lastReason = "hungry";
            return List.of();
        }
        if (companion.getOwner() instanceof ServerPlayer owner && ownerInMenu(owner)) {
            state.lastReason = "owner is busy in a menu";
            return List.of();
        }
        String blocked = state.budget.blockReason(now);
        if (blocked != null) {
            state.lastReason = blocked;
            return List.of();
        }

        List<Opportunity> found = OpportunityScanner.scan((ServerLevel) companion.level(),
                companion, now, state.budget.tidyAllowed(now), tier.policy());
        if (found.isEmpty()) {
            state.lastReason = "nothing worth doing";
            return List.of();
        }
        state.round = found;
        state.roundAt = now;
        state.lastReason = "offered " + found.size() + " candidate(s)";
        send(companion, found, tier);
        CompanionRuntimeState.publish(companion.getUUID(), "autonomy_opportunities",
                opportunitiesJson(found, tier));
        return found;
    }

    private static void send(CompanionEntity companion, List<Opportunity> found,
            AutonomyTier tier) {
        if (!(companion.getOwner() instanceof ServerPlayer owner)) {
            return;
        }
        if (!ServerPlayNetworking.canSend(owner, AgentClientPayloads.Opportunities.TYPE)) {
            return;
        }
        List<AgentClientPayloads.Opportunities.Row> rows = new ArrayList<>(found.size());
        for (Opportunity opportunity : found) {
            rows.add(new AgentClientPayloads.Opportunities.Row(opportunity.id(),
                    opportunity.why(), opportunity.priority()));
        }
        ServerPlayNetworking.send(owner, new AgentClientPayloads.Opportunities(rows, tier.id(),
                tier.autoRuns()));
    }

    // ---- the choice coming back -------------------------------------------------------------

    /**
     * Verifies that an autonomy plan answers a candidate this server just offered.
     *
     * <p>Without this a modified client could submit any free-tier plan at any time and call it
     * autonomy. The id alone is not enough — it has to be in the current round.
     */
    public static boolean consumeOpportunity(UUID companionId, String opportunityId) {
        State state = STATES.get(companionId);
        if (state == null || opportunityId == null || opportunityId.isBlank()) {
            return false;
        }
        if (System.currentTimeMillis() - state.roundAt > ROUND_TTL_MILLIS) {
            return false;
        }
        boolean offered = state.round.stream()
                .anyMatch(opportunity -> opportunity.id().equals(opportunityId));
        if (!offered) {
            return false;
        }
        state.round = List.of();
        state.roundAt = 0;
        return true;
    }

    /** Called by the plan boundary once an autonomy plan actually starts. */
    public static void noteAutonomyPlan(UUID companionId, String planId, String goal,
            int tokens, long now) {
        State state = state(companionId);
        state.autoPlanId = planId == null ? "" : planId;
        state.autoGoal = goal == null ? "" : goal;
        state.budget.recordRun(now);
        state.budget.recordTokens(now, tokens);
    }

    /** Called when the plan ends; success clears the breaker, failure counts toward it. */
    public static void onPlanFinished(UUID companionId, String planId,
            com.touhoulittlemaid.mini.core.plan.PlanRunner.Summary summary) {
        State state = STATES.get(companionId);
        if (state == null || !planId.equals(state.autoPlanId)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (summary.ok()) {
            state.budget.recordSuccess();
            if ("tidy_chests".equals(state.autoGoal)) {
                state.budget.recordTidy(now);
            }
        } else {
            state.budget.recordFailure(now);
            CompanionRuntimeState.publish(companionId, "autonomy_failed",
                    "{\"plan_id\":\"" + planId + "\",\"code\":\"" + summary.code() + "\"}");
        }
        state.autoPlanId = "";
        state.autoGoal = "";
    }

    // ---- helpers ----------------------------------------------------------------------------

    static boolean hasThreat(CompanionEntity companion) {
        AABB box = companion.getBoundingBox().inflate(THREAT_RADIUS);
        return !companion.level().getEntitiesOfClass(Monster.class, box).isEmpty();
    }

    static boolean ownerInMenu(ServerPlayer owner) {
        return owner.containerMenu != null && owner.containerMenu != owner.inventoryMenu;
    }

    private static String opportunitiesJson(List<Opportunity> found, AutonomyTier tier) {
        StringBuilder out = new StringBuilder("{\"tier\":\"")
                .append(tier.id()).append("\",\"items\":[");
        for (int index = 0; index < found.size(); index++) {
            Opportunity opportunity = found.get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"id\":\"").append(opportunity.id())
                    .append("\",\"why\":\"").append(escape(opportunity.why()))
                    .append("\",\"priority\":").append(opportunity.priority()).append('}');
        }
        return out.append("]}").toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String quote(String value) {
        return "\"" + escape(value == null ? "" : value) + "\"";
    }
}
