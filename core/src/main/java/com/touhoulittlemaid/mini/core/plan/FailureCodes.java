package com.touhoulittlemaid.mini.core.plan;

/**
 * Machine-readable failure codes shared by modules, the gate and the runner.
 *
 * <p>Codes are part of the 0.2 contract: clients switch on them to build the "what went
 * wrong" line, so they must not be renamed without a plan schema bump.
 */
public final class FailureCodes {

    public static final String OK = "ok";

    // Plan shape.
    public static final String UNKNOWN_MODULE = "unknown_module";
    public static final String UNKNOWN_VERSION = "unknown_version";
    public static final String INVALID_PLAN = "invalid_plan";
    public static final String INVALID_PARAMS = "invalid_params";
    public static final String INVALID_REPEAT = "invalid_repeat";
    public static final String INVALID_POLICY = "invalid_policy";
    public static final String PLAN_TOO_LARGE = "plan_too_large";
    public static final String TOO_MANY_STEPS = "too_many_steps";
    public static final String UNBOUND_BINDING = "unbound_binding";
    public static final String UNKNOWN_PREDICATE = "unknown_predicate";
    public static final String BUDGET_EXCEEDED = "budget_exceeded";
    public static final String RISK_DENIED = "risk_denied";
    public static final String CONFIRMATION_REQUIRED = "confirmation_required";
    public static final String CANCELLED = "cancelled";

    // Reachability.
    public static final String UNREACHABLE = "unreachable";
    public static final String BLOCKED = "blocked";
    public static final String INTERRUPTED = "interrupted";
    public static final String TIMEOUT = "timeout";
    public static final String BUSY = "busy";
    public static final String NOT_FOUND = "not_found";

    // Tools and materials.
    public static final String TOOL_NOT_IN_INVENTORY = "tool_not_in_inventory";
    public static final String WRONG_TOOL = "wrong_tool";
    public static final String TOOL_LOST = "tool_lost";
    public static final String MISSING_ITEM = "missing_item";

    // Containers and space.
    public static final String INVENTORY_FULL = "inventory_full";
    public static final String LOCKED = "locked";
    public static final String TARGET_BUSY = "target_busy";
    public static final String NO_DESTINATION = "no_destination";
    public static final String KEEP_ZONE = "keep_zone";

    // Recipes and production.
    public static final String NO_RECIPE = "no_recipe";
    public static final String UNSUPPORTED_RECIPE_TYPE = "unsupported_recipe_type";
    public static final String NO_DROP = "no_drop";
    public static final String NOT_HARVESTABLE = "not_harvestable";

    // Knowledge and permission.
    public static final String UNKNOWN_CATEGORY = "unknown_category";
    public static final String PROTECTED = "protected";
    public static final String COUNT_MISMATCH = "count_mismatch";
    public static final String UNSAFE_AREA = "unsafe_area";

    private FailureCodes() {
    }
}
