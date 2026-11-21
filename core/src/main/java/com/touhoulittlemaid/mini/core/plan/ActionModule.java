package com.touhoulittlemaid.mini.core.plan;

import java.util.List;

/**
 * A fixed, strongly typed capability the body can perform.
 *
 * <p>Modules are hand-written Java with unit tests and explicit failure codes. The model
 * only chooses them and fills parameters, so the set grows with new body abilities, never
 * with new crops or mods.
 */
public interface ActionModule {

    /** Stable id. Renaming one after 0.2 needs a plan schema bump. */
    String id();

    ModuleSpec spec();

    RiskClass risk();

    /** Rough cost used by the gate before anything runs. */
    default int estimatedActions(ModuleParams params) {
        return 1;
    }

    /** Rough duration used by the gate before anything runs. */
    default int estimatedTicks(ModuleParams params) {
        return 20;
    }

    /** Blocks this module can remove, used against the destroy budget. */
    default int destroyedBlocks(ModuleParams params) {
        return 0;
    }

    /**
     * Pre-flight refusal: missing tool, full inventory, unloaded target.
     *
     * <p>Return {@link ModuleResult#ok} to proceed. A failure here never starts the module,
     * which keeps "the plan was never possible" separate from "it failed halfway".
     */
    ModuleResult check(ModuleContext context, ModuleParams params);

    ModuleExecution start(ModuleContext context, ModuleParams params);

    /** Output keys this module can bind for later steps. */
    default List<String> outputs() {
        return List.of();
    }
}
