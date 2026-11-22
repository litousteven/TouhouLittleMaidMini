package com.touhoulittlemaid.mini.client.menu;

import com.touhoulittlemaid.mini.llm.ChatClient;
import com.touhoulittlemaid.mini.llm.ModelConfig;
import com.touhoulittlemaid.mini.llm.ModelConfigStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Client-side AI settings.
 *
 * <p>Writes {@code config/tlmm-client.toml} and {@code config/tlmm/credentials.key}. The key is
 * never displayed: the screen shows whether one exists, and a blank field means "keep the
 * current one". Nothing here talks to the server — the server has no key and no model config.
 */
public final class AiSettingsScreen extends Screen {

    private static final int PANEL_WIDTH = 260;
    private static final int PANEL_HEIGHT = 168;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF202020;
    private static final int TEXT_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF5A5A5A;
    private static final int OK_COLOR = 0xFF1A4A1A;
    private static final int ERROR_COLOR = 0xFF8A1A1A;

    private EditBox baseUrl;
    private EditBox model;
    private EditBox token;
    private volatile String status = "";
    private volatile int statusColor = MUTED_COLOR;
    private boolean testing;

    public AiSettingsScreen() {
        super(Component.translatable("gui.tlmm.ai.title"));
    }

    @Override
    protected void init() {
        super.init();
        ModelConfigStore.Loaded loaded = load();
        ModelConfig config = loaded.config();
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        baseUrl = addBox(left + 10, top + 30, "gui.tlmm.ai.base_url", 240);
        baseUrl.setValue(config.baseUrl());
        model = addBox(left + 10, top + 58, "gui.tlmm.ai.model", 240);
        model.setValue(config.model());
        token = addBox(left + 10, top + 86,
                loaded.hasToken() ? "gui.tlmm.ai.token_keep" : "gui.tlmm.ai.token", 240);
        token.setMaxLength(512);

        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.ai.save"),
                        ignored -> save())
                .bounds(left + 10, top + 112, 76, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.ai.test"),
                        ignored -> test())
                .bounds(left + 92, top + 112, 76, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.tlmm.ai.cancel"),
                        ignored -> onClose())
                .bounds(left + 174, top + 112, 76, 18).build());
        statusColor = MUTED_COLOR;
        status = Component.translatable("gui.tlmm.ai.key_state",
                Component.translatable(loaded.hasToken()
                        ? "gui.tlmm.ai.key_set" : "gui.tlmm.ai.key_missing").getString())
                .getString();
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
        int right = left + PANEL_WIDTH;
        int bottom = top + PANEL_HEIGHT;
        graphics.fill(left, top, right, bottom, PANEL_COLOR);
        graphics.fill(left, top, right, top + 1, BORDER_COLOR);
        graphics.fill(left, bottom - 1, right, bottom, BORDER_COLOR);
        graphics.fill(left, top, left + 1, bottom, BORDER_COLOR);
        graphics.fill(right - 1, top, right, bottom, BORDER_COLOR);
        graphics.text(font, title, left + 10, top + 8, TEXT_COLOR, false);
        graphics.text(font, Component.literal(status), left + 10, top + 138, statusColor,
                false);
        graphics.text(font, Component.translatable("gui.tlmm.ai.client_only"), left + 10,
                top + 150, MUTED_COLOR, false);
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
        ModelConfigStore.Loaded loaded = load();
        ModelConfig config = new ModelConfig(baseUrl.getValue(), model.getValue(),
                loaded.config().temperature(), loaded.config().maxTokens(),
                loaded.config().timeoutSeconds());
        String secret = token.getValue() == null ? "" : token.getValue().strip();
        try {
            ModelConfigStore.save(gameDirectory(), config, secret.isEmpty() ? null : secret);
            status = Component.translatable("gui.tlmm.ai.saved_client").getString();
            statusColor = OK_COLOR;
            token.setValue("");
        } catch (IOException exception) {
            status = Component.translatable("gui.tlmm.ai.save_failed").getString();
            statusColor = ERROR_COLOR;
        }
    }

    /** Saves first, then makes one tiny real request on a virtual thread. */
    private void test() {
        if (testing) {
            return;
        }
        save();
        ModelConfigStore.Loaded loaded = load();
        if (!loaded.ready()) {
            status = Component.translatable("gui.tlmm.ai.not_ready").getString();
            statusColor = ERROR_COLOR;
            return;
        }
        testing = true;
        status = Component.translatable("gui.tlmm.ai.testing").getString();
        statusColor = MUTED_COLOR;
        long started = System.currentTimeMillis();
        Thread.ofVirtual().start(() -> {
            String text;
            int color;
            try {
                ChatClient client = new ChatClient();
                client.complete(loaded.config(), loaded.token(), List.of(
                        ChatClient.Message.system("Reply with the single word: ok"),
                        ChatClient.Message.user("ping")));
                long elapsed = System.currentTimeMillis() - started;
                text = Component.translatable("gui.tlmm.ai.test_ok", elapsed).getString();
                color = OK_COLOR;
            } catch (ChatClient.ModelException exception) {
                text = Component.translatable("gui.tlmm.ai.test_failed", exception.code())
                        .getString();
                color = ERROR_COLOR;
            } catch (RuntimeException exception) {
                text = Component.translatable("gui.tlmm.ai.test_failed",
                        exception.getClass().getSimpleName()).getString();
                color = ERROR_COLOR;
            }
            String finalText = text;
            int finalColor = color;
            Minecraft.getInstance().execute(() -> {
                testing = false;
                status = finalText;
                statusColor = finalColor;
            });
        });
    }

    private Path gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    private ModelConfigStore.Loaded load() {
        return ModelConfigStore.load(gameDirectory());
    }
}
