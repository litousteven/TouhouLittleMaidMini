package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.learning.PlanTemplate;
import com.touhoulittlemaid.mini.core.learning.TemplateCatalog;
import com.touhoulittlemaid.mini.core.learning.TemplateDraft;
import com.touhoulittlemaid.mini.core.learning.TemplateProposal;
import com.touhoulittlemaid.mini.core.learning.TemplateStep;
import com.touhoulittlemaid.mini.core.learning.TrustLevel;
import com.touhoulittlemaid.mini.core.plan.PlanRunner;
import com.touhoulittlemaid.mini.core.skill.SkillIndex;
import com.touhoulittlemaid.mini.fabric.agent.AgentObserver;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.learning.TemplateService;
import com.touhoulittlemaid.mini.fabric.skill.SkillService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * M6.6 acceptance: learning produces reviewable data, trust is earned and lost, and Skill
 * disclosure cannot escape its own directory.
 *
 * <p>The catalog is a process-level singleton and GameTests in a batch are interleaved. No
 * test resets it; every assertion is relative to a snapshot taken at the start, and each
 * test uses its own goal so deduplication cannot couple them.
 */
public final class M6LearningGameTests {

    /** A per-JVM suffix keeps signatures unique even when a stale file is loaded. */
    private static final String RUN = Long.toString(System.nanoTime());

