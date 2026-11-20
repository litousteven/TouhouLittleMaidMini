package com.touhoulittlemaid.mini.fabric;

import com.touhoulittlemaid.mini.client.menu.CompanionScreen;
import com.touhoulittlemaid.mini.client.render.DebugCompanionRenderer;
import com.touhoulittlemaid.mini.fabric.content.FabricContent;
import com.touhoulittlemaid.mini.fabric.menu.CompanionMenus;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;

public final class TlmmFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRenderers.register(FabricContent.COMPANION, DebugCompanionRenderer::new);
        EntityRenderers.register(FabricContent.DEBUG_COMPANION, DebugCompanionRenderer::new);
        MenuScreens.register(CompanionMenus.TYPE, CompanionScreen::new);
    }
}
