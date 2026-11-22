package com.touhoulittlemaid.mini.core.autonomy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.touhoulittlemaid.mini.core.plan.PlanGate;

/** The candidate list is filtered by the same policy that will gate the plan. */
class OpportunityTest {

    private static final List<String> IDS = List.of("gather_building", "harvest_farm",
            "cook_food", "supply_owner", "tidy_station", "tidy_chests");

    @Test
    void choresOffersHouseworkButNeverLeavesTheBase() {
        PlanGate.Policy chores = PlanGate.Policy.chores();
        assertTrue(Opportunity.allowedBy(chores, "harvest_farm"));
        assertTrue(Opportunity.allowedBy(chores, "cook_food"));
        assertTrue(Opportunity.allowedBy(chores, "tidy_chests"));
        assertTrue(Opportunity.allowedBy(chores, "tidy_station"));
        assertFalse(Opportunity.allowedBy(chores, "gather_building"));
    }

    @Test
    void freeOffersEveryKnownOpportunity() {
        PlanGate.Policy free = PlanGate.Policy.free();
        for (String id : IDS) {
            assertTrue(Opportunity.allowedBy(free, id), id + " should be offered in free");
            assertFalse(Opportunity.requiredModules(id).isEmpty(), id + " has no modules");
        }
    }

    @Test
    void anUnknownIdIsNeverOffered() {
        assertFalse(Opportunity.allowedBy(PlanGate.Policy.free(), "dismantle_the_house"));
        assertTrue(Opportunity.requiredModules("dismantle_the_house").isEmpty());
    }

    @Test
    void theChoresWhitelistNamesTheModulesItNeeds() {
        for (String id : List.of("harvest_farm", "cook_food", "supply_owner", "tidy_station",
                "tidy_chests")) {
            assertTrue(PlanGate.Policy.CHORE_MODULES
                    .containsAll(Opportunity.requiredModules(id)), id);
        }
        assertTrue(PlanGate.Policy.CHORE_MODULES.contains("pickup_items"));
    }
}
