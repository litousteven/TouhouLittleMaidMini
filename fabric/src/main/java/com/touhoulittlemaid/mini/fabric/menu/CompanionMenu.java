package com.touhoulittlemaid.mini.fabric.menu;

import com.touhoulittlemaid.mini.core.CompanionInventoryLayout;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Compact companion management menu: backpack, equipment and lifecycle controls.
 *
 * <p>The server builds this with the live companion so slot clicks change the real
 * containers. The client rebuilds it from the menu type with the same slot layout and
 * local view containers that the network updates fill.
 */
public final class CompanionMenu extends AbstractContainerMenu {

    public static final int BACKPACK_START = 0;
    public static final int EQUIPMENT_START = BACKPACK_START + CompanionInventoryLayout.BACKPACK_SLOTS;
    public static final int PLAYER_INVENTORY_START =
            EQUIPMENT_START + CompanionInventoryLayout.EQUIPMENT_SLOTS;

    public static final int BUTTON_CYCLE_BEHAVIOR = 0;
    public static final int BUTTON_SET_HOME = 1;
    public static final int BUTTON_CLEAR_HOME = 2;
    public static final int BUTTON_CONFIRM_DELETE = 3;

    public static final int DATA_HEALTH = 0;
    public static final int DATA_MAX_HEALTH = 1;
    public static final int DATA_HUNGER = 2;
    public static final int DATA_BEHAVIOR = 3;
    public static final int DATA_HOME_SET = 4;
    public static final int DATA_RADIUS = 5;
    public static final int DATA_BACKPACK_USED = 6;
    public static final int DATA_MCP_CONNECTED = 7;
    public static final int DATA_EQUIPMENT_USED = 8;
    public static final int DATA_SCHEDULE = 9;
    public static final int DATA_MCP_PORT = 10;
    public static final int DATA_MCP_HOST = 11;

    private static final int DATA_COUNT = 12;

    private final SimpleContainerData data = new SimpleContainerData(DATA_COUNT);
    @Nullable
    private final CompanionEntity companion;

    /** Client-side factory used by {@code MenuType.create}. */
    public CompanionMenu(int containerId, Inventory playerInventory) {
        this(containerId, playerInventory, CompanionMenus.pendingCompanion(playerInventory.player));
    }

    public CompanionMenu(int containerId, Inventory playerInventory,
            @Nullable CompanionEntity companion) {
        super(CompanionMenus.TYPE, containerId);
        this.companion = companion;

        SimpleContainer backpack = companion != null
                ? companion.backpack()
                : new SimpleContainer(CompanionInventoryLayout.BACKPACK_SLOTS);
        SimpleContainer equipment = companion != null
                ? companion.equipment()
                : new SimpleContainer(CompanionInventoryLayout.EQUIPMENT_SLOTS);

        for (int slot = 0; slot < CompanionInventoryLayout.BACKPACK_SLOTS; slot++) {
            addSlot(new Slot(backpack, slot, 8 + slot * 18, 18));
        }
        for (int slot = 0; slot < CompanionInventoryLayout.EQUIPMENT_SLOTS; slot++) {
            EquipmentSlot equipmentSlot = CompanionEntity.equipmentSlotAt(slot);
            addSlot(new EquipmentMenuSlot(equipment, slot, 8 + slot * 18, 40, equipmentSlot));
        }
        addStandardInventorySlots(playerInventory, 8, 106);

        refreshData();
        addDataSlots(data);
    }

    public ContainerData data() {
        return data;
    }

    @Nullable
    public CompanionEntity companion() {
        return companion;
    }

    @Override
    public void broadcastChanges() {
        refreshData();
        super.broadcastChanges();
    }

    private void refreshData() {
        if (companion == null) {
            return;
        }
        data.set(DATA_HEALTH, Math.round(companion.getHealth() * 10.0F));
        data.set(DATA_MAX_HEALTH, Math.round(companion.getMaxHealth() * 10.0F));
        data.set(DATA_HUNGER, companion.hunger().level());
        data.set(DATA_BEHAVIOR, companion.behavior().ordinal());
        data.set(DATA_HOME_SET, companion.hasHome() ? 1 : 0);
        data.set(DATA_RADIUS, companion.workRadius());
        data.set(DATA_BACKPACK_USED, companion.backpackUsedSlots());
        data.set(DATA_MCP_CONNECTED, companion.isMcpConnected() ? 1 : 0);
        data.set(DATA_EQUIPMENT_USED, companion.equipmentUsedSlots());
        data.set(DATA_SCHEDULE, companion.schedule().ordinal());
        var serverConfig = com.touhoulittlemaid.mini.fabric.config.CompanionConfigs.current();
        data.set(DATA_MCP_PORT, serverConfig.mcpPort());
        data.set(DATA_MCP_HOST, switch (serverConfig.mcpHost()) {
            case "localhost" -> 1;
            case "::1" -> 2;
            default -> 0;
        });
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (companion == null || !companion.isOwner(player)) {
            return false;
        }
        switch (buttonId) {
            case BUTTON_CYCLE_BEHAVIOR -> companion.setBehavior(nextBehavior(companion.behavior()));
            case BUTTON_SET_HOME -> companion.setHome(companion.blockPosition());
            case BUTTON_CLEAR_HOME -> companion.clearHome();
            case BUTTON_CONFIRM_DELETE -> {
                if (!companion.canBeDeletedInventoryLossless()) {
                    player.sendSystemMessage(Component.translatable(
                            "gui.tlmm.companion.delete_blocked"));
                    return false;
                }
                companion.discard();
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                    serverPlayer.closeContainer();
                }
                player.sendSystemMessage(Component.translatable(
                        "gui.tlmm.companion.deleted", companion.companionName()));
            }
            default -> {
                return false;
            }
        }
        refreshData();
        return true;
    }

    private static LocalBehavior nextBehavior(LocalBehavior current) {
        return switch (current) {
            case STAY -> LocalBehavior.FOLLOW;
            case FOLLOW -> LocalBehavior.GUARD;
            case GUARD -> LocalBehavior.STAY;
        };
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        int playerEnd = PLAYER_INVENTORY_START + 36;
        if (slotIndex < PLAYER_INVENTORY_START) {
            if (!moveItemStackTo(stack, PLAYER_INVENTORY_START, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = moveItemStackTo(stack, BACKPACK_START, EQUIPMENT_START, false)
                    || moveItemStackTo(stack, EQUIPMENT_START, PLAYER_INVENTORY_START, false);
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return companion == null
                || companion.isAlive() && player.distanceToSqr(companion) <= 64.0D;
    }

    @Override
    public void removed(Player player) {
        if (companion != null) {
            companion.applyEquipment();
            companion.backpack().setChanged();
            companion.equipment().setChanged();
        }
        super.removed(player);
    }

    private final class EquipmentMenuSlot extends Slot {

        private final EquipmentSlot equipmentSlot;

        EquipmentMenuSlot(net.minecraft.world.Container container, int slot, int x, int y,
                EquipmentSlot equipmentSlot) {
            super(container, slot, x, y);
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (stack.isEmpty()) {
                return true;
            }
            if (equipmentSlot == EquipmentSlot.MAINHAND
                    || equipmentSlot == EquipmentSlot.OFFHAND) {
                return true;
            }
            if (companion != null) {
                return companion.isEquippableInSlot(stack, equipmentSlot);
            }
            return stack.has(DataComponents.EQUIPPABLE)
                    && stack.get(DataComponents.EQUIPPABLE).slot() == equipmentSlot;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
