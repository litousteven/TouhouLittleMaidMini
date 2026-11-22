package com.touhoulittlemaid.mini.core.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class TemplateCatalogTest {

    private static final long NOW = 1_700_000_000_000L;

    private static TemplateDraft draft(String goal) {
        return new TemplateDraft("harvest-and-store", goal, "test",
                List.of(TemplateStep.of("harvest_area", Map.of("radius", 4.0)),
                        TemplateStep.of("deposit_items", Map.of("item", "${item}"))));
    }

    private static String adopt(TemplateCatalog catalog, String goal) {
        String proposalId = catalog.propose(draft(goal), "plan-1", NOW).message()
                .replace("proposed ", "").split(" ")[0];
        String templateId = catalog.adopt(proposalId, NOW).message()
                .replace("adopted ", "").split(" ")[0];
        return templateId;
    }

    @Test
    void aProposalCannotRunBeforeAdoption() {
        TemplateCatalog catalog = new TemplateCatalog();
        assertTrue(catalog.propose(draft("收菜"), "plan-1", NOW).ok());
        assertTrue(catalog.templates().isEmpty());
        assertEquals(1, catalog.pendingCount());
        assertFalse(catalog.executionDecision("anything").allowed());
    }

    @Test
    void twoSupervisedSuccessesEarnTrust() {
        TemplateCatalog catalog = new TemplateCatalog();
        String id = adopt(catalog, "收菜");
        assertEquals(TrustLevel.SUPERVISED, catalog.template(id).orElseThrow().trust());
        assertEquals("confirmation_required", catalog.executionDecision(id).code());

        // One confirmation buys exactly one run.
        assertTrue(catalog.confirm(id).ok());
        assertTrue(catalog.beginRun(id).ok());
        assertEquals("confirmation_required", catalog.executionDecision(id).code());
        assertTrue(catalog.recordOutcome(id, true, NOW).ok());

        assertTrue(catalog.confirm(id).ok());
        assertTrue(catalog.beginRun(id).ok());
        assertTrue(catalog.recordOutcome(id, true, NOW).ok());
        assertEquals(TrustLevel.TRUSTED, catalog.template(id).orElseThrow().trust());
        assertTrue(catalog.executionDecision(id).allowed(), "trusted runs need no confirmation");
    }

    @Test
    void threeConsecutiveFailuresDemoteTrust() {
        TemplateCatalog catalog = new TemplateCatalog();
        String id = adopt(catalog, "收菜");
        catalog.confirm(id);
        catalog.beginRun(id);
        catalog.recordOutcome(id, true, NOW);
        catalog.confirm(id);
        catalog.beginRun(id);
        catalog.recordOutcome(id, true, NOW);
        assertEquals(TrustLevel.TRUSTED, catalog.template(id).orElseThrow().trust());

        catalog.recordOutcome(id, false, NOW);
        catalog.recordOutcome(id, false, NOW);
        assertEquals(TrustLevel.TRUSTED, catalog.template(id).orElseThrow().trust());
        catalog.recordOutcome(id, false, NOW);
        PlanTemplate demoted = catalog.template(id).orElseThrow();
        assertEquals(TrustLevel.SUPERVISED, demoted.trust());
        assertEquals(0, demoted.supervisedRuns());
        assertEquals("confirmation_required", catalog.executionDecision(id).code());
    }

    @Test
    void proposalsCanBeRejectedAndAdoptionsRolledBack() {
        TemplateCatalog catalog = new TemplateCatalog();
        catalog.propose(draft("收菜"), "plan-1", NOW);
        String rejected = catalog.proposals().get(0).id();
        assertTrue(catalog.reject(rejected).ok());
        assertEquals(TemplateProposal.Status.REJECTED,
                catalog.proposal(rejected).orElseThrow().status());

        String templateId = adopt(catalog, "做饭");
        assertTrue(catalog.rollback(templateId, NOW).ok());
        assertEquals(TrustLevel.DISABLED,
                catalog.template(templateId).orElseThrow().trust());
        assertEquals("template_disabled", catalog.executionDecision(templateId).code());
        assertEquals(TemplateProposal.Status.PENDING,
                catalog.proposal(catalog.template(templateId).orElseThrow()
                        .sourceProposalId()).orElseThrow().status());
    }

    @Test
    void duplicateSequencesDoNotPileUp() {
        TemplateCatalog catalog = new TemplateCatalog();
        assertTrue(catalog.propose(draft("收菜"), "plan-1", NOW).ok());
        assertFalse(catalog.propose(draft("收菜"), "plan-2", NOW).ok());
        assertEquals("duplicate", catalog.propose(draft("收菜"), "plan-2", NOW).code());
    }

    @Test
    void theJsonRoundTripKeepsTrustAndSteps() {
        TemplateCatalog catalog = new TemplateCatalog();
        String id = adopt(catalog, "收菜");
        catalog.recordOutcome(id, true, NOW);
        String json = TemplateCatalogJson.encode(catalog);
        TemplateCatalogJson.Loaded loaded = TemplateCatalogJson.decode(json);
        assertTrue(loaded.problems().isEmpty(), loaded.problems().toString());
        assertEquals(catalog.templates().size(), loaded.catalog().templates().size());
        assertEquals(TrustLevel.SUPERVISED,
                loaded.catalog().template(id).orElseThrow().trust());
        assertEquals(1, loaded.catalog().template(id).orElseThrow().successes());
        assertEquals(2, loaded.catalog().template(id).orElseThrow().steps().size());
        assertTrue(TemplateCatalogJson.decode("not json").problems().contains("invalid_json"));
    }
}
