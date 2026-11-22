package com.touhoulittlemaid.mini.client.menu;

import com.touhoulittlemaid.mini.client.agent.AgentChatScreen;

import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.CompanionSchedule;
import com.touhoulittlemaid.mini.fabric.menu.CompanionMenu;

import java.util.Locale;

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

/** Compact, readable management screen for one companion. */
public final class CompanionScreen extends AbstractContainerScreen<CompanionMenu> {

    private static final int ENTER_KEY = 257;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int HEADER_COLOR = 0xFFAFAFAF;
    private static final int FOOTER_COLOR = 0xFFB8B8B8;
    private static final int BORDER_COLOR = 0xFF202020;
    private static final int SLOT_BORDER_COLOR = 0xFF373737;
    private static final int SLOT_FILL_COLOR = 0xFF8B8B8B;
    private static final int TEXT_COLOR = 0xFF303030;
    private static final int MUTED_TEXT_COLOR = 0xFF5A5A5A;
    private static final int HEART_COLOR = 0xFFD63B3B;
    private static final int FOOD_COLOR = 0xFFD69A2D;
    private static final int COPY_FEEDBACK_TICKS = 60;

    private static final int HEADER_HEIGHT = 16;
    private static final int MCP_LINE_Y = 78;
    private static final int MCP_HINT_Y = 87;
    private static final int STATS_LINE_Y = 60;
    private static final int STATS_SECOND_LINE_Y = 69;
    private static final int PLAYER_LABEL_Y = 96;
    private static final int BUTTON_ROW_ONE_Y = 182;
    private static final int BUTTON_ROW_TWO_Y = 202;
    private static final int NAME_ROW_Y = 222;

    @Nullable
    private Button behaviorButton;
    @Nullable
    private Button deleteButton;
    @Nullable
    private Button copyButton;
    @Nullable
    private EditBox nameBox;
    private boolean deleteArmed;
    private int copiedTicks;

