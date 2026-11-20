package com.touhoulittlemaid.mini.fabric.content;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.DebugCompanionEntity;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

public final class FabricContent {

    private static final Identifier DEBUG_COMPANION_ID =
            Identifier.fromNamespaceAndPath(TlmmFabric.MOD_ID, "debug_companion");
    private static final ResourceKey<EntityType<?>> DEBUG_COMPANION_KEY =
            ResourceKey.create(Registries.ENTITY_TYPE, DEBUG_COMPANION_ID);

    public static final EntityType<DebugCompanionEntity> DEBUG_COMPANION =
            Registry.register(BuiltInRegistries.ENTITY_TYPE, DEBUG_COMPANION_ID,
                    EntityType.Builder.of(DebugCompanionEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(10)
                            .build(DEBUG_COMPANION_KEY));

    private FabricContent() {
    }

    public static void register() {
        FabricDefaultAttributeRegistry.register(DEBUG_COMPANION, Mob.createMobAttributes());
    }
}
