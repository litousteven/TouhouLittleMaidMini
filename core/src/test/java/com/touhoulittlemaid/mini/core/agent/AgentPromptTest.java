package com.touhoulittlemaid.mini.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.touhoulittlemaid.mini.core.autonomy.Opportunity;
import com.touhoulittlemaid.mini.core.plan.ActionModule;
import com.touhoulittlemaid.mini.core.plan.ModuleContext;
import com.touhoulittlemaid.mini.core.plan.ModuleExecution;
import com.touhoulittlemaid.mini.core.plan.ModuleParams;
import com.touhoulittlemaid.mini.core.plan.ModuleRegistry;
import com.touhoulittlemaid.mini.core.plan.ModuleResult;
import com.touhoulittlemaid.mini.core.plan.ModuleSpec;
import com.touhoulittlemaid.mini.core.plan.ParamSpec;
import com.touhoulittlemaid.mini.core.plan.RiskClass;

import java.util.List;

import org.junit.jupiter.api.Test;

class AgentPromptTest {

    @Test
    void catalogIsOneLinePerModuleWithParamHints() {
        ModuleRegistry registry = ModuleRegistry.builder().register(new Harvest()).build();
        String catalog = AgentPrompt.catalog(registry);
        assertTrue(catalog.startsWith("- harvest_area"), catalog);
        assertTrue(catalog.contains("(destructive)"), catalog);
        assertTrue(catalog.contains("radius:int=8"), catalog);
        assertTrue(catalog.contains("replant:bool=true"), catalog);
        assertFalse(catalog.contains("\n\n"), "one line per module");
    }

    @Test
    void systemPromptCarriesTheContractAndEveryModule() {
        ModuleRegistry registry = ModuleRegistry.builder()
                .register(new Harvest())
                .register(new Say())
                .build();
        String system = AgentPrompt.system("阿求", registry);
        assertTrue(system.contains("阿求"));
        assertTrue(system.contains("{\"say\":"));
        assertTrue(system.contains("harvest_area"));
        assertTrue(system.contains("[text:str!]"), system);
        assertTrue(system.contains("plan:null"));
        // The example must itself be a valid shape, not a placeholder.
        assertTrue(system.contains("\"version\":1"));
    }

    @Test
    void describeListsRangesAndAllowedValues() {
        ModuleRegistry registry = ModuleRegistry.builder().register(new Harvest()).build();
        String text = AgentPrompt.describe(registry.require("harvest_area").spec(),
                RiskClass.DESTROY);
        assertTrue(text.contains("harvest_area"));
        assertTrue(text.contains("range=1..16"), text);
        assertTrue(text.contains("destructive"));
    }

    @Test
    void userMessageKeepsObservationAndRequestApart() {
        String text = AgentPrompt.user("{\"health\":20}", "把麦子收了");
        assertTrue(text.indexOf("{\"health\":20}") < text.indexOf("把麦子收了"));
        assertTrue(AgentPrompt.user(null, null).contains("{}"));
    }

    @Test
    void autonomyUserIsAChoiceAmongOfferedCandidatesOnly() {
        List<Opportunity> candidates = List.of(
                Opportunity.of("harvest_farm", "23 株小麦熟了", 2),
                Opportunity.of("tidy_chests", "3 个杂箱", 3));
        String auto = AgentPrompt.autonomyUser("{\"health\":20}", candidates, true);
        assertTrue(auto.contains("harvest_farm"), auto);
        assertTrue(auto.contains("tidy_chests"), auto);
        assertTrue(auto.contains("harvest_area"), auto);
        assertTrue(auto.contains("\"none\""), auto);
        assertTrue(auto.contains("never invent a task"), auto);
        assertTrue(auto.contains("you may start it yourself"), auto);
        assertFalse(auto.contains("mine_material"), auto);

        String suggest = AgentPrompt.autonomyUser("{}", candidates, false);
        assertTrue(suggest.contains("the owner will be asked to confirm"), suggest);
        assertFalse(suggest.contains("you may start it yourself"), suggest);
    }

    private static final class Harvest implements ActionModule {

        @Override
        public String id() {
            return "harvest_area";
        }

        @Override
        public ModuleSpec spec() {
            return new ModuleSpec("harvest_area", "harvest and replant mature crops",
                    List.of(ParamSpec.intRange("radius", 1, 16, 8, "search radius"),
                            ParamSpec.bool("replant", true, "replant seeds")));
        }

        @Override
        public RiskClass risk() {
            return RiskClass.DESTROY;
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

    private static final class Say implements ActionModule {

        @Override
        public String id() {
            return "say";
        }

        @Override
        public ModuleSpec spec() {
            return new ModuleSpec("say", "say one line",
                    List.of(ParamSpec.string("text", true, "line")));
        }

        @Override
        public RiskClass risk() {
            return RiskClass.OBSERVE;
        }

        @Override
        public ModuleResult check(ModuleContext context, ModuleParams params) {
            return ModuleResult.ok("ok", "ready");
        }

        @Override
        public ModuleExecution start(ModuleContext context, ModuleParams params) {
            return () -> ModuleResult.ok("said", "said");
        }
    }
}
