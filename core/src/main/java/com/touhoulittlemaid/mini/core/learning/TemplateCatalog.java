package com.touhoulittlemaid.mini.core.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The whole learning state machine, pure Java so it can be tested without a server.
 *
 * <p>Invariants that matter: nothing runs before adoption; a supervised template needs one
 * confirmation per run; two successful supervised runs earn trust; three consecutive failures
 * take it away again; a rollback disables the template and reopens the proposal.
 */
public final class TemplateCatalog {

    public static final int MAX_TEMPLATES = 64;
    public static final int MAX_PROPOSALS = 32;
    public static final int SUPERVISED_RUNS_TO_TRUST = 2;
    public static final int FAILURES_TO_DEMOTE = 3;

    private static final AtomicInteger IDS = new AtomicInteger();

    private final Map<String, TemplateProposal> proposals = new LinkedHashMap<>();
    private final Map<String, PlanTemplate> templates = new LinkedHashMap<>();
    /** One confirmed supervised run, consumed when the run actually starts. */
    private final Map<String, Boolean> confirmed = new LinkedHashMap<>();

    public record Mutation(boolean ok, String code, String message) {

        public static Mutation ok(String message) {
            return new Mutation(true, "ok", message);
        }

        public static Mutation fail(String code, String message) {
            return new Mutation(false, code, message);
        }
    }

    public record Decision(boolean allowed, String code) {
    }

    public synchronized List<TemplateProposal> proposals() {
        return List.copyOf(proposals.values());
    }

    public synchronized List<PlanTemplate> templates() {
        return List.copyOf(templates.values());
    }

    public synchronized Optional<TemplateProposal> proposal(String id) {
        return Optional.ofNullable(proposals.get(id));
    }