    public CompanionScreen(CompanionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 240);
        this.inventoryLabelY = PLAYER_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        copyButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.mcp.copy"),
                        ignored -> copyEndpoint())
                .bounds(x + 132, y + 1, 36, 14).build());
        copyButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.tlmm.mcp.copy_tooltip")));
        Button aiButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.ai.short"),
                        ignored -> minecraft.gui.setScreen(new AiSettingsScreen()))
                .bounds(x + 88, y + 1, 40, 14).build());
        aiButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.tlmm.ai.settings")));
        Button chatButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.chat.short"),
                        ignored -> minecraft.gui.setScreen(new AgentChatScreen()))
                .bounds(x + 44, y + 1, 40, 14).build());
        chatButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.tlmm.chat.title")));

        behaviorButton = addRenderableWidget(Button.builder(behaviorLabel(),
                        ignored -> press(CompanionMenu.BUTTON_CYCLE_BEHAVIOR))
                .bounds(x + 8, y + BUTTON_ROW_ONE_Y, 76, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.companion.set_home"),
                        ignored -> press(CompanionMenu.BUTTON_SET_HOME))
                .bounds(x + 92, y + BUTTON_ROW_ONE_Y, 76, 18).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.companion.clear_home"),
                        ignored -> press(CompanionMenu.BUTTON_CLEAR_HOME))
                .bounds(x + 8, y + BUTTON_ROW_TWO_Y, 76, 18).build());
        deleteButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.companion.delete"),
                        ignored -> onDeletePressed())
                .bounds(x + 92, y + BUTTON_ROW_TWO_Y, 76, 18).build());
        deleteButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.tlmm.companion.delete_tooltip")));

        nameBox = new EditBox(font, x + 8, y + NAME_ROW_Y, 116, 18,
                Component.translatable("gui.tlmm.companion.name"));
        nameBox.setMaxLength(32);
        nameBox.setHint(Component.translatable("gui.tlmm.companion.name_hint"));
        addRenderableWidget(nameBox);
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.companion.rename"),
                        ignored -> renameCompanion())
                .bounds(x + 128, y + NAME_ROW_Y, 40, 18).build());

        updateBehaviorLabel();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL_COLOR);
        graphics.fill(x, y, x + imageWidth, y + HEADER_HEIGHT, HEADER_COLOR);
        graphics.fill(x, y + 220, x + imageWidth, y + 221, BORDER_COLOR);
        graphics.fill(x, y + 221, x + imageWidth, y + imageHeight, FOOTER_COLOR);
        graphics.fill(x, y, x + imageWidth, y + 1, BORDER_COLOR);
        graphics.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, BORDER_COLOR);
        graphics.fill(x, y, x + 1, y + imageHeight, BORDER_COLOR);
        graphics.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, BORDER_COLOR);

        for (Slot slot : this.menu.slots) {
            int slotX = x + slot.x;
            int slotY = y + slot.y;
            graphics.fill(slotX - 1, slotY - 1, slotX + 17, slotY + 17, SLOT_BORDER_COLOR);
            graphics.fill(slotX, slotY, slotX + 16, slotY + 16, SLOT_FILL_COLOR);
            if (mouseX >= slotX && mouseX < slotX + 16
                    && mouseY >= slotY && mouseY < slotY + 16) {
                graphics.fill(slotX, slotY, slotX + 16, slotY + 16, 0x60FFFFFF);
            }
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, font.plainSubstrByWidth(title.getString(), 118),
                8, 4, TEXT_COLOR, false);

        int health = menu.data().get(CompanionMenu.DATA_HEALTH);
        int maxHealth = menu.data().get(CompanionMenu.DATA_MAX_HEALTH);
        int hunger = menu.data().get(CompanionMenu.DATA_HUNGER);
        int radius = menu.data().get(CompanionMenu.DATA_RADIUS);
        int backpackUsed = menu.data().get(CompanionMenu.DATA_BACKPACK_USED);
        int equipmentUsed = menu.data().get(CompanionMenu.DATA_EQUIPMENT_USED);
        boolean mcp = menu.data().get(CompanionMenu.DATA_MCP_CONNECTED) != 0;
        int homeSet = menu.data().get(CompanionMenu.DATA_HOME_SET);

        drawHeart(graphics, 8, STATS_LINE_Y + 1, HEART_COLOR);
        graphics.text(font,
                Component.translatable("gui.tlmm.companion.stats_health",
                        formatTenths(health), formatTenths(maxHealth), hunger),
                18, STATS_LINE_Y, TEXT_COLOR, false);
        drawFood(graphics, 8, STATS_SECOND_LINE_Y + 1, FOOD_COLOR);
        graphics.text(font,
                Component.translatable("gui.tlmm.companion.stats_other",
                        radius, backpackUsed, equipmentUsed),
                18, STATS_SECOND_LINE_Y, TEXT_COLOR, false);

        int dotColor = mcp ? 0xFF1B7F1B : 0xFF9A3A3A;
        graphics.fill(8, MCP_LINE_Y + 3, 13, MCP_LINE_Y + 8, dotColor);
        Component mcpState = Component.translatable(
                mcp ? "gui.tlmm.mcp_on" : "gui.tlmm.mcp_off");
        graphics.text(font, mcpState, 17, MCP_LINE_Y,
                mcp ? 0xFF1B7F1B : 0xFF9A3A3A, false);
        if (mcp) {
            graphics.text(font, Component.literal(displayEndpoint()),
                    imageWidth - 8 - font.width(displayEndpoint()), MCP_LINE_Y,
                    MUTED_TEXT_COLOR, false);
        }

        Component hint = mcpHint();
        graphics.text(font, hint, 8, MCP_HINT_Y,
                hint.getString().startsWith("Copied") ? 0xFF1B7F1B : MUTED_TEXT_COLOR,
                false);

        graphics.text(font, playerInventoryTitle, 8, inventoryLabelY, TEXT_COLOR, false);

        if (isOver(mouseX, mouseY, 8, STATS_LINE_Y, imageWidth - 16, 18)) {
            graphics.setTooltipForNextFrame(font,
                    Component.translatable("gui.tlmm.stats.tooltip",
                            Component.translatable(homeSet != 0
                                    ? "gui.tlmm.home.set" : "gui.tlmm.home.not_set"),
                            scheduleLabel()),
                    mouseX, mouseY);
        } else if (isOver(mouseX, mouseY, 8, MCP_LINE_Y, imageWidth - 16, 18)) {
            graphics.setTooltipForNextFrame(font,
                    Component.translatable("gui.tlmm.mcp.tooltip", mcpUrl()),
                    mouseX, mouseY);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (copiedTicks > 0) {
            copiedTicks--;
        }
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

    private Component mcpHint() {
        if (copiedTicks > 0) {
            return Component.translatable("gui.tlmm.mcp.copied");
        }
        return Component.translatable(menu.data().get(CompanionMenu.DATA_MCP_CONNECTED) != 0
                ? "gui.tlmm.mcp.hint.connected"
                : "gui.tlmm.mcp.hint.token");
    }

    private Component scheduleLabel() {
        int ordinal = menu.data().get(CompanionMenu.DATA_SCHEDULE);
        CompanionSchedule[] values = CompanionSchedule.values();
        CompanionSchedule schedule = values[Math.max(0, Math.min(ordinal, values.length - 1))];
        return Component.translatable("gui.tlmm.schedule." + schedule.id());
    }

    private String mcpUrl() {
        return "http://" + hostForUrl() + ":" + mcpPort() + "/mcp";
    }

    private String displayEndpoint() {
        return hostForUrl() + ":" + mcpPort() + "/mcp";
    }

    private int mcpPort() {
        int port = menu.data().get(CompanionMenu.DATA_MCP_PORT);
        return port <= 0 ? 8765 : port;
    }

    private String hostForUrl() {
        int host = menu.data().get(CompanionMenu.DATA_MCP_HOST);
        if (host == 1) {
            return "localhost";
        }
        if (host == 2) {
            return "[::1]";
        }
        return "127.0.0.1";
    }

    private void copyEndpoint() {
        Minecraft client = Minecraft.getInstance();
        client.keyboardHandler.setClipboard(mcpUrl());
        copiedTicks = COPY_FEEDBACK_TICKS;
    }

    private void onDeletePressed() {
        if (!deleteArmed) {
            deleteArmed = true;
            if (deleteButton != null) {
                deleteButton.setMessage(
                        Component.translatable("gui.tlmm.companion.confirm_delete"));
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

    private static void drawHeart(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 1, y, x + 3, y + 1, color);
        graphics.fill(x + 4, y, x + 6, y + 1, color);
        graphics.fill(x, y + 1, x + 7, y + 3, color);
        graphics.fill(x + 1, y + 3, x + 6, y + 4, color);
        graphics.fill(x + 2, y + 4, x + 5, y + 5, color);
        graphics.fill(x + 3, y + 5, x + 4, y + 6, color);
    }

    private static void drawFood(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(x + 1, y, x + 5, y + 1, color);
        graphics.fill(x, y + 1, x + 6, y + 4, color);
        graphics.fill(x + 1, y + 4, x + 5, y + 5, color);
        graphics.fill(x + 2, y + 5, x + 4, y + 6, color);
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String formatTenths(int value) {
        return value % 10 == 0
                ? Integer.toString(value / 10)
                : String.format(Locale.ROOT, "%.1f", value / 10.0D);
    }
}
