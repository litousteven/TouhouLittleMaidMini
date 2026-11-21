package com.touhoulittlemaid.mini.client.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** In-game editor for the optional direct model client. */
public final class LlmConfigScreen extends Screen {

    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 132;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF202020;
    private static final int TEXT_COLOR = 0xFF303030;

    private EditBox baseUrl;
    private EditBox model;
    private EditBox token;

    public LlmConfigScreen() {
        super(Component.translatable("gui.tlmm.ai.title"));
    }

    @Override
    protected void init() {
        super.init();
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        baseUrl = addBox(left + 10, top + 30, "gui.tlmm.ai.base_url", 220);
        model = addBox(left + 10, top + 60, "gui.tlmm.ai.model", 220);
        token = addBox(left + 10, top + 90, "gui.tlmm.ai.token", 220);
        token.setMaxLength(512);
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.ai.save"),
                        ignored -> save())
                .bounds(left + 10, top + 108, 105, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.ai.cancel"),
                        ignored -> onClose())
                .bounds(left + 125, top + 108, 105, 18).build());
    }

    private EditBox addBox(int x, int y, String translationKey, int width) {
        EditBox box = new EditBox(font, x, y, width, 18,
                Component.translatable(translationKey));
        box.setMaxLength(256);
        box.setHint(Component.translatable(translationKey));
        addRenderableWidget(box);
        return box;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, PANEL_COLOR);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 1, BORDER_COLOR);
        graphics.fill(left, top + PANEL_HEIGHT - 1, left + PANEL_WIDTH,
                top + PANEL_HEIGHT, BORDER_COLOR);
        graphics.fill(left, top, left + 1, top + PANEL_HEIGHT, BORDER_COLOR);
        graphics.fill(left + PANEL_WIDTH - 1, top, left + PANEL_WIDTH,
                top + PANEL_HEIGHT, BORDER_COLOR);
        graphics.text(font, title, left + 10, top + 10, TEXT_COLOR, false);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    private void save() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        String base = baseUrl.getValue().strip();
        String name = model.getValue().strip();
        String secret = token.getValue().strip();
        if (!base.isEmpty()) {
            client.player.connection.sendCommand("tlmm llm base " + base);
        }
        if (!name.isEmpty()) {
            client.player.connection.sendCommand("tlmm llm model " + name);
        }
        if (!secret.isEmpty()) {
            client.player.connection.sendCommand("tlmm llm token " + secret);
        }
        client.player.connection.sendCommand("tlmm llm enabled true");
        onClose();
    }
}
