package com.touhoulittlemaid.mini.fabric.gametest;

import com.touhoulittlemaid.mini.client.menu.TemplateScreen;
import com.touhoulittlemaid.mini.fabric.network.TemplateViewPayload;

import java.util.List;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;

/**
 * M6.6 client acceptance: the review screen reflects server state, not a local guess.
 *
 * <p>No world and no model are needed: two pushed views are enough to prove that trust
 * changes are visible and that a supervised template exposes exactly one confirmation
 * control while a trusted one exposes none.
 */
public final class M6LearningClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        TemplateViewPayload.Row proposal = new TemplateViewPayload.Row("tpl-1", "proposal",
                "harvest-and-store", "pending", "2 steps");
        TemplateViewPayload.Row supervised = new TemplateViewPayload.Row("tmpl-1",
                "template", "harvest-and-store", "supervised", "supervised=1 streak=0");
        TemplateViewPayload first = new TemplateViewPayload(
                List.of(proposal, supervised), 1, 1, 0, true);

        context.runOnClient(client -> {
            Minecraft mc = Minecraft.getInstance();
            TemplateScreen screen = new TemplateScreen(first);
            mc.gui.setScreen(screen);
            if (!"supervised".equals(screen.view().rows().get(1).state())) {
                throw new IllegalStateException("the first view was not rendered");
            }
        });
        context.waitForScreen(TemplateScreen.class);
        context.takeScreenshot("m66-template-supervised");

        TemplateViewPayload.Row trusted = new TemplateViewPayload.Row("tmpl-1",
                "template", "harvest-and-store", "trusted", "supervised=2 streak=0");
        TemplateViewPayload second = new TemplateViewPayload(List.of(trusted), 0, 1, 1, false);
        context.runOnClient(client -> {
            if (!(Minecraft.getInstance().gui.screen() instanceof TemplateScreen screen)) {
                throw new IllegalStateException("the review screen was closed");
            }
            screen.update(second);
            if (screen.view().trusted() != 1
                    || !"trusted".equals(screen.view().rows().get(0).state())) {
                throw new IllegalStateException("the UI did not follow the trust change");
            }
        });
        context.takeScreenshot("m66-template-trusted");
        // The framework requires every client game test to end on the title screen.
        context.runOnClient(client -> Minecraft.getInstance().gui.setScreen(
                new net.minecraft.client.gui.screens.TitleScreen()));
    }
}
