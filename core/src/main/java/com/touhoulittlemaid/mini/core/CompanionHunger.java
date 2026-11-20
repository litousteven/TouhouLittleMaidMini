package com.touhoulittlemaid.mini.core;

/**
 * Hunger for a companion.
 *
 * <p>Companions are not players, so they do not use {@code FoodData}. This model keeps
 * the same 0-20 scale while staying independent from Minecraft so the decay, eating and
 * starvation rules can be unit tested.
 */
public final class CompanionHunger {

    public static final int MAX_LEVEL = 20;

    /** Below this level the body looks for food. */
    public static final int EAT_BELOW = 18;

    private int level;
    private int ticksUntilDecay;

    public CompanionHunger(int level) {
        this.level = clamp(level);
        this.ticksUntilDecay = 0;
    }

    public static CompanionHunger full() {
        return new CompanionHunger(MAX_LEVEL);
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = clamp(level);
    }

    public int percent() {
        return level * 100 / MAX_LEVEL;
    }

    /**
     * Advances hunger by one tick.
     *
     * @param decayIntervalTicks ticks per hunger point; values below one disable decay
     * @return whether the level dropped on this tick
     */
    public boolean tick(int decayIntervalTicks) {
        if (decayIntervalTicks < 1 || level <= 0) {
            return false;
        }
        ticksUntilDecay++;
        if (ticksUntilDecay < decayIntervalTicks) {
            return false;
        }
        ticksUntilDecay = 0;
        level--;
        return true;
    }

    public boolean needsFood() {
        return level < EAT_BELOW;
    }

    public boolean isStarving() {
        return level <= 0;
    }

    /** Eats one item worth of nutrition and returns whether the level changed. */
    public boolean eat(int nutrition) {
        if (nutrition <= 0) {
            return false;
        }
        int previous = level;
        level = clamp(level + nutrition);
        return level != previous;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(MAX_LEVEL, value));
    }
}
