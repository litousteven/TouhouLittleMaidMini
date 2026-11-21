package com.touhoulittlemaid.mini.core.plan;

/**
 * One call in a plan.
 *
 * @param module    registered module id
 * @param params    declared parameters, possibly containing {@code $binding} references
 * @param bind      optional name that stores this step's output for later steps
 * @param condition optional whitelisted predicate; when false the step is skipped
 * @param onFailure what to do when the attempt fails
 * @param repeat    bounded repetition, {@link RepeatSpec#ONCE} by default
 */
public record PlanStep(
        String module,
        ModuleParams params,
        String bind,
        String condition,
        FailurePolicy onFailure,
        RepeatSpec repeat) {

    public PlanStep {
        if (module == null || module.isBlank()) {
            throw new IllegalArgumentException("module id is required");
        }
        params = params == null ? ModuleParams.empty() : params;
        bind = bind == null ? "" : bind.strip();
        condition = condition == null ? "" : condition.strip();
        onFailure = onFailure == null ? FailurePolicy.abort() : onFailure;
        repeat = repeat == null ? RepeatSpec.ONCE : repeat;
    }

    public static PlanStep of(String module, ModuleParams params) {
        return new PlanStep(module, params, "", "", FailurePolicy.abort(), RepeatSpec.ONCE);
    }

    public boolean hasBind() {
        return !bind.isEmpty();
    }

    public boolean hasCondition() {
        return !condition.isEmpty();
    }
}
