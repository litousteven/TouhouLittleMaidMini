package com.touhoulittlemaid.mini.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Runner semantics: ordering, async ticking, failure policy, bindings and cancellation. */
class PlanRunnerTest {

    private final ModuleRegistry registry = ModuleRegistry.builder()
            .register(TestModules.stub("probe", RiskClass.OBSERVE))
            .build();

    private static Plan plan(String steps) throws Exception {
        return PlanJson.parse("{\"version\":1,\"goal\":\"g\",\"steps\":[" + steps + "]}").plan();
    }

    private PlanRunner runner(Plan plan) {
        return runner(plan, new TestModules.FakePredicates(), new RecordingListener());
    }

    private PlanRunner runner(Plan plan, TestModules.FakePredicates predicates,
            PlanListener listener) {
        return new PlanRunner(plan, registry, TestModules.CONTEXT, predicates, listener);
    }

    /** Runs ticks until the plan stops, with a hard cap so a bug cannot hang the suite. */
    private static void drain(PlanRunner runner, int maxTicks) {
        int guard = 0;
        while (runner.state() == PlanRunner.State.RUNNING && guard++ < maxTicks) {
            runner.tick();
        }
    }

    private TestModules.StubModule module() {
        return (TestModules.StubModule) registry.require("probe");
    }

    @Test
    void runsStepsInOrderAndEmitsLifecycleEvents() throws Exception {
        RecordingListener listener = new RecordingListener();
        PlanRunner runner = runner(plan("{\"module\":\"probe\"},{\"module\":\"probe\"}"),
                new TestModules.FakePredicates(), listener);
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(List.of(
                "plan_started",
                "step_started:0:probe",
                "step_finished:0:probe:ok",
                "step_started:1:probe",
                "step_finished:1:probe:ok",
                "plan_finished:true"), listener.events);
        assertEquals(2, module().attempts);
    }

    @Test
    void advancesOneTickAtATimeForAsyncModules() throws Exception {
        module().ticksPerAttempt = 3;
        PlanRunner runner = runner(plan("{\"module\":\"probe\"}"));
        runner.start();
        assertEquals(PlanRunner.State.RUNNING, runner.state());
        runner.tick();
        assertEquals(PlanRunner.State.RUNNING, runner.state());
        runner.tick();
        assertEquals(PlanRunner.State.RUNNING, runner.state());
        runner.tick();
        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(3, runner.ticks());
    }

