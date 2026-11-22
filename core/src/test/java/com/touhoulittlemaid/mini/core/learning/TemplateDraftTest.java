package com.touhoulittlemaid.mini.core.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanJson;

class TemplateDraftTest {

    private static final String PLAN = """
            {"version":1,"goal":"收菜入库","steps":[
              {"module":"harvest_area","params":{"radius":4,"replant":true}},
              {"module":"deposit_items","params":{"item":"minecraft:wheat","count":16}}]}
            """;

    @Test
    void variableParametersBecomePlaceholdersWithDefaults() {
        TemplateDraft draft = TemplateDraft.fromPlanJson(PLAN, "plan-1");
        assertNotNull(draft);
        assertEquals("收菜入库", draft.goal());
        assertEquals(2, draft.steps().size());
        assertEquals("${item}", draft.steps().get(1).params().get("item"));
        assertEquals("minecraft:wheat", draft.steps().get(1).defaults().get("item"));
        assertEquals(4.0, draft.steps().get(0).params().get("radius"));
    }

    @Test
    void materialisedPlansStillPassTheNormalGate() {
        TemplateDraft draft = TemplateDraft.fromPlanJson(PLAN, "plan-1");
        TemplateCatalog catalog = new TemplateCatalog();
        String message = catalog.propose(draft, "plan-1", 1L).message();
        String proposalId = message.replace("proposed ", "").split(" ")[0];
        String templateId = catalog.adopt(proposalId, 2L).message()
                .replace("adopted ", "").split(" ")[0];
        PlanTemplate template = catalog.template(templateId).orElseThrow();
        String json = TemplateDraft.planJson(template, Map.of("item", "minecraft:carrot"));
        assertTrue(json.contains("minecraft:carrot"), json);
        assertTrue(json.contains("template:" + templateId));
        try {
            assertEquals(2, PlanJson.parse(json).plan().stepCount());
        } catch (PlanFormatException exception) {
            throw new AssertionError(exception.code() + " " + exception.getMessage());
        }
    }

    @Test
    void trivialPlansAreNotWorthLearning() {
        assertNull(TemplateDraft.fromPlanJson(
                "{\"version\":1,\"steps\":[{\"module\":\"say\",\"params\":{}}]}", "p"));
        assertNull(TemplateDraft.fromPlanJson(
                "{\"version\":1,\"steps\":[{\"module\":\"say\",\"params\":{}},"
                        + "{\"module\":\"say\",\"params\":{}}]}", "p"));
        assertNull(TemplateDraft.fromPlanJson("not json", "p"));
    }

    @Test
    void placeholdersFallBackToTheCapturedDefault() {
        TemplateStep step = new TemplateStep("deposit_items",
                Map.of("item", "${item}", "count", 8.0),
                Map.of("item", "minecraft:wheat"));
        assertEquals("minecraft:wheat", step.materialise(Map.of()).get("item"));
        assertEquals("minecraft:carrot",
                step.materialise(Map.of("item", "minecraft:carrot")).get("item"));
        assertEquals(8.0, step.materialise(Map.of()).get("count"));
    }
}
