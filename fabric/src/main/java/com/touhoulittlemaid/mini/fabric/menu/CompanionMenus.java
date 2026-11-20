package com.touhoulittlemaid.mini.fabric.menu;

import com.touhoulittlemaid.mini.fabric.TlmmFabric;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.jetbrains.annotations.Nullable;

/** Registers and opens the companion management menu. */
public final class CompanionMenus {

    public static final MenuType<CompanionMenu> TYPE = Registry.register(
            BuiltInRegistries.MENU,
            Identifier.fromNamespaceAndPath(TlmmFabric.MOD_ID, "companion"),
            new MenuType<>(CompanionMenu::new, FeatureFlags.VANILLA_SET));

    /**
     * One-shot handoff used only if a menu is created through {@code MenuType.create}
     * on the server. The normal path passes the entity to the menu constructor.
     */
    private static final Map<UUID, UUID> PENDING = new ConcurrentHashMap<>();

    private CompanionMenus() {
    }

    /** Forces class initialisation so the menu type is registered at mod startup. */
    public static void register() {
        // Registration happens in the static initializer above.
    }

    public static void open(ServerPlayer player, CompanionEntity companion) {
        PENDING.put(player.getUUID(), companion.getUUID());
        try {
            player.openMenu(new Provider(companion));
        } finally {
            PENDING.remove(player.getUUID());
        }
    }

    @Nullable
    static CompanionEntity pendingCompanion(Player player) {
        UUID companionId = PENDING.remove(player.getUUID());
        if (companionId == null) {
            return null;
        }
        if (player.level() instanceof ServerLevel serverLevel) {
            return serverLevel.getEntityInAnyDimension(companionId) instanceof CompanionEntity companion
                    ? companion
                    : null;
        }
        return null;
    }

    private record Provider(CompanionEntity companion) implements MenuProvider {

        @Override
        public Component getDisplayName() {
            return Component.translatable("gui.tlmm.companion.title", companion.companionName());
        }

        @Override
        public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
            return new CompanionMenu(containerId, inventory, companion);
        }
    }
}
