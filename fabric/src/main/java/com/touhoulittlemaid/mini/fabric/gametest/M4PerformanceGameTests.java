package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ServerMetrics;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.mcp.FabricCompanionBackend;
import com.touhoulittlemaid.mini.fabric.runtime.ServerDiagnostics;
import com.touhoulittlemaid.mini.mcp.CompanionTools;
import com.touhoulittlemaid.mini.mcp.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** M4 scale probe: eight idle companions and four companions driven by generic agent moves. */
public final class M4PerformanceGameTests {

    private static final int COMPANION_COUNT = 12;
    private static final int IDLE_COUNT = 8;
    private static final double AVERAGE_MSPT_BUDGET = 50.0D;
    private static final double MAX_MSPT_BUDGET = 250.0D;

    @GameTest(maxTicks = 400)
    public void twelveCompanionsStayWithinTheTickBudget(GameTestHelper helper) {
        List<CompanionEntity> companions = new ArrayList<>();
        for (int index = 0; index < COMPANION_COUNT; index++) {
            CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
            companion.setBehavior(index < IDLE_COUNT
                    ? LocalBehavior.STAY : LocalBehavior.FOLLOW);
            companion.setHome(helper.absolutePos(new BlockPos(2, 2, 2)));
            companions.add(companion);
        }
        List<CompanionEntity> active = companions.subList(IDLE_COUNT, COMPANION_COUNT);
        ToolRegistry registry = CompanionTools.registry(
                new FabricCompanionBackend(helper.getLevel().getServer()));

        helper.runAfterDelay(60, () -> {
            int firstWave = issueMoves(helper, registry, active, 1);
            helper.runAfterDelay(80, () -> {
                int secondWave = issueMoves(helper, registry, active, -1);
                helper.runAfterDelay(60, () -> finish(helper, companions,
                        firstWave + secondWave));
            });
        });
    }

    private static int issueMoves(GameTestHelper helper, ToolRegistry registry,
            List<CompanionEntity> active, int direction) {
        int accepted = 0;
        for (int index = 0; index < active.size(); index++) {
            CompanionEntity companion = active.get(index);
            if (!companion.isAlive()) {
                continue;
            }
            BlockPos target = helper.absolutePos(new BlockPos(
                    2 + direction * (2 + index % 3), 2, 2 + direction * (index / 2)));
            ToolResult result = registry.invoke("companion_move", Map.of(
                    "companion_id", companion.getUUID().toString(),
                    "x", target.getX(),
                    "y", target.getY(),
                    "z", target.getZ(),
                    "distance", 1.0D));
            if (result.status() == ToolResult.Status.OK) {
                accepted++;
            }
        }
        return accepted;
    }

    private static void finish(GameTestHelper helper, List<CompanionEntity> companions,
            int acceptedMoves) {
        for (CompanionEntity companion : companions) {
            if (!companion.isAlive()) {
                throw helper.assertionException("a companion did not survive the scale probe");
            }
        }
        ServerMetrics metrics = ServerDiagnostics.snapshot(helper.getLevel().getServer());
        TlmmFabric.LOGGER.info("[m4] scale probe spawned={} server_companions={} "
                        + "agent_moves={} mspt_avg={} mspt_max={} heap={}/{}MB threads={} events={}",
                companions.size(), metrics.companionCount(), acceptedMoves,
                metrics.msptAverage(), metrics.msptMax(), metrics.heapUsedMb(),
                metrics.heapMaxMb(), metrics.threadCount(), metrics.retainedEventCount());
        if (metrics.msptAverage() > AVERAGE_MSPT_BUDGET) {
            throw helper.assertionException("average MSPT exceeded the budget: "
                    + metrics.msptAverage());
        }
        if (metrics.msptMax() > MAX_MSPT_BUDGET) {
            throw helper.assertionException("single-tick MSPT exceeded the budget: "
                    + metrics.msptMax());
        }
        helper.succeed();
    }
}
