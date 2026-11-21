package com.touhoulittlemaid.mini.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The gate is the security boundary: every rejection must name a concrete reason. */
class PlanGateTest {

    private static ModuleRegistry registry() {
        return ModuleRegistry.builder()
                .register(TestModules.stub("probe", RiskClass.OBSERVE))
                .register(TestModules.stub("dig", RiskClass.DESTROY, 1, 20, 1))
                .register(TestModules.stub("heavy", RiskClass.MODIFY, 200, 20, 0))
                .build();
    }

    private static String plan(String steps) {
        return "{\"version\":1,\"goal\":\"test\",\"steps\":[" + steps + "]}";
    }

    private static PlanGate gate(PlanGate.Policy policy) {
        return new PlanGate(registry(), policy);
    }

    private static PlanGate.Result check(PlanGate.Policy policy, String json) throws Exception {
        return gate(policy).check(PlanJson.parse(json));
    }

    @Test
    void acceptsTwoStepPlan() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"probe\",\"params\":{\"tier\":1}},"
                        + "{\"module\":\"probe\",\"bind\":\"second\"}"));
        assertTrue(result.ok(), result.message());
    }

    @Test
    void rejectsUnknownModuleWithKnownList() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"shell_exec\"}"));
        assertFalse(result.ok());
        assertEquals(FailureCodes.UNKNOWN_MODULE, result.code());
        assertTrue(result.message().contains("shell_exec"));
        assertTrue(result.message().contains("probe"));
    }

    @Test
    void rejectsWrongParameterType() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"probe\",\"params\":{\"tier\":\"high\"}}"));
        assertEquals(FailureCodes.INVALID_PARAMS, result.code());
        assertTrue(result.message().contains("tier"));
    }

    @Test
    void rejectsOutOfRangeParameterAndUnknownParameter() throws Exception {
        assertEquals(FailureCodes.INVALID_PARAMS, check(PlanGate.Policy.command(),
                plan("{\"module\":\"probe\",\"params\":{\"tier\":9}}")).code());
        PlanGate.Result unknown = check(PlanGate.Policy.command(),
                plan("{\"module\":\"probe\",\"params\":{\"hidden\":1}}"));
        assertEquals(FailureCodes.INVALID_PARAMS, unknown.code());
        assertTrue(unknown.message().contains("hidden"));
    }

    @Test
    void rejectsActionBudgetOverflow() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"heavy\"},{\"module\":\"heavy\"},{\"module\":\"heavy\"}"));
        assertEquals(FailureCodes.BUDGET_EXCEEDED, result.code());
        assertTrue(result.message().contains("600"));
    }

    @Test
    void repeatMultipliesTheBudget() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"heavy\",\"repeat\":{\"max\":3}}"));
        assertEquals(FailureCodes.BUDGET_EXCEEDED, result.code());
    }

    @Test
    void rejectsDestroyInChoresAndRequiresConfirmationInCommandMode() throws Exception {
        assertEquals(FailureCodes.RISK_DENIED, check(PlanGate.Policy.chores(),
                plan("{\"module\":\"dig\"}")).code());
        assertEquals(FailureCodes.CONFIRMATION_REQUIRED, check(PlanGate.Policy.command(),
                plan("{\"module\":\"dig\"}")).code());
        assertTrue(check(PlanGate.Policy.commandConfirmed(),
                plan("{\"module\":\"dig\"}")).ok());
    }

    @Test
    void rejectsUnknownPredicate() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"probe\",\"if\":\"java.lang.Runtime.getRuntime()\"}"));
        assertEquals(FailureCodes.UNKNOWN_PREDICATE, result.code());
    }

    @Test
    void rejectsReferenceToLaterBinding() throws Exception {
        PlanGate.Result result = check(PlanGate.Policy.command(),
                plan("{\"module\":\"probe\",\"bind\":\"a\"},"
                        + "{\"module\":\"probe\",\"params\":{\"label\":\"$b\"}},"
                        + "{\"module\":\"probe\",\"bind\":\"b\"}"));
        assertEquals(FailureCodes.INVALID_PARAMS, result.code());
        assertTrue(result.message().contains("$b"));
    }

    @Test
    void rejectsOversizedPayloadEvenWhenTheModelNeverParsedIt() {
        Plan plan = Plan.of("big", List.of(PlanStep.of("probe", ModuleParams.empty())));
        PlanGate.Result result = gate(PlanGate.Policy.command())
                .check(new ParsedPlan(plan, PlanLimits.MAX_PLAN_BYTES + 1));
        assertEquals(FailureCodes.PLAN_TOO_LARGE, result.code());
    }

    @Test
    void choresPolicyIsTighterThanCommand() throws Exception {
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < 14; index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append("{\"module\":\"probe\"}");
        }
        assertEquals(FailureCodes.TOO_MANY_STEPS, check(PlanGate.Policy.chores(),
                plan(steps.toString())).code());
    }
}
