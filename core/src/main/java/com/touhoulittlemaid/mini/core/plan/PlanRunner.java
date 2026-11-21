package com.touhoulittlemaid.mini.core.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executes a gated plan one module at a time, one tick at a time.
 *
 * <p>Nothing here blocks. A module that needs longer reports
 * {@link ModuleResult#running()} and is polled again next tick, which is what keeps a plan
 * off the server thread's critical path. Failure handling is exactly the declared policy:
 * abort, continue, or a bounded retry.
 */
public final class PlanRunner {

    public enum State {
        IDLE,
        RUNNING,
        FINISHED,
        FAILED,
        CANCELLED
    }

    public record StepOutcome(int index, String module, boolean skipped, boolean success,
            String code, String message) {
    }

    public record Summary(State state, boolean ok, String code, String message,
            int stepsCompleted, int stepsSkipped, int stepsFailed, int actions, int ticks,
            List<StepOutcome> steps, Map<String, Map<String, Object>> bindings) {
    }

    private final Plan plan;
    private final ModuleRegistry registry;
    private final ModuleContext moduleContext;
    private final PredicateContext predicates;
    private final PlanListener listener;

    private State state = State.IDLE;
    private int index;
    private int retriesLeft;
    private int remainingRepeats;
    private boolean stepAnnounced;
    private ModuleExecution execution;
    private int actions;
    private int ticks;
    private String code = FailureCodes.OK;
    private String message = "";
    private final List<StepOutcome> outcomes = new ArrayList<>();
    private final Map<String, Map<String, Object>> bindings = new LinkedHashMap<>();
    private final Set<String> okSteps = new HashSet<>();
    private final Set<String> failedSteps = new HashSet<>();

    public PlanRunner(Plan plan, ModuleRegistry registry, ModuleContext moduleContext,
            PredicateContext predicates, PlanListener listener) {
        this.plan = java.util.Objects.requireNonNull(plan, "plan");
        this.registry = java.util.Objects.requireNonNull(registry, "registry");
        this.moduleContext = java.util.Objects.requireNonNull(moduleContext, "moduleContext");
        this.predicates = java.util.Objects.requireNonNull(predicates, "predicates");
        this.listener = listener == null ? new PlanListener() { } : listener;
    }

    public void start() {
        if (state != State.IDLE) {
            throw new IllegalStateException("plan already started: " + state);
        }
        state = State.RUNNING;
        listener.planStarted(plan);
        advance();
    }

    /** Advances at most one module attempt by one tick. Safe to call when idle. */
    public void tick() {
        if (state != State.RUNNING) {
            return;
        }
        ticks++;
        if (ticks > PlanLimits.MAX_TICKS) {
            abort(FailureCodes.BUDGET_EXCEEDED,
                    "plan exceeded the " + (PlanLimits.MAX_TICKS / 20) + " second limit");
            return;
        }
        if (execution != null) {
            ModuleResult result;
            try {
                result = execution.tick();
            } catch (RuntimeException exception) {
                result = ModuleResult.failed(FailureCodes.INTERRUPTED,
                        "module threw " + exception.getClass().getSimpleName() + ": "
                                + exception.getMessage());
            }
            if (result == null) {
                result = ModuleResult.failed(FailureCodes.INTERRUPTED,
                        "module returned no result");
            }
            if (result.isRunning()) {
                return;
            }
            execution = null;
            handleResult(currentStep(), result);
            return;
        }
        advance();
    }

    public void cancel(String reason) {
        if (state != State.RUNNING) {
            return;
        }
        if (execution != null) {
            execution.cancel();
            execution = null;
        }
        state = State.CANCELLED;
        code = FailureCodes.CANCELLED;
        message = reason == null || reason.isBlank() ? "plan cancelled" : reason;
        listener.planFinished(summary());
    }

    public State state() {
        return state;
    }

    public int currentIndex() {
        return index;
    }

    public String currentModule() {
        return index >= 0 && index < plan.stepCount() ? plan.steps().get(index).module() : "";
    }

    public int actions() {
        return actions;
    }

    public int ticks() {
        return ticks;
    }

    public Map<String, Map<String, Object>> bindings() {
        return Map.copyOf(bindings);
    }

    public Summary summary() {
        int completed = 0;
        int skipped = 0;
        int failed = 0;
        for (StepOutcome outcome : outcomes) {
            if (outcome.skipped()) {
                skipped++;
            } else if (outcome.success()) {
                completed++;
            } else {
                failed++;
            }
        }
        return new Summary(state, state == State.FINISHED, code, message, completed, skipped,
                failed, actions, ticks, List.copyOf(outcomes), Map.copyOf(bindings));
    }

    private void advance() {
        while (state == State.RUNNING) {
            if (index >= plan.stepCount()) {
                finish();
                return;
            }
            PlanStep step = plan.steps().get(index);
            if (step.hasCondition()) {
                Boolean condition = evaluate(step.condition());
                if (condition == null) {
                    return;
                }
                if (!condition) {
                    outcomes.add(new StepOutcome(index, step.module(), true, true, "skipped",
                            "condition was false: " + step.condition()));
                    listener.stepFinished(index, step.module(),
                            ModuleResult.ok("skipped", "condition was false"), true);
                    index++;
                    continue;
                }
            }
            remainingRepeats = step.repeat().max();
            retriesLeft = step.onFailure().retries();
            stepAnnounced = false;
            launch(step);
            return;
        }
    }

    private void launch(PlanStep step) {
        actions++;
        if (actions > PlanLimits.MAX_ACTIONS) {
            abort(FailureCodes.BUDGET_EXCEEDED,
                    "plan exceeded the " + PlanLimits.MAX_ACTIONS + " action limit");
            return;
        }
        ActionModule module = registry.find(step.module()).orElse(null);
        if (module == null) {
            abort(FailureCodes.UNKNOWN_MODULE, "unknown module: " + step.module());
            return;
        }
        ModuleParams resolved;
        try {
            resolved = ModuleParams.of(module.spec().applyDefaults(
                    step.params().resolve(bindings).raw()));
        } catch (PlanFormatException exception) {
            failAttempt(step, ModuleResult.failed(exception.code(), exception.getMessage()));
            return;
        }

        ModuleResult check;
        try {
            check = module.check(moduleContext, resolved);
        } catch (RuntimeException exception) {
            check = ModuleResult.failed(FailureCodes.INTERRUPTED,
                    "module check threw " + exception.getClass().getSimpleName() + ": "
                            + exception.getMessage());
        }
        if (check != null && check.isError()) {
            failAttempt(step, check);
            return;
        }
        if (!stepAnnounced) {
            stepAnnounced = true;
            listener.stepStarted(index, module.id());
        }
        execution = module.start(moduleContext, resolved);
        if (execution == null) {
            execution = null;
            failAttempt(step, ModuleResult.failed(FailureCodes.INTERRUPTED,
                    "module did not start"));
        }
    }

    private void handleResult(PlanStep step, ModuleResult result) {
        if (result.isSuccess()) {
            if (step.hasBind()) {
                bindings.put(step.bind(), result.output());
                okSteps.add(step.bind());
            }
            remainingRepeats--;
            boolean again = false;
            if (remainingRepeats > 0) {
                if (!step.repeat().hasCondition()) {
                    again = true;
                } else {
                    Boolean condition = evaluate(step.repeat().whileCondition());
                    if (condition == null) {
                        return;
                    }
                    again = condition;
                }
            }
            if (again) {
                launch(step);
                return;
            }
            finishStep(step, result);
            index++;
            advance();
            return;
        }
        failAttempt(step, result);
    }

    private void failAttempt(PlanStep step, ModuleResult result) {
        if (step.hasBind()) {
            failedSteps.add(step.bind());
        }
        if (step.onFailure().kind() == FailurePolicy.Kind.RETRY && retriesLeft > 0) {
            retriesLeft--;
            launch(step);
            return;
        }
        finishStep(step, result);
        if (step.onFailure().kind() == FailurePolicy.Kind.CONTINUE) {
            index++;
            advance();
            return;
        }
        abort(result.code(), "step " + index + " (" + step.module() + "): "
                + result.message());
    }

    private void finishStep(PlanStep step, ModuleResult result) {
        outcomes.add(new StepOutcome(index, step.module(), false, result.isSuccess(),
                result.code(), result.message()));
        listener.stepFinished(index, step.module(), result, false);
    }

    private PlanStep currentStep() {
        return index >= 0 && index < plan.stepCount() ? plan.steps().get(index) : null;
    }

    private Boolean evaluate(String predicateText) {
        PlanPredicate predicate;
        try {
            predicate = PlanPredicate.parse(predicateText);
        } catch (PlanFormatException exception) {
            abort(exception.code(), exception.getMessage());
            return null;
        }
        try {
            return predicate.test(predicates);
        } catch (RuntimeException exception) {
            abort(FailureCodes.INTERRUPTED,
                    "predicate failed: " + predicateText);
            return null;
        }
    }

    private void finish() {
        state = State.FINISHED;
        code = FailureCodes.OK;
        message = plan.stepCount() == 0 ? "empty plan" : "plan finished";
        listener.planFinished(summary());
    }

    private void abort(String failureCode, String failureMessage) {
        if (execution != null) {
            execution.cancel();
            execution = null;
        }
        state = State.FAILED;
        code = failureCode == null || failureCode.isBlank()
                ? FailureCodes.INTERRUPTED : failureCode;
        message = failureMessage == null ? "" : failureMessage;
        listener.planFinished(summary());
    }

    /** Step ids usable in {@code step.ok("<id>")}: the bind name of that step. */
    public boolean stepOk(String stepId) {
        return okSteps.contains(stepId);
    }

    public boolean stepFailed(String stepId) {
        return failedSteps.contains(stepId);
    }
}
