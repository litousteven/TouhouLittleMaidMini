package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionEvent;
import com.touhoulittlemaid.mini.core.plan.FailureCodes;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** M6.0: the module framework runs a real two-step plan and refuses invalid ones. */
public final class M6PlanGameTests {

    private static ParsedPlan parse(GameTestHelper helper, String json) {
        try {
            return PlanJson.parse(json);
        } catch (PlanFormatException exception) {
            throw helper.assertionException(
                    "plan json was rejected: " + exception.code() + " " + exception.getMessage());
        }
    }

    private static List<String> kinds(CompanionEntity companion) {
        return CompanionRuntimeState.eventBus()
                .after(companion.getUUID().toString(), 0, 200).stream()
                .map(CompanionEvent::kind)
                .toList();
    }

    @GameTest(maxTicks = 120)
    public void sayAndGotoPlanFinishesInOrder(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos start = companion.blockPosition();
        ParsedPlan plan = parse(helper, "{\"version\":1,\"goal\":\"m6 smoke\",\"steps\":["
                + "{\"module\":\"say\",\"params\":{\"text\":\"ready\"}},"
                + "{\"module\":\"goto\",\"params\":{\"x\":" + start.getX()
                + ",\"y\":" + start.getY() + ",\"z\":" + start.getZ() + ",\"range\":2}}]}");

        PlanRuntime.SubmitResult submitted =
                PlanRuntime.submit(companion, plan, PlanGate.Policy.command());
        if (!submitted.ok()) {
            throw helper.assertionException(
                    "plan rejected: " + submitted.code() + " " + submitted.message());
        }

        helper.succeedWhen(() -> {
            if (PlanRuntime.isRunning(companion.getUUID())) {
                throw helper.assertionException("plan is still running");
            }
            List<String> kinds = kinds(companion);
            List<String> expected = List.of("plan_started", "step_started", "step_finished",
                    "step_started", "step_finished", "plan_finished");
            if (!kinds.equals(expected)) {
                throw helper.assertionException("unexpected plan event stream: " + kinds);
            }
            String finished = CompanionRuntimeState.eventBus()
                    .after(companion.getUUID().toString(), 0, 200).stream()
                    .filter(event -> event.kind().equals("plan_finished"))
                    .findFirst().map(CompanionEvent::data).orElse("");
            if (!finished.contains("\"ok\":true")) {
                throw helper.assertionException("plan did not finish ok: " + finished);
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void unknownModuleIsRejectedBeforeStarting(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        ParsedPlan plan = parse(helper,
                "{\"version\":1,\"steps\":[{\"module\":\"shell_exec\"}]}");
        PlanRuntime.SubmitResult submitted =
                PlanRuntime.submit(companion, plan, PlanGate.Policy.command());

        if (submitted.ok()) {
            PlanRuntime.cancel(companion.getUUID(), "test cleanup");
            throw helper.assertionException("unknown module must be rejected");
        }
        if (!FailureCodes.UNKNOWN_MODULE.equals(submitted.code())) {
            throw helper.assertionException("expected unknown_module, got " + submitted.code());
        }
        if (PlanRuntime.isRunning(companion.getUUID())) {
            throw helper.assertionException("a rejected plan must not leave a session");
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 60)
    public void overBudgetPlanIsRejected(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos start = companion.blockPosition();
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < 24; index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append("{\"module\":\"goto\",\"params\":{\"x\":").append(start.getX())
                    .append(",\"y\":").append(start.getY())
                    .append(",\"z\":").append(start.getZ())
                    .append(",\"timeout_seconds\":60}}");
        }
        ParsedPlan plan = parse(helper,
                "{\"version\":1,\"steps\":[" + steps + "]}");
        PlanRuntime.SubmitResult submitted =
                PlanRuntime.submit(companion, plan, PlanGate.Policy.command());

        if (submitted.ok()) {
            PlanRuntime.cancel(companion.getUUID(), "test cleanup");
            throw helper.assertionException("over-budget plan must be rejected");
        }
        if (!FailureCodes.BUDGET_EXCEEDED.equals(submitted.code())) {
            throw helper.assertionException("expected budget_exceeded, got " + submitted.code());
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 80)
    public void gotoKeepsTickingWithoutBlocking(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos start = companion.blockPosition();
        ParsedPlan plan = parse(helper, "{\"version\":1,\"steps\":["
                + "{\"module\":\"goto\",\"params\":{\"x\":" + (start.getX() + 3)
                + ",\"y\":" + start.getY() + ",\"z\":" + start.getZ()
                + ",\"range\":0.4,\"timeout_seconds\":20}}]}");

        PlanRuntime.SubmitResult submitted =
                PlanRuntime.submit(companion, plan, PlanGate.Policy.command());
        if (!submitted.ok()) {
            throw helper.assertionException("plan rejected: " + submitted.code());
        }

        helper.runAfterDelay(2, () -> {
            boolean running = PlanRuntime.isRunning(companion.getUUID());
            PlanRuntime.cancel(companion.getUUID(), "test cleanup");
            if (!running) {
                throw helper.assertionException(
                        "a three block walk must not finish inside two ticks; the step "
                                + "completed synchronously and would have blocked the tick");
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 80)
    public void secondPlanIsRejectedWhileOneIsRunning(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        BlockPos start = companion.blockPosition();
        ParsedPlan first = parse(helper, "{\"version\":1,\"steps\":["
                + "{\"module\":\"goto\",\"params\":{\"x\":" + (start.getX() + 3)
                + ",\"y\":" + start.getY() + ",\"z\":" + start.getZ()
                + ",\"range\":0.4,\"timeout_seconds\":20}}]}");
        ParsedPlan second = parse(helper,
                "{\"version\":1,\"steps\":[{\"module\":\"say\",\"params\":{\"text\":\"hi\"}}]}");

        if (!PlanRuntime.submit(companion, first, PlanGate.Policy.command()).ok()) {
            throw helper.assertionException("first plan should start");
        }
        PlanRuntime.SubmitResult busy =
                PlanRuntime.submit(companion, second, PlanGate.Policy.command());
        PlanRuntime.cancel(companion.getUUID(), "test cleanup");

        if (busy.ok()) {
            throw helper.assertionException("a second concurrent plan must be refused");
        }
        if (!FailureCodes.BUSY.equals(busy.code())) {
            throw helper.assertionException("expected busy, got " + busy.code());
        }
        helper.succeed();
    }
}
