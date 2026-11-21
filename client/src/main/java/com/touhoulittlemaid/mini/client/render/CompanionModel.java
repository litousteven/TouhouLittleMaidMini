package com.touhoulittlemaid.mini.client.render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * A small humanoid companion model with a bow on the head.
 *
 * <p>The body keeps the vanilla humanoid mesh so armour, held items and the existing
 * equipment slots keep working; the bow adds the only extra silhouette.
 */
public final class CompanionModel extends HumanoidModel<CompanionRenderState> {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath("tlmm", "companion"), "main");

    private final ModelPart ribbon;

    public CompanionModel(ModelPart root) {
        super(root);
        this.ribbon = root.getChild("head").getChild("ribbon");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        mesh.getRoot().getChild("head").addOrReplaceChild("ribbon",
                CubeListBuilder.create()
                        .texOffs(64, 0)
                        .addBox(-5.0F, -10.0F, -5.0F, 10.0F, 1.0F, 10.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void setupAnim(CompanionRenderState state) {
        super.setupAnim(state);
        float idle = Mth.sin(state.ageInTicks * 0.09F) * 0.025F;
        if (state.walkAnimationSpeed < 0.05F) {
            body.xRot += idle;
            rightArm.xRot += idle;
            leftArm.xRot -= idle;
        }
        head.yRot += Mth.sin(state.ageInTicks * 0.05F) * 0.02F;
        ribbon.zRot = Mth.sin(state.ageInTicks * 0.12F) * 0.06F;
    }
}