    private static String twoStepPlan() {
        return """
                {"version":1,"goal":"收菜入库-%s","steps":[
                  {"module":"harvest_area","params":{"radius":4}},
                  {"module":"deposit_items","params":{"item":"minecraft:wheat"}}]}
                """.formatted(RUN);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static PlanRunner.Summary summary(boolean ok) {
        return new PlanRunner.Summary(ok ? PlanRunner.State.FINISHED
                : PlanRunner.State.FAILED, ok, ok ? "ok" : "failed", "", 2, 0,
                ok ? 0 : 1, 0, 1, List.of(), Map.of());
    }

    private static TemplateDraft draft(String goal, String label) {
        return new TemplateDraft(label, goal + "-" + RUN, "two say steps",
                List.of(TemplateStep.of("say", Map.of("text", "one")),
                        TemplateStep.of("say", Map.of("text", "two"))));
    }

    private static String adopt(TemplateCatalog catalog, String goal, String label) {
        int before = catalog.templates().size();
        TemplateCatalog.Mutation proposed = catalog.propose(draft(goal, label), "plan-test",
                now());
        if (!proposed.ok()) {
            throw new AssertionError(proposed.code());
        }
        String proposalId = catalog.proposals().get(catalog.proposals().size() - 1).id();
        TemplateCatalog.Mutation adopted = catalog.adopt(proposalId, now());
        if (!adopted.ok()) {
            throw new AssertionError(adopted.code());
        }
        if (catalog.templates().size() != before + 1) {
            throw new AssertionError("adoption did not create exactly one template");
        }
        return catalog.templates().get(catalog.templates().size() - 1).id();
    }

    @GameTest(maxTicks = 40)
    public void aSuccessfulPlanOnlyCreatesAPendingProposal(GameTestHelper helper) {
        TemplateCatalog catalog = TemplateService.catalog();
        long pendingBefore = catalog.pendingCount();
        int templatesBefore = catalog.templates().size();
        TemplateService.remember("plan-m66-success", twoStepPlan());
        TemplateService.onPlanFinished(UUID.randomUUID(), "plan-m66-success", summary(true));

        if (catalog.pendingCount() != pendingBefore + 1
                || catalog.templates().size() != templatesBefore) {
            throw helper.assertionException("a success must add one proposal and no template");
        }
        String proposalId = catalog.proposals().get(catalog.proposals().size() - 1).id();
        if (catalog.proposal(proposalId).orElseThrow().status()
                != TemplateProposal.Status.PENDING) {
            throw helper.assertionException("the proposal is not awaiting review");
        }
        if (catalog.executionDecision(proposalId).allowed()) {
            throw helper.assertionException("unreviewed data must never be executable");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aFailedPlanCreatesNoProposal(GameTestHelper helper) {
        TemplateCatalog catalog = TemplateService.catalog();
        long pendingBefore = catalog.pendingCount();
        TemplateService.remember("plan-m66-failure", twoStepPlan());
        TemplateService.onPlanFinished(UUID.randomUUID(), "plan-m66-failure", summary(false));
        if (catalog.pendingCount() != pendingBefore) {
            throw helper.assertionException("a failed plan must not be learned");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void aSupervisedRunNeedsAConfirmationEachTime(GameTestHelper helper) {
        TemplateCatalog catalog = TemplateService.catalog();
        String id = adopt(catalog, "goal-confirm", "say-confirm");
        if (catalog.executionDecision(id).allowed()
                || !"confirmation_required".equals(catalog.executionDecision(id).code())) {
            throw helper.assertionException("a supervised template ran without confirmation");
        }
        TemplateService.confirm(id);
        if (!catalog.executionDecision(id).allowed()) {
            throw helper.assertionException("confirm did not authorise one run");
        }
        catalog.beginRun(id);
        if (catalog.executionDecision(id).allowed()) {
            throw helper.assertionException("one confirmation authorised two runs");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 120)
    public void aRealRunFeedsTheListenerAndTrustCanBeLost(GameTestHelper helper) {
        TemplateCatalog catalog = TemplateService.catalog();
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        String id = adopt(catalog, "goal-real-run", "say-real");

        TemplateService.confirm(id);
        TemplateCatalog.Mutation started = TemplateService.run(companion, id);
        if (!started.ok()) {
            throw helper.assertionException("the confirmed run did not start: "
                    + started.code() + " " + started.message());
        }
        helper.succeedWhen(() -> {
            PlanTemplate afterOne = catalog.template(id).orElseThrow();
            if (afterOne.successes() != 1) {
                throw helper.assertionException("the completion listener did not record the run");
            }
            if (afterOne.trust() != TrustLevel.SUPERVISED) {
                throw helper.assertionException("one success must not earn trust");
            }
            // The remaining transitions are the same deterministic state machine exercised
            // by the core unit tests; keeping them here makes the acceptance self-contained.
            catalog.confirm(id);
            catalog.beginRun(id);
            catalog.recordOutcome(id, true, now());
            if (catalog.template(id).orElseThrow().trust() != TrustLevel.TRUSTED) {
                throw helper.assertionException("two successes should earn trust");
            }
            catalog.recordOutcome(id, false, now());
            catalog.recordOutcome(id, false, now());
            catalog.recordOutcome(id, false, now());
            if (catalog.template(id).orElseThrow().trust() != TrustLevel.SUPERVISED) {
                throw helper.assertionException("three failures should demote trust");
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void rejectAndRollbackAreReversible(GameTestHelper helper) {
        TemplateCatalog catalog = TemplateService.catalog();
        catalog.propose(draft("goal-reject", "reject-me"), "plan-x", now());
        String proposalId = catalog.proposals().get(catalog.proposals().size() - 1).id();
        if (!TemplateService.reject(proposalId).ok()
                || catalog.proposal(proposalId).orElseThrow().status()
                        != TemplateProposal.Status.REJECTED) {
            throw helper.assertionException("a rejected proposal stayed pending");
        }
        String id = adopt(catalog, "goal-rollback", "rollback-me");
        String sourceProposal = catalog.template(id).orElseThrow().sourceProposalId();
        if (!TemplateService.rollback(id).ok()) {
            throw helper.assertionException("rollback was refused");
        }
        if (catalog.template(id).orElseThrow().trust() != TrustLevel.DISABLED
                || catalog.executionDecision(id).allowed()) {
            throw helper.assertionException("a rolled back template is still runnable");
        }
        if (catalog.proposal(sourceProposal).orElseThrow().status()
                != TemplateProposal.Status.PENDING) {
            throw helper.assertionException("rollback did not reopen the proposal");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void skillsAreDisclosedInThreeBoundedLevels(GameTestHelper helper) {
        Path root = FabricLoader.getInstance().getGameDir()
                .resolve("config/tlmm/skill-gametest");
        SkillService.load(root);
        try {
            Path attachment = root.resolve("builtin/harvest-and-store/notes.txt");
            Files.createDirectories(attachment.getParent());
            Files.writeString(attachment, "extra detail", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw helper.assertionException("could not set up skill files: "
                    + exception.getMessage());
        }

        SkillIndex index = SkillService.index();
        if (index.entries().isEmpty()) {
            throw helper.assertionException("the level-1 index is empty");
        }
        String status = AgentObserver.observe(
                helper.spawn(FabricContent.COMPANION, 2, 2, 2), "status", index.entries());
        if (!status.contains("\"skills\"") || status.contains("harvest_area")) {
            throw helper.assertionException("the index leaked a body: " + status);
        }
        if (!SkillService.observe("skill:harvest-and-store").contains("harvest_area")) {
            throw helper.assertionException("level 2 did not return the body");
        }
        if (!SkillService.observe("skill_file:harvest-and-store:notes.txt")
                .contains("extra detail")) {
            throw helper.assertionException("level 3 did not return the attachment");
        }
        String escape = SkillService.observe(
                "skill_file:harvest-and-store:../secret.txt");
        if (!escape.contains("invalid_path") || escape.contains("secret")) {
            throw helper.assertionException("a skill escaped its directory: " + escape);
        }
        helper.succeed();
    }
}
