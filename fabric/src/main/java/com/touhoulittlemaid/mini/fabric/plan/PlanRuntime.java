package com.touhoulittlemaid.mini.fabric.plan;

import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ModuleRegistry;
import com.touhoulittlemaid.mini.core.plan.ModuleResult;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.Plan;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanListener;
import com.touhoulittlemaid.mini.core.plan.PlanRunner;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Owns running plans and drives them once per server tick.
 *
 * <p>A session is one companion running one plan. Submission is gated once, at the door;
 * after that every tick costs a bounded amount of work and publishes events on the existing
 * bus, so an Agent watching {@code companion_wait_events} sees plan progress without a new
 * tool.
 */
public final class PlanRuntime {

    private static final Map<UUID, Session> ACTIVE = new ConcurrentHashMap<>();
    private static final AtomicLong IDS = new AtomicLong();
    private static final java.util.List<CompletionListener> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private PlanRuntime() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(PlanRuntime::onServerTick);
    }

    public record SubmitResult(boolean ok, String code, String message, String planId) {
    }

    /** A running plan as the UI needs it: where it is, not what it holds. */
    public record Snapshot(String planId, int step, int steps, String module, int actions,
            int ticks, String source) {
    }

    /** Who asked for the plan: the owner in chat, the owner through a command, or the tier. */
    public static final String SOURCE_COMMAND = "command";
    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_AUTONOMY = "autonomy";

    /** Called once per plan, after the runner stopped, on the server thread. */
    public interface CompletionListener {

        void finished(UUID companionId, String planId, PlanRunner.Summary summary);
    }

    /**
     * Registers a completion listener.
     *
     * <p>The summary has to be taken here: once a session leaves {@link #ACTIVE} the runner is
     * gone, so a poller that only looks at snapshots can never learn why a plan ended.
     */
    public static void addCompletionListener(CompletionListener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void clearCompletionListeners() {
        LISTENERS.clear();
    }

    /** Validates and starts a plan for one companion. Must run on the server thread. */
    public static SubmitResult submit(CompanionEntity companion, ParsedPlan parsed,
            PlanGate.Policy policy) {
        return submit(companion, parsed, policy, SOURCE_COMMAND);
    }

    public static SubmitResult submit(CompanionEntity companion, ParsedPlan parsed,
            PlanGate.Policy policy, String source) {
        ModuleRegistry registry = FabricModules.registry();
        PlanGate.Result gate = new PlanGate(registry, policy).check(parsed);
        if (!gate.ok()) {
            return new SubmitResult(false, gate.code(), gate.message(), "");
        }
        Session existing = ACTIVE.get(companion.getUUID());
        if (existing != null && existing.runner.state() == PlanRunner.State.RUNNING) {
            return new SubmitResult(false, FailureCodes.BUSY,
                    "companion is already running plan " + existing.planId, existing.planId);
        }

        String planId = "plan-" + IDS.incrementAndGet();
        FabricModuleContext context = new FabricModuleContext(companion, planId);
        FabricPredicates predicates = new FabricPredicates(context);
        Session session = new Session(planId, companion, context, parsed.plan().stepCount(),
                source == null ? SOURCE_COMMAND : source);
        PlanRunner runner = new PlanRunner(parsed.plan(), registry, context, predicates,
                new EventPublisher(session));
        predicates.attach(runner);
        session.runner = runner;

        ACTIVE.put(companion.getUUID(), session);
        try {
            runner.start();
        } catch (RuntimeException exception) {
            ACTIVE.remove(companion.getUUID());
            return new SubmitResult(false, FailureCodes.INTERRUPTED,
                    "plan failed to start: " + exception.getMessage(), planId);
        }
        if (runner.state() != PlanRunner.State.RUNNING) {
            finish(session);
        }
        return new SubmitResult(true, "started", "plan started", planId);
    }

    public static boolean cancel(UUID companionId, String reason) {
        Session session = ACTIVE.get(companionId);
        if (session == null) {
            return false;
        }
        session.runner.cancel(reason);
        finish(session);
        return true;
    }

    public static Optional<String> activePlanId(UUID companionId) {
        Session session = ACTIVE.get(companionId);
        return session == null ? Optional.empty() : Optional.of(session.planId);
    }

    public static boolean isRunning(UUID companionId) {
        Session session = ACTIVE.get(companionId);
        return session != null && session.runner.state() == PlanRunner.State.RUNNING;
    }

    /** Where the running plan is right now, if any. */
    public static Optional<Snapshot> snapshot(UUID companionId) {
        Session session = ACTIVE.get(companionId);
        if (session == null || session.runner.state() != PlanRunner.State.RUNNING) {
            return Optional.empty();
        }
        PlanRunner runner = session.runner;
        return Optional.of(new Snapshot(session.planId, runner.currentIndex(), session.steps,
                runner.currentModule(), runner.actions(), runner.ticks(), session.source));
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** Stops everything, used when the server stops so no session outlives the world. */
    public static void cancelAll(String reason) {
        for (UUID id : ACTIVE.keySet()) {
            cancel(id, reason);
        }
    }

    private static void onServerTick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : ACTIVE.entrySet()) {
            Session session = entry.getValue();
            try {
                if (session.runner.state() != PlanRunner.State.RUNNING) {
                    finish(session);
                    continue;
                }
                CompanionEntity companion = session.companion;
                if (!companion.isAlive() || companion.isRemoved()) {
                    session.runner.cancel("companion is gone");
                    finish(session);
                    continue;
                }
                session.runner.tick();
                if (session.runner.state() != PlanRunner.State.RUNNING) {
                    finish(session);
                }
            } catch (RuntimeException exception) {
                session.runner.cancel("plan tick failed: " + exception.getClass().getSimpleName());
                finish(session);
                CompanionRuntimeState.publish(session.companion.getUUID(), "plan_error",
                        "{\"plan_id\":\"" + session.planId + "\",\"code\":\"interrupted\"}");
            }
        }
    }

    /** Removes one session and tells listeners exactly once. */
    private static void finish(Session session) {
        if (session.notified) {
            return;
        }
        session.notified = true;
        ACTIVE.remove(session.companion.getUUID(), session);
        PlanRunner.Summary summary = session.runner.summary();
        for (CompletionListener listener : LISTENERS) {
            try {
                listener.finished(session.companion.getUUID(), session.planId, summary);
            } catch (RuntimeException exception) {
                com.touhoulittlemaid.mini.fabric.TlmmFabric.LOGGER.warn(
                        "[plan] completion listener failed: {}",
                        exception.getClass().getSimpleName());
            }
        }
    }

    private static final class Session {

        final String planId;
        final CompanionEntity companion;
        final FabricModuleContext context;
        final int steps;
        final String source;
        PlanRunner runner;
        boolean notified;

        Session(String planId, CompanionEntity companion, FabricModuleContext context,
                int steps, String source) {
            this.planId = planId;
            this.companion = companion;
            this.context = context;
            this.steps = steps;
            this.source = source;
        }
    }

    /** Turns runner callbacks into bus events; no second notification path. */
    private static final class EventPublisher implements PlanListener {

        private final Session session;

        EventPublisher(Session session) {
            this.session = session;
        }

        @Override
        public void planStarted(Plan plan) {
            publish("plan_started", "{\"plan_id\":" + quote(session.planId)
                    + ",\"goal\":" + quote(plan.goal())
                    + ",\"source\":" + quote(session.source)
                    + ",\"steps\":" + plan.stepCount() + "}");
        }

        @Override
        public void stepStarted(int index, String module) {
            publish("step_started", "{\"plan_id\":" + quote(session.planId)
                    + ",\"step\":" + index + ",\"module\":" + quote(module) + "}");
        }

        @Override
        public void stepFinished(int index, String module, ModuleResult result, boolean skipped) {
            publish("step_finished", "{\"plan_id\":" + quote(session.planId)
                    + ",\"step\":" + index + ",\"module\":" + quote(module)
                    + ",\"ok\":" + (skipped || result.isSuccess())
                    + ",\"skipped\":" + skipped
                    + ",\"code\":" + quote(skipped ? "skipped" : result.code())
                    + ",\"message\":" + quote(result.message()) + "}");
        }

        @Override
        public void planFinished(PlanRunner.Summary summary) {
            publish("plan_finished", "{\"plan_id\":" + quote(session.planId)
                    + ",\"source\":" + quote(session.source)
                    + ",\"ok\":" + summary.ok()
                    + ",\"state\":" + quote(summary.state().name())
                    + ",\"code\":" + quote(summary.code())
                    + ",\"message\":" + quote(summary.message())
                    + ",\"completed\":" + summary.stepsCompleted()
                    + ",\"failed\":" + summary.stepsFailed()
                    + ",\"skipped\":" + summary.stepsSkipped()
                    + ",\"actions\":" + summary.actions()
                    + ",\"ticks\":" + summary.ticks() + "}");
        }

        private void publish(String kind, String data) {
            CompanionRuntimeState.publish(session.companion.getUUID(), kind, data);
        }
    }

    private static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
