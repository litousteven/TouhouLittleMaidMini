package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.CompanionEvent;
import com.touhoulittlemaid.mini.core.ServerMetrics;
import com.touhoulittlemaid.mini.core.plan.ParsedPlan;
import com.touhoulittlemaid.mini.core.plan.PlanFormatException;
import com.touhoulittlemaid.mini.core.plan.PlanGate;
import com.touhoulittlemaid.mini.core.plan.PlanJson;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.plan.PlanRuntime;
import com.touhoulittlemaid.mini.fabric.plan.TidyModule;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;
import com.touhoulittlemaid.mini.fabric.runtime.ServerDiagnostics;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * M6.7: the two deterministic performance checks from `docs/02-development-plan.md`.
 *
 * <p>Four concurrent plans prove the runtime holds several sessions at once; the 20-container
 * tidy probe measures the only unbounded part of a tidy step. Long-run MSPT deltas and leak
 * trends belong to the 24-hour soak on a dedicated server, not to a 3-second GameTest batch —
 * this class records the metrics but does not pretend to have measured a 24-hour trend.
 */
public final class M6ReleaseGameTests {

    private static final int CONCURRENT_PLANS = 4;
    private static final int CHEST_COUNT = 20;
    private static final long TIDY_NANOS_BUDGET = 5_000_000L;

    private static ParsedPlan sayPlan(GameTestHelper helper) {
        try {
            return PlanJson.parse("{\"version\":1,\"steps\":["
                    + "{\"module\":\"say\",\"params\":{\"text\":\"one\"}},"
                    + "{\"module\":\"say\",\"params\":{\"text\":\"two\"}}]}");
        } catch (PlanFormatException exception) {
            throw helper.assertionException("plan rejected: " + exception.code());
        }
    }

    @GameTest(maxTicks = 120)
    public void fourConcurrentPlansFinishWithinTheTickBudget(GameTestHelper helper) {
        ParsedPlan plan = sayPlan(helper);
        List<CompanionEntity> companions = new ArrayList<>();
        for (int index = 0; index < CONCURRENT_PLANS; index++) {
            CompanionEntity companion = helper.spawn(FabricContent.COMPANION,
                    2 + index, 2, 2 + index % 2);
            companions.add(companion);
            if (!PlanRuntime.submit(companion, plan, PlanGate.Policy.commandConfirmed(),
                    PlanRuntime.SOURCE_COMMAND).ok()) {
                throw helper.assertionException("concurrent plan " + index + " was refused");
            }
        }
        if (PlanRuntime.activeCount() < CONCURRENT_PLANS) {
            throw helper.assertionException("the runtime is not holding four sessions: "
                    + PlanRuntime.activeCount());
        }
        helper.succeedWhen(() -> {
            for (CompanionEntity companion : companions) {
                if (PlanRuntime.isRunning(companion.getUUID())) {
                    throw helper.assertionException("a concurrent plan never finished");
                }
                boolean finished = CompanionRuntimeState.eventBus()
                        .after(companion.getUUID().toString(), 0, 400).stream()
                        .filter(event -> event.kind().equals("plan_finished"))
                        .map(CompanionEvent::data)
                        .anyMatch(data -> data.contains("\"ok\":true"));
                if (!finished) {
                    throw helper.assertionException("a plan finished without an ok event");
                }
            }
            ServerMetrics metrics = ServerDiagnostics.snapshot(
                    helper.getLevel().getServer());
            TlmmFabric.LOGGER.info("[m6.7] concurrent={} mspt_avg={} mspt_max={} "
                            + "heap={}/{}MB threads={} events={}",
                    CONCURRENT_PLANS, metrics.msptAverage(), metrics.msptMax(),
                    metrics.heapUsedMb(), metrics.heapMaxMb(), metrics.threadCount(),
                    metrics.retainedEventCount());
            if (metrics.msptAverage() > 50.0D) {
                throw helper.assertionException("average MSPT exceeded the budget: "
                        + metrics.msptAverage());
            }
        });
    }

    @GameTest(maxTicks = 60)
    public void planningTwentyChestsStaysUnderFiveMilliseconds(GameTestHelper helper) {
        List<Container> containers = new ArrayList<>();
        for (int chest = 0; chest < CHEST_COUNT; chest++) {
            SimpleContainer container = new SimpleContainer(27);
            container.setItem(0, new ItemStack(Items.COBBLESTONE, 8 + chest));
            container.setItem(1, new ItemStack(Items.OAK_PLANKS, 4 + chest % 5));
            container.setItem(2, new ItemStack(Items.WHEAT_SEEDS, chest % 7));
            containers.add(container);
        }
        // Warm up class loading and JIT before timing; the first call is never representative.
        for (int index = 0; index < 3; index++) {
            TidyModule.planNanos(containers);
        }
        long worst = 0;
        for (int index = 0; index < 10; index++) {
            worst = Math.max(worst, TidyModule.planNanos(containers));
        }
        TlmmFabric.LOGGER.info("[m6.7] tidy planning containers={} worst={}us budget={}us",
                CHEST_COUNT, worst / 1_000L, TIDY_NANOS_BUDGET / 1_000L);
        if (worst > TIDY_NANOS_BUDGET) {
            throw helper.assertionException("tidy planning exceeded the 5ms budget: "
                    + worst / 1_000L + "us");
        }
        helper.succeed();
    }
}
