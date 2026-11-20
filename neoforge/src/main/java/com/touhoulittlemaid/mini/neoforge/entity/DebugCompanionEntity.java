package com.touhoulittlemaid.mini.neoforge.entity;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * M0-only entity proving registration, persistence and synchronized state.
 */
public final class DebugCompanionEntity extends PathfinderMob {

    private static final EntityDataAccessor<Integer> DATA_PHASE =
            SynchedEntityData.defineId(DebugCompanionEntity.class, EntityDataSerializers.INT);

    private int persistedProbe;

    public DebugCompanionEntity(EntityType<? extends DebugCompanionEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PHASE, 0);
    }

    public int phase() {
        return entityData.get(DATA_PHASE);
    }

    public void setPhase(int phase) {
        entityData.set(DATA_PHASE, phase);
    }

    public int persistedProbe() {
        return persistedProbe;
    }

    public void setPersistedProbe(int persistedProbe) {
        this.persistedProbe = persistedProbe;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putInt("m0_persisted_probe", persistedProbe);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        persistedProbe = input.getInt("m0_persisted_probe").orElse(0);
    }
}
