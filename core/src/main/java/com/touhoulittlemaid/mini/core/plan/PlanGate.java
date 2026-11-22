package com.touhoulittlemaid.mini.core.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Authoritative static check: nothing runs until this passes.
 *
 * <p>The gate answers four questions before a tick is spent — is every module known, are the
 * parameters in range, is the risk allowed in this mode, and is the total cost inside
 * budget. Client-side quick checks are convenience only; this is the boundary.
 */
public final class PlanGate {

    private final ModuleRegistry registry;
    private final Policy policy;

    public PlanGate(ModuleRegistry registry, Policy policy) {
        this.registry = registry;
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    public Result check(ParsedPlan parsed) {
        if (parsed == null) {
            return Result.reject(FailureCodes.INVALID_PLAN, "plan is missing");
        }
        if (parsed.bytes() > PlanLimits.MAX_PLAN_BYTES) {
            return Result.reject(FailureCodes.PLAN_TOO_LARGE,
                    "plan is " + parsed.bytes() + " bytes, limit is "
                            + PlanLimits.MAX_PLAN_BYTES);
        }
        Plan plan = parsed.plan();
        if (plan.version() != PlanLimits.VERSION) {
            return Result.reject(FailureCodes.UNKNOWN_VERSION,
                    "plan version " + plan.version() + " is not supported");
        }
        if (plan.stepCount() > PlanLimits.MAX_STEPS) {
            return Result.reject(FailureCodes.TOO_MANY_STEPS,
                    "plan has " + plan.stepCount() + " steps, limit is "
                            + PlanLimits.MAX_STEPS);
        }
        if (plan.stepCount() > policy.maxSteps()) {
            return Result.reject(FailureCodes.TOO_MANY_STEPS,
                    "this mode allows " + policy.maxSteps() + " steps, plan has "
                            + plan.stepCount());
        }

        Set<String> bindings = new HashSet<>();
        int actions = 0;
        int ticks = 0;
        int destroyed = 0;

        for (int index = 0; index < plan.stepCount(); index++) {
            PlanStep step = plan.steps().get(index);
            String where = "step " + index + " (" + step.module() + ")";
            ActionModule module = registry.find(step.module()).orElse(null);
            if (module == null) {
                return Result.reject(FailureCodes.UNKNOWN_MODULE,
                        where + ": unknown module, known modules are " + registry.ids());
            }
            if (!policy.allowsModule(module.id())) {
                return Result.reject(FailureCodes.RISK_DENIED,
                        where + ": this mode only allows " + policy.allowedModules());
            }
            // A destructive module is allowed either because the mode permits destruction, or
            // because the mode names it explicitly — chores harvest crops and tidy chests while
            // still refusing to mine. Listing it is a stronger statement than a risk class.
            if (module.risk() == RiskClass.DESTROY && !policy.allowDestroy()
                    && !policy.allowedModules().contains(module.id())) {
                return Result.reject(FailureCodes.CONFIRMATION_REQUIRED,
                        where + ": " + module.risk()
                                + " modules need an explicit confirmation");
            }

            Result paramResult = checkParams(module, step, bindings, where);
            if (!paramResult.ok()) {
                return paramResult;
            }

            if (step.hasCondition()) {
                Result predicateResult = parsePredicate(step.condition(), where + ".if");
                if (!predicateResult.ok()) {
                    return predicateResult;
                }
            }
            if (step.repeat().hasCondition()) {
                Result predicateResult = parsePredicate(step.repeat().whileCondition(),
                        where + ".repeat.while");
                if (!predicateResult.ok()) {
                    return predicateResult;
                }
            }

            int multiplier = step.repeat().max();
            ModuleParams effective = ModuleParams.of(
                    module.spec().applyDefaults(step.params().raw()));
            actions += Math.max(1, module.estimatedActions(effective)) * multiplier;
            ticks += Math.max(1, module.estimatedTicks(effective)) * multiplier;
            destroyed += Math.max(0, module.destroyedBlocks(effective)) * multiplier;

            if (step.hasBind()) {
                bindings.add(step.bind());
            }
        }

        if (actions > policy.maxActions()) {
            return Result.reject(FailureCodes.BUDGET_EXCEEDED,
                    "plan needs " + actions + " actions, this mode allows "
                            + policy.maxActions());
        }
        if (ticks > policy.maxTicks()) {
            return Result.reject(FailureCodes.BUDGET_EXCEEDED,
                    "plan needs about " + (ticks / 20) + " seconds, this mode allows "
                            + (policy.maxTicks() / 20));
        }
        if (destroyed > policy.maxDestroyed()) {
            return Result.reject(FailureCodes.BUDGET_EXCEEDED,
                    "plan removes " + destroyed + " blocks, this mode allows "
                            + policy.maxDestroyed());
        }
        return Result.accept();
    }

    private Result checkParams(ActionModule module, PlanStep step, Set<String> bindings,
            String where) {
        ModuleSpec spec = module.spec();
        List<String> issues = new ArrayList<>();
        for (String key : step.params().raw().keySet()) {
            if (spec.param(key) == null) {
                issues.add("unknown parameter " + key);
            }
        }
        for (ParamSpec declared : spec.params()) {
            Object value = step.params().get(declared.name());
            if (value == null) {
                if (declared.required()) {
                    issues.add("missing required parameter " + declared.name());
                }
                continue;
            }
            String issue = checkValue(declared, value, bindings);
            if (issue != null) {
                issues.add(issue);
            }
        }
        if (!issues.isEmpty()) {
            return Result.reject(FailureCodes.INVALID_PARAMS, where + ": " + issues);
        }
        return Result.accept();
    }

    private String checkValue(ParamSpec declared, Object value, Set<String> bindings) {
        String name = declared.name();
        // A bound reference is checked for existence here and for type at run time, when the
        // earlier step has actually produced its output.
        if (value instanceof String text && ModuleParams.isReference(text)) {
            return bindings.contains(text.substring(1))
                    ? null
                    : "parameter " + name + " references " + text + " before it is bound";
        }
        return switch (declared.type()) {
            case STRING -> {
                if (!(value instanceof String text)) {
                    yield "parameter " + name + " must be a string";
                }
                if (!declared.allowed().isEmpty() && !declared.allowed().contains(text)) {
                    yield "parameter " + name + " must be one of " + declared.allowed();
                }
                yield null;
            }
            case INT -> {
                if (!(value instanceof Number number)) {
                    yield "parameter " + name + " must be a number";
                }
                double numeric = number.doubleValue();
                if (numeric != Math.rint(numeric)) {
                    yield "parameter " + name + " must be an integer";
                }
                yield checkRange(declared, numeric);
            }
            case DOUBLE -> {
                if (!(value instanceof Number number)) {
                    yield "parameter " + name + " must be a number";
                }
                yield checkRange(declared, number.doubleValue());
            }
            case BOOLEAN -> value instanceof Boolean
                    ? null
                    : "parameter " + name + " must be a boolean";
            case STRING_LIST -> {
                if (!(value instanceof List<?> list)) {
                    yield "parameter " + name + " must be an array of strings";
                }
                for (Object element : list) {
                    if (!(element instanceof String text)) {
                        yield "parameter " + name + " must contain only strings";
                    }
                    if (ModuleParams.isReference(text)) {
                        if (!bindings.contains(text.substring(1))) {
                            yield "parameter " + name + " references " + text
                                    + " before it is bound";
                        }
                    }
                }
                yield null;
            }
        };
    }

    private String checkRange(ParamSpec declared, double value) {
        if (declared.min() != null && value < declared.min()) {
            return "parameter " + declared.name() + " is below " + format(declared.min());
        }
        if (declared.max() != null && value > declared.max()) {
            return "parameter " + declared.name() + " is above " + format(declared.max());
        }
        return null;
    }

    private static String format(double value) {
        return value == Math.rint(value)
                ? Long.toString((long) value)
                : String.format(Locale.ROOT, "%.2f", value);
    }

    private Result parsePredicate(String text, String where) {
        try {
            PlanPredicate.parse(text);
            return Result.accept();
        } catch (PlanFormatException exception) {
            return Result.reject(exception.code(), where + ": " + exception.getMessage());
        }
    }

    /** What the plan is allowed to do. */
    public enum Mode {
        /** Player asked for it directly. */
        COMMAND,
        /** Autonomous chores whitelist. */
        CHORES,
        /** Autonomous free tier. */
        FREE
    }

    public record Policy(Mode mode, int maxSteps, int maxActions, int maxTicks,
            int maxDestroyed, boolean allowDestroy, List<String> allowedModules) {

        /**
         * Chores: what a companion may do on its own without being asked.
         *
         * <p>A whitelist, not a risk class. Harvesting destroys crops and tidying moves
         * container contents, yet both are chores; mining is not, because it leaves the base.
         */
        public static final List<String> CHORE_MODULES = List.of("say", "goto", "harvest_area",
                "plant_area", "till_area", "deposit_items", "withdraw_items", "smelt_items",
                "tidy_chests", "move_slot", "pickup_items");

        public Policy {
            if (mode == null) {
                throw new IllegalArgumentException("mode is required");
            }
            if (maxSteps < 1 || maxSteps > PlanLimits.MAX_STEPS) {
                throw new IllegalArgumentException("maxSteps is outside the plan limit");
            }
            allowedModules = allowedModules == null ? List.of() : List.copyOf(allowedModules);
        }

        public boolean allowsModule(String id) {
            return allowedModules.isEmpty() || allowedModules.contains(id);
        }

        /** True when every module in a candidate's plan is inside this tier's whitelist. */
        public boolean allowsModules(List<String> ids) {
            return allowedModules.isEmpty() || allowedModules.containsAll(ids);
        }

        /** A command that must not remove blocks without a second confirmation. */
        public static Policy command() {
            return new Policy(Mode.COMMAND, PlanLimits.MAX_STEPS, PlanLimits.MAX_ACTIONS,
                    PlanLimits.MAX_TICKS, PlanLimits.MAX_DESTROYED_BLOCKS, false, List.of());
        }

        /** A command the player has explicitly confirmed, including destructive modules. */
        public static Policy commandConfirmed() {
            return new Policy(Mode.COMMAND, PlanLimits.MAX_STEPS, PlanLimits.MAX_ACTIONS,
                    PlanLimits.MAX_TICKS, PlanLimits.MAX_DESTROYED_BLOCKS, true, List.of());
        }

        public static Policy chores() {
            return new Policy(Mode.CHORES, 12, 200, 300 * 20, 64, false, CHORE_MODULES);
        }

        public static Policy free() {
            return new Policy(Mode.FREE, PlanLimits.MAX_STEPS, PlanLimits.MAX_ACTIONS,
                    PlanLimits.MAX_TICKS, 64, true, List.of());
        }
    }

    public record Result(boolean ok, String code, String message) {

        public static Result accept() {
            return new Result(true, FailureCodes.OK, "plan accepted");
        }

        public static Result reject(String code, String message) {
            return new Result(false, code, message);
        }
    }
}
