package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.core.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CompanionToolsTest {

    private static final UUID COMPANION = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** Records calls so each test can assert what reached the body. */
    private static final class RecordingBackend implements CompanionTools.CompanionBackend {
        private final AtomicInteger waits = new AtomicInteger();
        private LocalBehavior lastBehavior;
        private int[] lastMove;
        private UUID lastStop;

        @Override
        public List<CompanionSnapshot> listCompanions() {
            return List.of(new CompanionSnapshot(COMPANION, Optional.empty(), "Alice",
                    LocalBehavior.STAY, true));
        }

        @Override
        public CompanionSnapshot state(UUID companionId) {
            if (!COMPANION.equals(companionId)) {
                throw new IllegalStateException("Unknown companion: " + companionId);
            }
            return new CompanionSnapshot(companionId, Optional.empty(), "Alice",
                    LocalBehavior.STAY, true);
        }

        @Override
        public ToolResult move(UUID companionId, int x, int y, int z, double speed) {
            lastMove = new int[] {x, y, z};
            return ToolResult.ok("moving", "moving");
        }

        @Override
        public ToolResult interact(UUID companionId, int x, int y, int z) {
            return ToolResult.ok("interacted", "interacted");
        }

        @Override
        public ToolResult inventory(UUID companionId, int slot) {
            return ToolResult.ok("slot", "empty");
        }

        @Override
        public ToolResult setBehavior(UUID companionId, LocalBehavior behavior) {
            lastBehavior = behavior;
            return ToolResult.ok("behavior_set", behavior.id());
        }

        @Override
        public ToolResult stop(UUID companionId) {
            lastStop = companionId;
            return ToolResult.ok("stopped", "stopped");
        }

        @Override
        public ToolResult wait(UUID companionId, int ticks) {
            waits.addAndGet(ticks);
            return ToolResult.ok("waited", "waited " + ticks);
        }
    }

    private static ToolRegistry registry(RecordingBackend backend) {
        return CompanionTools.registry(backend);
    }

    @Test
    void toolSurfaceStaysWithinBudgetAndCoversEveryActionFamily() {
        ToolRegistry registry = registry(new RecordingBackend());
        assertTrue(registry.size() <= 10, "MCP tool budget is ten tools");
        assertEquals(8, registry.size());

        List<String> names = registry.tools().stream().map(ToolSpec::name).toList();
        assertEquals(List.of(
                "companion_list",
                "companion_state",
                "companion_move",
                "companion_interact",
                "companion_inventory",
                "companion_set_behavior",
                "companion_wait",
                "companion_stop"), names);

        assertEquals(6, CompanionTools.coveredActions().size());
    }

    @Test
    void readOnlyToolsAreAnnotatedAsSuch() {
        ToolRegistry registry = registry(new RecordingBackend());
        assertTrue(registry.spec("companion_list").orElseThrow().readOnly());
        assertTrue(registry.spec("companion_state").orElseThrow().readOnly());
        assertTrue(registry.spec("companion_inventory").orElseThrow().readOnly());
        assertTrue(registry.spec("companion_stop").orElseThrow().destructive());
        assertFalse(registry.spec("companion_move").orElseThrow().readOnly());
    }

    @Test
    void missingRequiredParameterIsRejectedBeforeReachingTheBody() {
        RecordingBackend backend = new RecordingBackend();
        ToolResult result = registry(backend).invoke("companion_wait", Map.of());

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals("invalid_params", result.code());
        assertEquals(0, backend.waits.get(), "the body must not run on invalid input");
    }

    @Test
    void wrongParameterTypeIsRejected() {
        ToolResult result = registry(new RecordingBackend())
                .invoke("companion_move", Map.of(
                        "companion_id", COMPANION.toString(),
                        "x", "left",
                        "y", 1,
                        "z", 2));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals("invalid_params", result.code());
    }

    @Test
    void unexpectedParameterIsRejected() {
        ToolResult result = registry(new RecordingBackend())
                .invoke("companion_list", Map.of("bogus", 1));

        assertEquals(ToolResult.Status.REJECTED, result.status());
    }

    @Test
    void numericMinimumIsEnforced() {
        ToolResult result = registry(new RecordingBackend())
                .invoke("companion_wait", Map.of(
                        "companion_id", COMPANION.toString(),
                        "ticks", 0));

        assertEquals(ToolResult.Status.REJECTED, result.status());
    }

    @Test
    void validCallReachesTheBody() {
        RecordingBackend backend = new RecordingBackend();
        ToolResult result = registry(backend).invoke("companion_wait", Map.of(
                "companion_id", COMPANION.toString(),
                "ticks", 20));

        assertEquals(ToolResult.Status.OK, result.status());
        assertEquals(20, backend.waits.get());
    }

    @Test
    void invalidBehaviorIsRejected() {
        RecordingBackend backend = new RecordingBackend();
        ToolResult result = registry(backend).invoke("companion_set_behavior", Map.of(
                "companion_id", COMPANION.toString(),
                "behavior", "mine"));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals(null, backend.lastBehavior);
    }

    @Test
    void validBehaviorReachesTheBody() {
        RecordingBackend backend = new RecordingBackend();
        registry(backend).invoke("companion_set_behavior", Map.of(
                "companion_id", COMPANION.toString(),
                "behavior", "guard"));

        assertEquals(LocalBehavior.GUARD, backend.lastBehavior);
    }

    @Test
    void unknownToolIsReportedAsSuch() {
        ToolResult result = registry(new RecordingBackend()).invoke("companion_delete_everything", Map.of());

        assertEquals(ToolResult.Status.UNKNOWN_TOOL, result.status());
    }

    @Test
    void unknownCompanionIsAStructuredRefusalNotAnInternalError() {
        ToolResult result = registry(new RecordingBackend()).invoke("companion_state", Map.of(
                "companion_id", "99999999-9999-9999-9999-999999999999"));

        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals("unknown_companion", result.code());
    }

    @Test
    void knownCompanionStateIsReturned() {
        ToolResult result = registry(new RecordingBackend()).invoke("companion_state", Map.of(
                "companion_id", COMPANION.toString()));

        assertEquals(ToolResult.Status.OK, result.status());
        assertTrue(result.message().contains("Alice"));
    }

    @Test
    void duplicateRegistrationIsRefused() {
        ToolRegistry registry = new ToolRegistry();
        ToolSpec spec = new ToolSpec("dup", "Dup", "Dup", Map.of(), true, false, true);
        registry.register(spec, arguments -> ToolResult.ok("ok", "ok"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.register(spec, arguments -> ToolResult.ok("ok", "ok")));
    }

    @Test
    void handlerFailureBecomesStructuredRejection() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ToolSpec("boom", "Boom", "Boom", Map.of(), false, false, false),
                arguments -> {
                    throw new IllegalStateException("no world");
                });

        ToolResult result = registry.invoke("boom", Map.of());
        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals("internal_error", result.code());
    }
}
