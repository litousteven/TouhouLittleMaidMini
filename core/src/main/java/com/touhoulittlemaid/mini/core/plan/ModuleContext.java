package com.touhoulittlemaid.mini.core.plan;

/**
 * What a module may touch while running.
 *
 * <p>Core stays free of Minecraft types, so the loader supplies a concrete implementation
 * and modules cast it. The only field core itself relies on is the companion id, which
 * every audit line needs.
 */
public interface ModuleContext {

    String companionId();
}