    public synchronized Optional<PlanTemplate> template(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    public synchronized long pendingCount() {
        return proposals.values().stream()
                .filter(row -> row.status() == TemplateProposal.Status.PENDING).count();
    }

    public static String signature(String goal, List<TemplateStep> steps) {
        String modules = steps.stream().map(TemplateStep::module)
                .collect(Collectors.joining(","));
        return (goal == null ? "" : goal.strip()) + "|" + modules;
    }

    /** Only a successful, reusable plan earns a proposal; duplicates are silently skipped. */
    public synchronized Mutation propose(TemplateDraft draft, String sourcePlanId, long now) {
        if (draft == null || draft.steps().isEmpty()) {
            return Mutation.fail("invalid_draft", "there is nothing reusable in that plan");
        }
        String signature = signature(draft.goal(), draft.steps());
        boolean duplicate = proposals.values().stream()
                .anyMatch(row -> signature(row.goal(), row.steps()).equals(signature))
                || templates.values().stream()
                .anyMatch(row -> row.trust() != TrustLevel.DISABLED
                        && row.signature().equals(signature));
        if (duplicate) {
            return Mutation.fail("duplicate", "a proposal for this sequence already exists");
        }
        if (proposals.size() >= MAX_PROPOSALS) {
            return Mutation.fail("too_many_proposals", "review the existing proposals first");
        }
        String id = "tpl-" + now + "-" + String.format("%04d", IDS.incrementAndGet() % 10_000);
        proposals.put(id, new TemplateProposal(id, draft.name(), draft.goal(),
                draft.description(), draft.steps(), sourcePlanId, now,
                TemplateProposal.Status.PENDING));
        return Mutation.ok("proposed " + id + " (" + draft.steps().size() + " steps)");
    }

    public synchronized Mutation adopt(String proposalId, long now) {
        TemplateProposal proposal = proposals.get(proposalId);
        if (proposal == null) {
            return Mutation.fail("no_proposal", "no proposal with that id");
        }
        if (proposal.status() != TemplateProposal.Status.PENDING) {
            return Mutation.fail("not_pending", "that proposal is " + proposal.status().id());
        }
        if (templates.size() >= MAX_TEMPLATES) {
            return Mutation.fail("too_many_templates", "retire a template first");
        }
        String id = "tmpl-" + now + "-" + String.format("%04d", IDS.incrementAndGet() % 10_000);
        templates.put(id, new PlanTemplate(id, proposal.name(), proposal.goal(),
                proposal.steps(), TrustLevel.SUPERVISED, 0, 0, 0, 0, proposal.id(),
                proposal.sourcePlanId(), now, now, 1));
        proposals.put(proposalId, withStatus(proposal, TemplateProposal.Status.ADOPTED));
        return Mutation.ok("adopted " + id + " (supervised)");
    }

    public synchronized Mutation reject(String proposalId) {
        TemplateProposal proposal = proposals.get(proposalId);
        if (proposal == null || proposal.status() != TemplateProposal.Status.PENDING) {
            return Mutation.fail("not_pending", "nothing pending with that id");
        }
        proposals.put(proposalId, withStatus(proposal, TemplateProposal.Status.REJECTED));
        return Mutation.ok("rejected " + proposalId);
    }

    /** Disables the template and reopens its proposal, so the adoption can be undone. */
    public synchronized Mutation rollback(String templateId, long now) {
        PlanTemplate template = templates.get(templateId);
        if (template == null) {
            return Mutation.fail("no_template", "no template with that id");
        }
        if (template.trust() == TrustLevel.DISABLED) {
            return Mutation.fail("already_disabled", "that template is already rolled back");
        }
        templates.put(templateId, template.with(TrustLevel.DISABLED, 0, 0,
                template.successes(), template.failures(), now));
        confirmed.remove(templateId);
        TemplateProposal proposal = proposals.get(template.sourceProposalId());
        if (proposal != null) {
            proposals.put(proposal.id(), withStatus(proposal,
                    TemplateProposal.Status.PENDING));
        }
        return Mutation.ok("rolled back " + templateId + "; the proposal is pending again");
    }

    public synchronized Decision executionDecision(String templateId) {
        PlanTemplate template = templates.get(templateId);
        if (template == null) {
            return new Decision(false, "no_template");
        }
        return switch (template.trust()) {
            case DISABLED -> new Decision(false, "template_disabled");
            case TRUSTED -> new Decision(true, "ok");
            case SUPERVISED -> Boolean.TRUE.equals(confirmed.get(templateId))
                    ? new Decision(true, "ok")
                    : new Decision(false, "confirmation_required");
        };
    }

    /** Grants exactly one supervised run. Trust does not need confirming. */
    public synchronized Mutation confirm(String templateId) {
        PlanTemplate template = templates.get(templateId);
        if (template == null) {
            return Mutation.fail("no_template", "no template with that id");
        }
        if (template.trust() != TrustLevel.SUPERVISED) {
            return Mutation.fail("not_supervised", "only supervised runs need confirmation");
        }
        confirmed.put(templateId, true);
        return Mutation.ok("one run confirmed for " + templateId);
    }

    /** Consumes the confirmation when the run actually starts. */
    public synchronized Mutation beginRun(String templateId) {
        Decision decision = executionDecision(templateId);
        if (!decision.allowed()) {
            return Mutation.fail(decision.code(), decision.code());
        }
        confirmed.remove(templateId);
        return Mutation.ok("run allowed");
    }

    public synchronized Mutation recordOutcome(String templateId, boolean ok, long now) {
        PlanTemplate template = templates.get(templateId);
        if (template == null) {
            return Mutation.fail("no_template", "no template with that id");
        }
        if (ok) {
            int supervised = template.supervisedRuns();
            TrustLevel level = template.trust();
            if (level == TrustLevel.SUPERVISED) {
                supervised++;
                if (supervised >= SUPERVISED_RUNS_TO_TRUST) {
                    level = TrustLevel.TRUSTED;
                }
            }
            templates.put(templateId, template.with(level, supervised, 0,
                    template.successes() + 1, template.failures(), now));
            return Mutation.ok("success; trust=" + level.id());
        }
        int streak = template.consecutiveFailures() + 1;
        TrustLevel level = template.trust();
        int supervised = template.supervisedRuns();
        if (streak >= FAILURES_TO_DEMOTE) {
            level = TrustLevel.SUPERVISED;
            supervised = 0;
            streak = 0;
        }
        templates.put(templateId, template.with(level, supervised, streak,
                template.successes(), template.failures() + 1, now));
        return Mutation.ok("failure " + streak + "; trust=" + level.id());
    }

    public synchronized void clear() {
        proposals.clear();
        templates.clear();
        confirmed.clear();
    }

    /** Replaces the whole state, used when the JSON file is loaded. */
    public synchronized void replace(List<TemplateProposal> loadedProposals,
            List<PlanTemplate> loadedTemplates) {
        clear();
        for (TemplateProposal proposal : loadedProposals) {
            proposals.put(proposal.id(), proposal);
        }
        for (PlanTemplate template : loadedTemplates) {
            templates.put(template.id(), template);
        }
    }

    private static TemplateProposal withStatus(TemplateProposal proposal,
            TemplateProposal.Status status) {
        return new TemplateProposal(proposal.id(), proposal.name(), proposal.goal(),
                proposal.description(), proposal.steps(), proposal.sourcePlanId(),
                proposal.createdAt(), status);
    }

    /** Bounded list for the review UI: pending proposals first, then templates. */
    public synchronized List<String> viewLines(int limit) {
        List<String> lines = new ArrayList<>();
        proposals.values().stream()
                .filter(row -> row.status() == TemplateProposal.Status.PENDING)
                .forEach(row -> lines.add("proposal " + row.id() + "  " + row.name()
                        + "  " + row.steps().size() + " steps"));
        templates.values().forEach(row -> lines.add("template " + row.id() + "  " + row.name()
                + "  " + row.trust().id() + "  supervised=" + row.supervisedRuns()
                + "  streak=" + row.consecutiveFailures()));
        return lines.size() > limit ? List.copyOf(lines.subList(0, limit)) : List.copyOf(lines);
    }
}
