package com.touhoulittlemaid.mini.core.learning;

import java.util.List;

/**
 * A reviewed, executable template and the trust it has earned.
 *
 * <p>Supervised runs need one confirmation each; two successful ones promote it to trusted.
 * Three consecutive failures demote a trusted template back to supervised. A rollback disables
 * it and reopens the originating proposal, so the decision is genuinely reversible.
 */
public record PlanTemplate(String id, String name, String goal, List<TemplateStep> steps,
        TrustLevel trust, int supervisedRuns, int consecutiveFailures, int successes,
        int failures, String sourceProposalId, String sourcePlanId, long createdAt,
        long updatedAt, int revision) {

    public PlanTemplate {
        id = id == null ? "" : id.strip();
        name = name == null ? "" : name.strip();
        goal = goal == null ? "" : goal.strip();
        sourceProposalId = sourceProposalId == null ? "" : sourceProposalId;
        sourcePlanId = sourcePlanId == null ? "" : sourcePlanId;
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (trust == null) {
            trust = TrustLevel.SUPERVISED;
        }
    }

    public PlanTemplate with(TrustLevel level, int supervised, int streak, int wins, int losses,
            long now) {
        return new PlanTemplate(id, name, goal, steps, level, supervised, streak, wins, losses,
                sourceProposalId, sourcePlanId, createdAt, now, revision + 1);
    }

    public String signature() {
        return TemplateCatalog.signature(goal, steps);
    }
}
