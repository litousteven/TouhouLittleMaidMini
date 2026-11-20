package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.config.CompanionConfigs;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;
import com.touhoulittlemaid.mini.fabric.runtime.CompanionRuntimeState;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** M3 disconnection degradation. */
public final class M3DisconnectGameTests {

    @GameTest(maxTicks = 160)
    public void disconnectGraceStopsBodyAndSwitchesToSafeMode(GameTestHelper helper) {
        CompanionEntity companion = helper.spawn(FabricContent.COMPANION, 2, 2, 2);
        companion.setBehavior(LocalBehavior.FOLLOW);
        companion.getNavigation().moveTo(2.5D, 2.0D, 12.5D, 1.0D);

        var original = CompanionConfigs.current();
        CompanionConfigs.override(original.withDisconnectGraceSeconds(0));
        CompanionRuntimeState.presence().recordWrite(companion.getUUID());

        helper.runAfterDelay(60, () -> {
            try {
                int due = CompanionRuntimeState.presence().dueForDisconnect(0L).size();
                com.touhoulittlemaid.mini.fabric.runtime.AgentWatchdog
                        .tick(helper.getLevel().getServer());
                if (companion.behavior() != LocalBehavior.STAY) {
                    throw helper.assertionException(
                            "disconnect must switch to the safe stay mode, got "
                                    + companion.behavior() + " due=" + due
                                    + " grace=" + CompanionConfigs.current()
                                    .disconnectGraceSeconds());
                }
                CompanionConfigs.override(original);
                CompanionRuntimeState.presence().clear();
                helper.succeed();
            } catch (RuntimeException exception) {
                CompanionConfigs.override(original);
                CompanionRuntimeState.presence().clear();
                throw exception;
            }
        });
    }
}
