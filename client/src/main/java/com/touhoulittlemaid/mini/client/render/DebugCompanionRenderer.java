package com.touhoulittlemaid.mini.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

public final class DebugCompanionRenderer<T extends Mob>
        extends HumanoidMobRenderer<T, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

    private static final Identifier PLACEHOLDER_TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/zombie/zombie.png");

    public DebugCompanionRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return PLACEHOLDER_TEXTURE;
    }
}
