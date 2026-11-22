package com.touhoulittlemaid.mini.fabric;

import com.touhoulittlemaid.mini.client.agent.AgentLoop;
import com.touhoulittlemaid.mini.client.agent.KnowledgeAnnotator;
import com.touhoulittlemaid.mini.client.menu.CompanionScreen;
import com.touhoulittlemaid.mini.client.menu.KnowledgeScreen;
import com.touhoulittlemaid.mini.client.render.CompanionModel;
import com.touhoulittlemaid.mini.client.render.CompanionRenderer;
import com.touhoulittlemaid.mini.client.render.DebugCompanionRenderer;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.menu.CompanionMenus;
import com.touhoulittlemaid.mini.fabric.network.AgentClientPayloads;
import com.touhoulittlemaid.mini.fabric.network.KnowledgeViewPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

/**
 * Client entry point: rendering, the review screen, and the client brain.
 *
 * <p>Everything that touches a model lives behind this class. The server entry point never
 * imports {@code com.touhoulittlemaid.mini.llm}; a build task enforces that.
 */
public final class TlmmFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ModelLayerRegistry.registerModelLayer(CompanionModel.LAYER,
                CompanionModel::createBodyLayer);
        EntityRenderers.register(FabricContent.COMPANION, CompanionRenderer::new);
        EntityRenderers.register(FabricContent.DEBUG_COMPANION, DebugCompanionRenderer::new);
        MenuScreens.register(CompanionMenus.TYPE, CompanionScreen::new);

        ClientPlayNetworking.registerGlobalReceiver(KnowledgeViewPayload.TYPE,
                (payload, context) -> context.client().execute(() -> {
                    Minecraft client = Minecraft.getInstance();
                    if (payload.open()) {
                        client.gui.setScreen(new KnowledgeScreen(payload));
                    } else if (client.gui.screen() instanceof KnowledgeScreen screen) {
                        screen.update(payload);
                    }
                }));

        ClientPlayNetworking.registerGlobalReceiver(AgentClientPayloads.Observation.TYPE,
                (payload, context) -> context.client().execute(
                        () -> AgentLoop.get().onObservation(payload)));
        ClientPlayNetworking.registerGlobalReceiver(AgentClientPayloads.TaskState.TYPE,
                (payload, context) -> context.client().execute(
                        () -> AgentLoop.get().onTaskState(payload)));
        ClientPlayNetworking.registerGlobalReceiver(AgentClientPayloads.TaskResult.TYPE,
                (payload, context) -> context.client().execute(
                        () -> AgentLoop.get().onTaskResult(payload)));
        ClientPlayNetworking.registerGlobalReceiver(AgentClientPayloads.Notice.TYPE,
                (payload, context) -> context.client().execute(
                        () -> AgentLoop.get().onNotice(payload)));
        ClientPlayNetworking.registerGlobalReceiver(AgentClientPayloads.Opportunities.TYPE,
                (payload, context) -> context.client().execute(
                        () -> AgentLoop.get().onOpportunities(payload)));
        ClientPlayNetworking.registerGlobalReceiver(AgentClientPayloads.KnowledgeRequest.TYPE,
                (payload, context) -> context.client().execute(
                        () -> KnowledgeAnnotator.handle(payload)));

        ClientTickEvents.END_CLIENT_TICK.register(client -> AgentLoop.get().tick());
    }
}
