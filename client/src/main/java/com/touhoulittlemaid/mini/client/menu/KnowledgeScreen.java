package com.touhoulittlemaid.mini.client.menu;

import com.touhoulittlemaid.mini.fabric.network.KnowledgeViewPayload;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * The "mod content" review page.
 *
 * <p>Every row is one proposal a model made and the player has not confirmed yet. Approving
 * or rejecting sends a command rather than a bespoke packet, so the decision travels the same
 * permission-checked path as {@code /tlmm knowledge approve}.
 */
public final class KnowledgeScreen extends Screen {

    private static final int PANEL_WIDTH = 360;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_VISIBLE_ROWS = 7;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF202020;
    private static final int TEXT_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF5A5A5A;

    private KnowledgeViewPayload payload;

    public KnowledgeScreen(KnowledgeViewPayload payload) {
        super(Component.translatable("gui.tlmm.knowledge.title"));
        this.payload = payload;
    }

    /** The server pushed a fresh view; redraw without reopening. */
    public void update(KnowledgeViewPayload next) {
        this.payload = next;
        this.rebuildWidgets();
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
        List<KnowledgeViewPayload.Row> rows = visibleRows();
        for (int index = 0; index < rows.size(); index++) {
            KnowledgeViewPayload.Row row = rows.get(index);
            int rowY = top + 44 + index * ROW_HEIGHT;
            addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.knowledge.approve"),
                            ignored -> command("tlmm knowledge approve " + row.id()))
                    .bounds(left + PANEL_WIDTH - 66, rowY, 30, 16).build());
            addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.knowledge.deny"),
                            ignored -> command("tlmm knowledge deny " + row.id()))
                    .bounds(left + PANEL_WIDTH - 34, rowY, 28, 16).build());
        }
        int footer = top + panelHeight() - 22;
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.knowledge.scan"),
                        ignored -> command("tlmm knowledge scan"))
                .bounds(left + 8, footer, 60, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.knowledge.export"),
                        ignored -> command("tlmm knowledge export"))
                .bounds(left + 72, footer, 60, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.knowledge.close"),
                        ignored -> onClose())
                .bounds(left + PANEL_WIDTH - 68, footer, 60, 16).build());
    }

    private List<KnowledgeViewPayload.Row> visibleRows() {
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
        graphics.fill(right - 1, top, right, bottom, BORDER_COLOR);

        graphics.text(font, title, left + 10, top + 8, TEXT_COLOR, false);
        graphics.text(font, Component.translatable("gui.tlmm.knowledge.summary",
                        payload.accepted(), payload.pending(), payload.batchesToday()),
                left + 10, top + 22, MUTED_COLOR, false);
        if (!payload.batchId().isBlank()) {
            graphics.text(font, Component.literal(payload.batchId()), left + 10, top + 32,
                    MUTED_COLOR, false);
        }

        List<KnowledgeViewPayload.Row> rows = visibleRows();
        if (rows.isEmpty()) {
            graphics.text(font, Component.translatable("gui.tlmm.knowledge.empty"),
                    left + 10, top + 48, MUTED_COLOR, false);
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            KnowledgeViewPayload.Row row = rows.get(index);
            int rowY = top + 44 + index * ROW_HEIGHT;
            String head = trim(row.id(), 22) + "  " + row.kind() + "  "
                    + String.format(java.util.Locale.ROOT, "%.2f", row.confidence());
            graphics.text(font, Component.literal(head), left + 10, rowY + 1, TEXT_COLOR, false);
            graphics.text(font, Component.literal(trim(row.reason().isEmpty()
                            ? row.tags() : row.tags() + " - " + row.reason(), 44)),
                    left + 10, rowY + 11, MUTED_COLOR, false);
        }
        if (payload.pending() > rows.size()) {
            graphics.text(font, Component.translatable("gui.tlmm.knowledge.more",
                            payload.pending() - rows.size()),
                    left + 10, top + 44 + rows.size() * ROW_HEIGHT, MUTED_COLOR, false);
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
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "\u2026";
    }
}
