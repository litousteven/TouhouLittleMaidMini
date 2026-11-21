package com.touhoulittlemaid.mini.client.render;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/** Extra values the companion renderer needs beyond the vanilla humanoid state. */
public final class CompanionRenderState extends HumanoidRenderState {

    public float health;
    public float maxHealth;
    public int hunger;
    public boolean mcpConnected;
    public String behavior = "stay";
    public String schedule = "all";
    public boolean homeSet;
}
