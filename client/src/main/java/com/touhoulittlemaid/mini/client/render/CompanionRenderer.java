package com.touhoulittlemaid.mini.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.touhoulittlemaid.mini.fabric.entity.CompanionEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Renders the companion body, its name and a compact status line.
 *
 * <p>The status line mirrors the synced entity state, so the client sees the same
 * health, hunger, local behaviour and MCP connection the agent sees.
 */
public final class CompanionRenderer extends HumanoidMobRenderer<CompanionEntity,
        CompanionRenderState, CompanionModel> {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("tlmm",
            "textures/entity/companion.png");
    private static final double MAX_STATUS_DISTANCE_SQ = 48.0D * 48.0D;

    public CompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new CompanionModel(context.bakeLayer(CompanionModel.LAYER)), 0.5F);
    }

    @Override
    public CompanionRenderState createRenderState() {
        return new CompanionRenderState();
    }

    @Override
    public void extractRenderState(CompanionEntity entity, CompanionRenderState state,
            float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.health = entity.getHealth();
        state.maxHealth = entity.getMaxHealth();
        state.hunger = entity.hunger().level();
        state.mcpConnected = entity.isMcpConnected();
        state.behavior = entity.behavior().id();
        state.schedule = entity.schedule().id();
        state.homeSet = entity.hasHome();
    }

    @Override
    public Identifier getTextureLocation(CompanionRenderState state) {
        return TEXTURE;
    }

    @Override
    public void submit(CompanionRenderState state, PoseStack pose,
            SubmitNodeCollector collector, CameraRenderState camera) {
        super.submit(state, pose, collector, camera);
        if (state.nameTagAttachment == null
                || state.distanceToCameraSq > MAX_STATUS_DISTANCE_SQ) {
            return;
        }
        Component status = Component.empty()
                .append(Component.translatable("gui.tlmm.status.health",
                                Integer.toString(Math.round(state.health)),
                                Integer.toString(Math.round(state.maxHealth)))
                        .withStyle(ChatFormatting.RED))
                .append(Component.literal("  "))
                .append(Component.translatable("gui.tlmm.status.food",
                                Integer.toString(state.hunger))
                        .withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  "))
                .append(Component.translatable(state.mcpConnected
                                ? "gui.tlmm.status.mcp" : "gui.tlmm.status.local")
                        .withStyle(state.mcpConnected
                                ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal("  "))
                .append(Component.translatable("gui.tlmm.companion.behavior."
                                + state.behavior)
                        .withStyle(ChatFormatting.AQUA));
        collector.submitNameTag(pose, state.nameTagAttachment, 10, status, true,
                state.lightCoords, camera);
    }
}
