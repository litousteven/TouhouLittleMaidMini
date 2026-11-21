package com.touhoulittlemaid.mini.core.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Deterministic modules and contexts for the plan framework tests. */
final class TestModules {

    private TestModules() {
    }

    static ModuleSpec spec(String id) {
        return new ModuleSpec(id, "test module " + id, List.of(
                ParamSpec.intRange("tier", 0, 3, 0, "probe tier"),
                ParamSpec.string("label", false, "free label")));
    }

    static StubModule stub(String id, RiskClass risk) {
        return new StubModule(id, risk, 1, 1, 0);
    }

    static StubModule stub(String id, RiskClass risk, int actions, int ticks, int destroyed) {
        return new StubModule(id, risk, actions, ticks, destroyed);
    }

    /** Records calls and replays a scripted list of outcomes. */
    static final class StubModule implements ActionModule {

        private final String id;
        private final RiskClass risk;
        private final int actions;
        private final int ticks;
        private final int destroyed;
        private final ModuleSpec spec;

        final Deque<ModuleResult> scripted = new ArrayDeque<>();
        final List<ModuleParams> started = new ArrayList<>();
        int attempts;
        int checkCalls;
        ModuleResult checkResult;
        int ticksPerAttempt = 1;

        StubModule(String id, RiskClass risk, int actions, int ticks, int destroyed) {
            this.id = id;
            this.risk = risk;
            this.actions = actions;
            this.ticks = ticks;
            this.destroyed = destroyed;
            this.spec = TestModules.spec(id);
        }

        StubModule script(ModuleResult... results) {
            scripted.clear();
            for (ModuleResult result : results) {
                scripted.addLast(result);
            }
            return this;
        }

        StubModule checkFails(String code) {
            this.checkResult = ModuleResult.failed(code, "check refused");
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ModuleSpec spec() {
            return spec;
        }

        @Override
        public RiskClass risk() {
            return risk;
        }

        @Override
        public int estimatedActions(ModuleParams params) {
            return actions;
        }

        @Override
        public int estimatedTicks(ModuleParams params) {
            return ticks;
        }

        @Override
        public int destroyedBlocks(ModuleParams params) {
            return destroyed;
        }

        @Override
        public ModuleResult check(ModuleContext context, ModuleParams params) {
            checkCalls++;
            return checkResult == null ? ModuleResult.ok("ready", "") : checkResult;
        }

        @Override
        public ModuleExecution start(ModuleContext context, ModuleParams params) {
            attempts++;
            started.add(params);
            int remaining = Math.max(1, ticksPerAttempt);
            return new ModuleExecution() {
                private int left = remaining;

                @Override
                public ModuleResult tick() {
                    left--;
                    if (left > 0) {
                        return ModuleResult.running();
                    }
                    return scripted.isEmpty()
                            ? ModuleResult.ok(FailureCodes.OK, "step done")
                            : scripted.poll();
                }
            };
        }
    }

    /** Predicate answers keyed by predicate text, so tests state intent directly. */
    static final class FakePredicates implements PredicateContext {

        final java.util.Map<String, Boolean> answers = new java.util.HashMap<>();
        final java.util.Map<String, Integer> counts = new java.util.HashMap<>();

        @Override
        public boolean stepOk(String stepId) {
            return answers.getOrDefault("ok:" + stepId, false);
        }

        @Override
        public boolean stepFailed(String stepId) {
            return answers.getOrDefault("failed:" + stepId, false);
        }

        @Override
        public boolean boundEmpty(String name) {
            return answers.getOrDefault("empty:" + name, false);
        }

        @Override
        public int boundCount(String name) {
            return counts.getOrDefault("bound:" + name, 0);
        }

        @Override
        public int inventoryCount(String item) {
            return counts.getOrDefault("inv:" + item, 0);
        }

        @Override
        public int inventorySpace() {
            return counts.getOrDefault("space", 0);
        }

        @Override
        public int hostileCount(int radius) {
            return counts.getOrDefault("hostiles:" + radius, 0);
        }

        @Override
        public double ownerDistance() {
            return counts.getOrDefault("owner", 0);
        }

        @Override
        public boolean isDay() {
            return answers.getOrDefault("day", false);
        }

        @Override
        public boolean isRain() {
            return answers.getOrDefault("rain", false);
        }
    }

    static final ModuleContext CONTEXT = () -> "test-companion";
}