    @Test
    void abortsOnFailureByDefault() throws Exception {
        module().script(ModuleResult.failed(FailureCodes.BLOCKED, "no path"));
        PlanRunner runner = runner(plan("{\"module\":\"probe\"},{\"module\":\"probe\"}"));
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FAILED, runner.state());
        assertEquals(FailureCodes.BLOCKED, runner.summary().code());
        assertTrue(runner.summary().message().contains("no path"));
        assertEquals(1, module().attempts, "the second step must not run");
        assertEquals(1, runner.summary().stepsFailed());
    }

    @Test
    void continuesAfterFailureWhenDeclared() throws Exception {
        module().script(ModuleResult.failed(FailureCodes.BLOCKED, "no path"));
        PlanRunner runner = runner(plan(
                "{\"module\":\"probe\",\"on_failure\":\"continue\"},{\"module\":\"probe\"}"));
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(2, module().attempts);
        assertEquals(1, runner.summary().stepsFailed());
        assertEquals(1, runner.summary().stepsCompleted());
    }

    @Test
    void retriesThenSucceeds() throws Exception {
        module().script(ModuleResult.failed(FailureCodes.BUSY, "busy"),
                ModuleResult.ok("done", "done"));
        PlanRunner runner = runner(plan("{\"module\":\"probe\",\"on_failure\":\"retry:1\"}"));
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(2, module().attempts);
    }

    @Test
    void retryExhaustionFailsWithTheLastCode() throws Exception {
        module().script(ModuleResult.failed(FailureCodes.BUSY, "busy"),
                ModuleResult.failed(FailureCodes.BUSY, "busy"),
                ModuleResult.failed(FailureCodes.BUSY, "busy"));
        PlanRunner runner = runner(plan("{\"module\":\"probe\",\"on_failure\":\"retry:2\"}"));
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FAILED, runner.state());
        assertEquals(3, module().attempts, "retry:2 means three attempts in total");
    }

    @Test
    void bindsOutputForLaterSteps() throws Exception {
        module().script(ModuleResult.ok("found", "found", Map.of("label", "crops")));
        RecordingListener listener = new RecordingListener();
        PlanRunner runner = runner(plan(
                "{\"module\":\"probe\",\"bind\":\"first\"},"
                        + "{\"module\":\"probe\",\"params\":{\"label\":\"$first\"}}"),
                new TestModules.FakePredicates(), listener);
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(Map.of("label", "crops"), runner.bindings().get("first"));
        assertEquals("crops", module().started.get(1).string("label", ""));
    }

    @Test
    void skipsStepWhenConditionIsFalse() throws Exception {
        TestModules.FakePredicates predicates = new TestModules.FakePredicates();
        predicates.answers.put("day", false);
        PlanRunner runner = runner(plan(
                "{\"module\":\"probe\",\"if\":\"time.is_day\"},{\"module\":\"probe\"}"),
                predicates, new RecordingListener());
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(1, module().attempts);
        assertEquals(1, runner.summary().stepsSkipped());
    }

    @Test
    void repeatsUpToDeclaredMaximum() throws Exception {
        PlanRunner runner = runner(plan("{\"module\":\"probe\",\"repeat\":{\"max\":3}}"));
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertEquals(3, module().attempts);
        // Repetition is one plan step, not three.
        assertEquals(1, runner.summary().stepsCompleted());
    }

    @Test
    void cancellingStopsFurtherSteps() throws Exception {
        module().ticksPerAttempt = 50;
        PlanRunner runner = runner(plan("{\"module\":\"probe\"},{\"module\":\"probe\"}"));
        runner.start();
        runner.tick();
        runner.cancel("owner asked to stop");

        assertEquals(PlanRunner.State.CANCELLED, runner.state());
        assertEquals(FailureCodes.CANCELLED, runner.summary().code());
        runner.tick();
        assertEquals(PlanRunner.State.CANCELLED, runner.state());
        assertEquals(1, module().attempts);
    }

    @Test
    void failedPreconditionNeverStartsTheModule() throws Exception {
        module().checkFails(FailureCodes.TOOL_NOT_IN_INVENTORY);
        PlanRunner runner = runner(plan("{\"module\":\"probe\"}"));
        runner.start();
        drain(runner, 20);

        assertEquals(PlanRunner.State.FAILED, runner.state());
        assertEquals(FailureCodes.TOOL_NOT_IN_INVENTORY, runner.summary().code());
        assertEquals(1, module().checkCalls);
        assertEquals(0, module().attempts);
    }

    @Test
    void emptyPlanFinishesImmediately() {
        PlanRunner runner = new PlanRunner(Plan.of("nothing", List.of()), registry,
                TestModules.CONTEXT, new TestModules.FakePredicates(),
                new RecordingListener());
        runner.start();
        assertEquals(PlanRunner.State.FINISHED, runner.state());
        assertTrue(runner.summary().ok());
    }

    static final class RecordingListener implements PlanListener {

        final List<String> events = new ArrayList<>();

        @Override
        public void planStarted(Plan plan) {
            events.add("plan_started");
        }

        @Override
        public void stepStarted(int index, String module) {
            events.add("step_started:" + index + ":" + module);
        }

        @Override
        public void stepFinished(int index, String module, ModuleResult result, boolean skipped) {
            events.add("step_finished:" + index + ":" + module + ":"
                    + (skipped ? "skipped" : result.code()));
        }

        @Override
        public void planFinished(PlanRunner.Summary summary) {
            events.add("plan_finished:" + summary.ok());
        }
    }
}
