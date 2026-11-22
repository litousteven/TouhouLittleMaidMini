package com.touhoulittlemaid.mini.core.autonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.touhoulittlemaid.mini.core.plan.PlanGate;
import org.junit.jupiter.api.Test;

class AutonomyTierTest {

    @Test
    void idsRoundTrip() {
        for (AutonomyTier tier : AutonomyTier.values()) {
            assertEquals(tier, AutonomyTier.fromId(tier.id()));
        }
        assertEquals(AutonomyTier.CHORES, AutonomyTier.fromId("not-a-tier"));
        assertTrue(AutonomyTier.parse("free").isPresent());
        assertTrue(AutonomyTier.parse("  ").isEmpty());
    }

    @Test
    void onlyTheTwoUpperTiersRunByThemselves() {
        assertFalse(AutonomyTier.OFF.scans());
        assertTrue(AutonomyTier.SUGGEST.scans());
        assertFalse(AutonomyTier.SUGGEST.autoRuns());
        assertTrue(AutonomyTier.CHORES.autoRuns());
        assertTrue(AutonomyTier.FREE.autoRuns());
    }

    @Test
    void theTierDecidesTheGatePolicy() {
        assertTrue(AutonomyTier.CHORES.policy().allowsModule("harvest_area"));
        assertFalse(AutonomyTier.CHORES.policy().allowsModule("mine_material"));
        assertTrue(AutonomyTier.FREE.policy().allowsModule("mine_material"));
        assertEquals(PlanGate.Mode.COMMAND, AutonomyTier.SUGGEST.policy().mode());
    }

    @Test
    void opportunitiesCarryTheReasonAndTheHint() {
        Opportunity opportunity = Opportunity.of("harvest_farm", "23 crops", 2)
                .with("radius", 8);
        assertEquals("harvest_farm", opportunity.id());
        assertTrue(opportunity.describe().contains("23 crops"));
        assertTrue(opportunity.describe().contains("radius=8"));
        assertTrue(opportunity.suggested().containsKey("radius"));
    }
}
