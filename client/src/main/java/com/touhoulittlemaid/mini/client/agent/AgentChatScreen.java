package com.touhoulittlemaid.mini.client.agent;

import com.touhoulittlemaid.mini.client.menu.AiSettingsScreen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * The chat panel: one input, the conversation, and the state of the current task.
 *
 * <p>Deliberately plain. It shows what the companion said, what the server refused and how far
 * a plan got; it does not show prompts, tokens or the model's raw JSON, because a player
 * should not have to read a protocol to talk to their companion.
 */
public final class AgentChatScreen extends Screen {

    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 226;
    private static final int PANEL_COLOR = 0xFFC6C6C6;
    private static final int BORDER_COLOR = 0xFF202020;
    private static final int TEXT_COLOR = 0xFF303030;
    private static final int MUTED_COLOR = 0xFF5A5A5A;
    private static final int ERROR_COLOR = 0xFF8A1A1A;
    private static final int COMPANION_COLOR = 0xFF1A4A1A;
    private static final int SYSTEM_COLOR = 0xFF3A3A6A;
    private static final int LINE_HEIGHT = 10;

    private final Runnable listener = this::refresh;
    private EditBox input;
    private Button send;
    private Button cancel;
    private Button confirm;
    private Button drop;
    private Button settings;

    public AgentChatScreen() {
        super(Component.translatable("gui.tlmm.chat.title"));
    }

    @Override
    protected void init() {
        super.init();
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        int bottom = top + PANEL_HEIGHT;

        input = new EditBox(font, left + 8, bottom - 44, PANEL_WIDTH - 84, 18,
                Component.translatable("gui.tlmm.chat.hint"));
        input.setMaxLength(512);
        input.setHint(Component.translatable("gui.tlmm.chat.hint"));
        addRenderableWidget(input);

        send = Button.builder(Component.translatable("gui.tlmm.chat.send"),
                        ignored -> submit())
                .bounds(left + PANEL_WIDTH - 72, bottom - 44, 64, 18).build();
        addRenderableWidget(send);

        cancel = Button.builder(Component.translatable("gui.tlmm.chat.stop"),
                        ignored -> AgentLoop.get().cancelCurrent())
                .bounds(left + 8, bottom - 22, 64, 18).build();
        addRenderableWidget(cancel);

        confirm = Button.builder(Component.translatable("gui.tlmm.chat.confirm"),
                        ignored -> AgentLoop.get().confirmPending())
                .bounds(left + 76, bottom - 22, 64, 18).build();
        addRenderableWidget(confirm);

        drop = Button.builder(Component.translatable("gui.tlmm.chat.drop"),
                        ignored -> AgentLoop.get().dropPending())
                .bounds(left + 144, bottom - 22, 64, 18).build();
        addRenderableWidget(drop);

        settings = Button.builder(Component.translatable("gui.tlmm.chat.settings"),
                        ignored -> minecraft.gui.setScreen(new AiSettingsScreen()))
                .bounds(left + PANEL_WIDTH - 72, bottom - 22, 64, 18).build();
        addRenderableWidget(settings);

        AgentLoop.get().addListener(listener);
        refresh();
    }

    @Override
    public void removed() {
        AgentLoop.get().removeListener(listener);
        super.removed();
    }

    private void refresh() {
        AgentLoop loop = AgentLoop.get();
        if (send != null) {
            send.active = !loop.isBusy();
        }
        if (cancel != null) {
            cancel.visible = loop.task() != null && loop.task().running();
        }
        boolean pending = loop.hasPendingConfirmation();
        if (confirm != null) {
            confirm.visible = pending;
        }
        if (drop != null) {
            drop.visible = pending;
        }
    }

    private void submit() {
        String text = input == null ? "" : input.getValue();
        if (text == null || text.strip().isEmpty()) {
            return;
        }
        input.setValue("");
        AgentLoop.get().send(text);
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
        graphics.text(font, title, left + 8, top + 6, TEXT_COLOR, false);
        graphics.text(font, Component.translatable("gui.tlmm.chat.status", statusLine()),
                left + 8, top + 18, MUTED_COLOR, false);

        int textTop = top + 30;
        int textBottom = bottom - 48;
        int width = PANEL_WIDTH - 16;
        renderLines(graphics, left + 8, textTop, textBottom, width);
    }

    private void renderLines(GuiGraphicsExtractor graphics, int x, int top, int bottom,
            int width) {
        List<AgentLoop.Line> lines = AgentLoop.get().lines();
        List<List<FormattedCharSequence>> rendered = new ArrayList<>(lines.size());
        for (AgentLoop.Line line : lines) {
            rendered.add(font.split(Component.literal(line.text()), width - 8));
        }
        // Work backwards: the newest line matters most, older ones fall off the top.
        int available = bottom - top;
        int start = rendered.size();
        int used = 0;
        while (start > 0) {
            int height = rendered.get(start - 1).size() * LINE_HEIGHT + 2;
            if (used + height > available) {
                break;
            }
            used += height;
            start--;
        }
        int cursor = bottom - used;
        for (int index = start; index < rendered.size(); index++) {
            int color = color(lines.get(index).role());
            for (FormattedCharSequence piece : rendered.get(index)) {
                if (cursor + LINE_HEIGHT > bottom) {
                    return;
                }
                graphics.text(font, piece, x, cursor, color, false);
                cursor += LINE_HEIGHT;
            }
            cursor += 2;
        }
    }

    private static int color(String role) {
        return switch (role == null ? "" : role) {
            case "user" -> TEXT_COLOR;
            case "companion" -> COMPANION_COLOR;
            case "error" -> ERROR_COLOR;
            default -> SYSTEM_COLOR;
        };
    }

    private String statusLine() {
        AgentLoop loop = AgentLoop.get();
        AgentLoop.Task task = loop.task();
        if (loop.hasPendingConfirmation()) {
            return Component.translatable("gui.tlmm.chat.waiting_confirm").getString();
        }
        return switch (loop.state()) {
            case WAITING_OBSERVATION -> Component.translatable("gui.tlmm.chat.waiting_server")
                    .getString();
            case THINKING -> Component.translatable("gui.tlmm.chat.thinking").getString();
            case RUNNING -> task == null ? ""
                    : Component.translatable("gui.tlmm.chat.running", task.step() + 1,
                            task.steps(), task.module()).getString();
            case IDLE -> Component.translatable("gui.tlmm.chat.idle").getString();
        };
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == 256) {
            onClose();
            return true;
        }
        if (event.key() == 257 || event.key() == 335) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
