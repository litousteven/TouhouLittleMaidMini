package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionHungerTest {

    @Test
    void startsFullAndNeedsNoFood() {
        CompanionHunger hunger = CompanionHunger.full();
        assertEquals(20, hunger.level());
        assertEquals(100, hunger.percent());
        assertFalse(hunger.needsFood());
        assertFalse(hunger.isStarving());
    }

    @Test
    void decaysOnePointPerInterval() {
        CompanionHunger hunger = new CompanionHunger(3);
        assertFalse(hunger.tick(20));
        for (int tick = 1; tick < 19; tick++) {
            assertFalse(hunger.tick(20));
        }
        assertEquals(3, hunger.level());
        assertTrue(hunger.tick(20));
        assertEquals(2, hunger.level());
    }

    @Test
    void neverDecaysBelowZero() {
        CompanionHunger hunger = new CompanionHunger(0);
        assertFalse(hunger.tick(1));
        assertEquals(0, hunger.level());
        assertTrue(hunger.isStarving());
    }

    @Test
    void decayCanBeDisabled() {
        CompanionHunger hunger = new CompanionHunger(20);
        for (int tick = 0; tick < 100; tick++) {
            assertFalse(hunger.tick(0));
        }
        assertEquals(20, hunger.level());
    }

    @Test
    void eatingClampsAtMaximum() {
        CompanionHunger hunger = new CompanionHunger(10);
        assertTrue(hunger.eat(5));
        assertEquals(15, hunger.level());
        assertTrue(hunger.eat(100));
        assertEquals(20, hunger.level());
        assertFalse(hunger.eat(5));
        assertFalse(hunger.eat(0));
    }

    @Test
    void levelInputIsClamped() {
        assertEquals(20, new CompanionHunger(99).level());
        assertEquals(0, new CompanionHunger(-4).level());
    }
}
