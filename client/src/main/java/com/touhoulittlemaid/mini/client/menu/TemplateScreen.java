package com.touhoulittlemaid.mini.client.menu;

import com.touhoulittlemaid.mini.fabric.network.TemplateViewPayload;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The learning review page: proposals, adopted templates and their trust state.
 *
 * <p>The server pushes the whole view on every state change, so "UI 状态同步" is not a
 * second source of truth: the screen only redraws what it was sent. Buttons issue commands,
 * so they go through the same permission-checked path as {@code /tlmm template ...}.
 */
public final class TemplateScreen extends Screen {

    private static final int PANEL_WIDTH = 380;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_VISIBLE_ROWS = 7;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF202020;
    private static final int TEXT_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF5A5A5A;

    private TemplateViewPayload payload;

    public TemplateScreen(TemplateViewPayload payload) {
        super(Component.translatable("gui.tlmm.template.title"));
        this.payload = payload;
    }

    public void update(TemplateViewPayload next) {
        this.payload = next;
        this.rebuildWidgets();
    }

    public TemplateViewPayload view() {
        return payload;
    }

    private static int panelHeight() {
        return 52 + ROW_HEIGHT * MAX_VISIBLE_ROWS + 26;
    }

    private static int panelLeft(int width) {
        return (width - PANEL_WIDTH) / 2;
    }

    private static int panelTop(int height) {
        return (height - panelHeight()) / 2;
    }

    @Override
    protected void init() {
        super.init();
        int left = panelLeft(width);
        int top = panelTop(height);
        List<TemplateViewPayload.Row> rows = visibleRows();
        for (int index = 0; index < rows.size(); index++) {
            TemplateViewPayload.Row row = rows.get(index);
            int rowY = top + 44 + index * ROW_HEIGHT;
            int x = left + PANEL_WIDTH - 8;
            if ("proposal".equals(row.kind())) {
                x -= 40;
                addRenderableWidget(Button.builder(
                                Component.translatable("gui.tlmm.template.adopt"),
                                ignored -> command("tlmm template adopt " + row.id()))
                        .bounds(x, rowY, 36, 16).build());
                x -= 40;
                addRenderableWidget(Button.builder(
                                Component.translatable("gui.tlmm.template.reject"),
                                ignored -> command("tlmm template reject " + row.id()))
                        .bounds(x, rowY, 36, 16).build());
            } else {
                if ("supervised".equals(row.state())) {
                    x -= 46;
                    addRenderableWidget(Button.builder(
                                    Component.translatable("gui.tlmm.template.confirm"),
                                    ignored -> command("tlmm template confirm " + row.id()))
                            .bounds(x, rowY, 42, 16).build());
                }
                x -= 40;
                addRenderableWidget(Button.builder(
                                Component.translatable("gui.tlmm.template.run"),
                                ignored -> command("tlmm template run " + row.id()))
                        .bounds(x, rowY, 36, 16).build());
                x -= 52;
                addRenderableWidget(Button.builder(
                                Component.translatable("gui.tlmm.template.rollback"),
                                ignored -> command("tlmm template rollback " + row.id()))
                        .bounds(x, rowY, 48, 16).build());
            }
        }
        int footer = top + panelHeight() - 22;
        addRenderableWidget(Button.builder(
                        Component.translatable("gui.tlmm.template.close"),
                        ignored -> onClose())
                .bounds(left + PANEL_WIDTH - 68, footer, 60, 16).build());
    }

    private List<TemplateViewPayload.Row> visibleRows() {
        return payload.rows().size() <= MAX_VISIBLE_ROWS
                ? payload.rows() : payload.rows().subList(0, MAX_VISIBLE_ROWS);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int left = panelLeft(width);
        int top = panelTop(height);
        int right = left + PANEL_WIDTH;
        int bottom = top + panelHeight();
        graphics.fill(left, top, right, bottom, PANEL_COLOR);
        graphics.fill(left, top, right, top + 1, BORDER_COLOR);
        graphics.fill(left, bottom - 1, right, bottom, BORDER_COLOR);
        graphics.fill(left, top, left + 1, bottom, BORDER_COLOR);
        graphics.fill(right - 1, top, right - 1, bottom, BORDER_COLOR);

        graphics.text(font, title, left + 10, top + 8, TEXT_COLOR, false);
        graphics.text(font, Component.translatable("gui.tlmm.template.summary",
                        payload.pending(), payload.templates(), payload.trusted()),
                left + 10, top + 22, MUTED_COLOR, false);

        List<TemplateViewPayload.Row> rows = visibleRows();
        if (rows.isEmpty()) {
            graphics.text(font, Component.translatable("gui.tlmm.template.empty"),
                    left + 10, top + 48, MUTED_COLOR, false);
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            TemplateViewPayload.Row row = rows.get(index);
            int rowY = top + 44 + index * ROW_HEIGHT;
            graphics.text(font, Component.literal(trim(row.id(), 20) + "  " + row.kind()
                            + "  " + row.state() + "  " + trim(row.name(), 14)),
                    left + 10, rowY + 1, TEXT_COLOR, false);
            graphics.text(font, Component.literal(trim(row.detail(), 44)),
                    left + 10, rowY + 11, MUTED_COLOR, false);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void command(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.connection.sendCommand(command);
        }
    }

    private static String trim(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "\u2026";
    }
}
