package com.touhoulittlemaid.mini.core.plan;

import java.util.List;

/**
 * An ordered list of module calls with a stated goal.
 *
 * <p>Data, not code: no branches, loops, variables or expressions beyond the whitelisted
 * predicates. That is what makes full static validation possible.
 */
public record Plan(int version, String goal, List<PlanStep> steps) {

    public Plan {
        goal = goal == null ? "" : goal.strip();
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static Plan of(String goal, List<PlanStep> steps) {
        return new Plan(PlanLimits.VERSION, goal, steps);
    }

    public int stepCount() {
        return steps.size();
    }
}
