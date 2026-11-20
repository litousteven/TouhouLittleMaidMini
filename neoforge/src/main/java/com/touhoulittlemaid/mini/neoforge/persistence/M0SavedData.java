package com.touhoulittlemaid.mini.neoforge.persistence;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.touhoulittlemaid.mini.neoforge.Tlmm;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class M0SavedData extends SavedData {

    private static final Codec<M0SavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("value", 0).forGetter(M0SavedData::value)
    ).apply(instance, M0SavedData::new));

    private static final SavedDataType<M0SavedData> TYPE = new SavedDataType<>(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(Tlmm.MOD_ID, "m0_state"),
            M0SavedData::new,
            CODEC,
            DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    private int value;

    public M0SavedData() {
    }

    private M0SavedData(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public void increment() {
        value++;
        setDirty();
    }

    public static M0SavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }
}
