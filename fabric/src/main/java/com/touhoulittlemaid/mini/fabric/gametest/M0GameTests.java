package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.entity.DebugCompanionEntity;
import com.touhoulittlemaid.mini.fabric.persistence.M0SavedData;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

public final class M0GameTests {

    @GameTest(maxTicks = 40)
    public void entityRegistrationSyncAndNbtRoundTrip(GameTestHelper helper) {
        DebugCompanionEntity entity = helper.spawn(FabricContent.DEBUG_COMPANION, 1, 1, 1);
        entity.setPhase(7);
        entity.setPersistedProbe(1234);

        if (entity.phase() != 7) {
            throw helper.assertionException("M0 synchronized entity data was not retained");
        }

        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        entity.saveWithoutId(output);
        CompoundTag encoded = output.buildResult();
        if (!encoded.contains("m0_persisted_probe")) {
            throw helper.assertionException("M0 persisted entity field was not written");
        }

        DebugCompanionEntity restored =
                new DebugCompanionEntity(FabricContent.DEBUG_COMPANION, helper.getLevel());
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING,
                helper.getLevel().registryAccess(), encoded));
        if (restored.persistedProbe() != 1234) {
            throw helper.assertionException("M0 persisted entity field did not survive NBT round trip");
        }

        entity.discard();
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void savedDataSurvivesStorageAccess(GameTestHelper helper) {
        M0SavedData savedData = M0SavedData.get(helper.getLevel().getServer());
        int before = savedData.value();
        savedData.increment();

        if (M0SavedData.get(helper.getLevel().getServer()).value() != before + 1) {
            throw helper.assertionException("M0 SavedData increment was not retained");
        }
        if (!savedData.isDirty()) {
            throw helper.assertionException("M0 SavedData must be dirty for the next save");
        }
        helper.succeed();
    }
}
