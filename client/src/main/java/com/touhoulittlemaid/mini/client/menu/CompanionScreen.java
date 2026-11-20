package com.touhoulittlemaid.mini.client.menu;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.fabric.menu.CompanionMenu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

/** Compact management screen for a companion. */
public final class CompanionScreen extends AbstractContainerScreen<CompanionMenu> {

    private static final int ENTER_KEY = 257;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF000000;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;
    private static final int SLOT_FILL_COLOR = 0xFF8B8B8B;
    private static final int TEXT_COLOR = 0xFF404040;

    @Nullable
    private Button behaviorButton;
    @Nullable
    private Button deleteButton;
    @Nullable
    private EditBox nameBox;
    private boolean deleteArmed;

    public CompanionScreen(CompanionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 240);
        this.inventoryLabelY = 82;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        behaviorButton = addRenderableWidget(Button.builder(behaviorLabel(),
                        ignored -> press(CompanionMenu.BUTTON_CYCLE_BEHAVIOR))
                .bounds(x + 8, y + 174, 76, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.companion.set_home"),
                        ignored -> press(CompanionMenu.BUTTON_SET_HOME))
                .bounds(x + 92, y + 174, 76, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.companion.clear_home"),
                        ignored -> press(CompanionMenu.BUTTON_CLEAR_HOME))
                .bounds(x + 8, y + 196, 76, 18).build());
        deleteButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.companion.delete"),
                        ignored -> onDeletePressed())
                .bounds(x + 92, y + 196, 76, 18).build());

        nameBox = new EditBox(font, x + 8, y + 220, 116, 18,
                Component.translatable("gui.tlmm.companion.name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("gui.tlmm.companion.name_hint"));
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.companion.rename"),
                        ignored -> renameCompanion())
                .bounds(x + 128, y + 220, 40, 18).build());

        updateBehaviorLabel();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        graphics.fill(x, y, x + imageWidth, y + 1, BORDER_COLOR);
        graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, BORDER_COLOR);
        graphics.fill(x, y, x + 1, y + imageHeight, BORDER_COLOR);
        graphics.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, BORDER_COLOR);

        for (Slot slot : this.menu.slots) {
            int slotX = x + slot.x;
            int slotY = y + slot.y;
            graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, SLOT_BORDER_COLOR);
            graphics.fill(slotX, slotY, slotX + 16, slotY + 16, SLOT_FILL_COLOR);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, 8, 6, TEXT_COLOR, false);
        graphics.text(font, playerInventoryTitle, 8, inventoryLabelY, TEXT_COLOR, false);

        int health = menu.data().get(CompanionMenu.DATA_HEALTH);
        int maxHealth = menu.data().get(CompanionMenu.DATA_MAX_HEALTH);
        int hunger = menu.data().get(CompanionMenu.DATA_HUNGER);
        int radius = menu.data().get(CompanionMenu.DATA_RADIUS);
        int backpackUsed = menu.data().get(CompanionMenu.DATA_BACKPACK_USED);
        int equipmentUsed = menu.data().get(CompanionMenu.DATA_EQUIPMENT_USED);
        boolean mcp = menu.data().get(CompanionMenu.DATA_MCP_CONNECTED) != 0;

        graphics.text(font,
                Component.translatable("gui.tlmm.companion.stats_health",
                        formatTenths(health), formatTenths(maxHealth), hunger),
                8, 62, TEXT_COLOR, false);
        graphics.text(font,
                Component.translatable("gui.tlmm.companion.stats_other",
                        radius, backpackUsed, equipmentUsed),
                8, 72, TEXT_COLOR, false);
        Component mcpStatus = Component.translatable(
                mcp ? "gui.tlmm.mcp_on" : "gui.tlmm.mcp_off");
        graphics.text(font, mcpStatus, imageWidth - 8 - font.width(mcpStatus), 6,
                mcp ? 0xFF1B7F1B : 0xFF9A3A3A, false);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateBehaviorLabel();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (nameBox != null && nameBox.isFocused() && event.key() == ENTER_KEY) {
            renameCompanion();
            return true;
        }
        return super.keyPressed(event);
    }

    private void onDeletePressed() {
        if (!deleteArmed) {
            deleteArmed = true;
            if (deleteButton != null) {
                deleteButton.setMessage(Component.translatable("gui.tlmm.companion.confirm_delete"));
            }
            return;
        }
        press(CompanionMenu.BUTTON_CONFIRM_DELETE);
    }

    private void renameCompanion() {
        if (nameBox == null || minecraft == null || minecraft.player == null) {
            return;
        }
        String name = nameBox.getValue().strip();
        if (name.isEmpty()) {
            return;
        }
        minecraft.player.connection.sendCommand("tlmm rename " + name);
    }

    private void press(int buttonId) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode != null) {
            client.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }
    }

    private void updateBehaviorLabel() {
        if (behaviorButton != null) {
            behaviorButton.setMessage(behaviorLabel());
        }
        if (deleteButton != null && !deleteArmed) {
            deleteButton.setMessage(Component.translatable("gui.tlmm.companion.delete"));
        }
    }

    private Component behaviorLabel() {
        int ordinal = menu.data().get(CompanionMenu.DATA_BEHAVIOR);
        LocalBehavior[] values = LocalBehavior.values();
        LocalBehavior behavior = values[Math.max(0, Math.min(ordinal, values.length - 1))];
        return Component.translatable("gui.tlmm.companion.behavior." + behavior.id());
    }

    private static String formatTenths(int value) {
        return Integer.toString(value / 10);
    }
}
