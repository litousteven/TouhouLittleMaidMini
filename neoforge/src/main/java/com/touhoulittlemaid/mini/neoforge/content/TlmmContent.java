package com.touhoulittlemaid.mini.neoforge.content;

import com.touhoulittlemaid.mini.neoforge.Tlmm;
import com.touhoulittlemaid.mini.neoforge.entity.DebugCompanionEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class TlmmContent {

    private static final DeferredRegister.Entities ENTITY_TYPES =
            DeferredRegister.createEntities(Tlmm.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<DebugCompanionEntity>> DEBUG_COMPANION =
            ENTITY_TYPES.register("debug_companion", () -> {
                ResourceKey<EntityType<?>> key = ResourceKey.create(
                        Registries.ENTITY_TYPE,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(Tlmm.MOD_ID, "debug_companion"));
                return EntityType.Builder.of(DebugCompanionEntity::new, MobCategory.CREATURE)
                        .sized(0.6F, 1.8F)
                        .clientTrackingRange(10)
                        .build(key);
            });

    private TlmmContent() {
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
