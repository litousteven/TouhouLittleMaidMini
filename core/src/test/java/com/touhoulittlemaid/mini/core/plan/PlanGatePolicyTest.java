package com.touhoulittlemaid.mini.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The tier policy is a whitelist, not a risk class: chores harvest but never mine. */
class PlanGatePolicyTest {

    private static ModuleRegistry registry() {
        return ModuleRegistry.builder()
                .register(new Stub("harvest_area", RiskClass.DESTROY))
                .register(new Stub("mine_material", RiskClass.DESTROY))
                .register(new Stub("say", RiskClass.OBSERVE))
                .build();
    }

    private static ParsedPlan one(String module) {
        try {
            return PlanJson.parse("{\"version\":1,\"steps\":[{\"module\":\"" + module + "\","
                    + "\"params\":{}}]}");
        } catch (PlanFormatException exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void choresAllowsAWhitelistedDestructiveModule() {
        var result = new PlanGate(registry(), PlanGate.Policy.chores()).check(one("harvest_area"));
        assertTrue(result.ok(), result.code() + " " + result.message());
    }

    @Test
    void choresRefusesAModuleOutsideTheWhitelist() {
        var result = new PlanGate(registry(), PlanGate.Policy.chores()).check(one("mine_material"));
        assertFalse(result.ok());
        assertEquals(FailureCodes.RISK_DENIED, result.code());
        assertTrue(result.message().contains("only allows"), result.message());
    }

    @Test
    void aCommandStillNeedsConfirmationForDestruction() {
        var result = new PlanGate(registry(), PlanGate.Policy.command()).check(one("harvest_area"));
        assertFalse(result.ok());
        assertEquals(FailureCodes.CONFIRMATION_REQUIRED, result.code());
    }

    @Test
    void aConfirmedCommandAllowsDestruction() {
        var result = new PlanGate(registry(), PlanGate.Policy.commandConfirmed())
                .check(one("harvest_area"));
        assertTrue(result.ok(), result.message());
    }

    @Test
    void freeRunsEverythingButKeepsItsOwnDestroyBudget() {
        var gate = new PlanGate(registry(), PlanGate.Policy.free());
        assertTrue(gate.check(one("mine_material")).ok());
        assertTrue(gate.policy().allowDestroy());
        assertTrue(gate.policy().allowedModules().isEmpty());
    }

    @Test
    void theChoreWhitelistIsExplicit() {
        List<String> whitelist = PlanGate.Policy.CHORE_MODULES;
        assertTrue(whitelist.contains("harvest_area"));
        assertTrue(whitelist.contains("tidy_chests"));
        assertTrue(whitelist.contains("pickup_items"));
        assertFalse(whitelist.contains("mine_material"));
        assertFalse(whitelist.contains("chop_tree"));
        assertFalse(whitelist.contains("craft_item"));
    }

    private record Stub(String id, RiskClass risk) implements ActionModule {

        @Override
        public ModuleSpec spec() {
            return new ModuleSpec(id, "", List.of());
        }

        @Override
        public ModuleResult check(ModuleContext context, ModuleParams params) {
            return ModuleResult.ok("ok", "ready");
        }

        @Override
        public ModuleExecution start(ModuleContext context, ModuleParams params) {
            return () -> ModuleResult.ok("done", "done");
        }
    }
}
