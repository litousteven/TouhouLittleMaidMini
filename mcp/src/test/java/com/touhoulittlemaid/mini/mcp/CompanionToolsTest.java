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
        private String said;
        private com.touhoulittlemaid.mini.core.ContextQuery lastContext;

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
        public ToolResult context(UUID companionId,
                com.touhoulittlemaid.mini.core.ContextQuery query) {
            lastContext = query;
            return ToolResult.ok("context", "{\"scanned\":1,\"blocks\":{}}");
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
        public ToolResult waitEvents(UUID companionId, long afterSequence,
                int timeoutSeconds, int maxEvents) {
            waits.addAndGet(1);
            return ToolResult.ok("events",
                    "{\"events\":[],\"next_seq\":" + afterSequence + ",\"timed_out\":true}");
        }

        @Override
        public ToolResult say(UUID companionId, String text) {
            said = text;
            return ToolResult.ok("said", "said");
        }
    }

    private static ToolRegistry registry(RecordingBackend backend) {
        return CompanionTools.registry(backend);
    }

    @Test
    void toolSurfaceStaysWithinBudgetAndCoversEveryActionFamily() {
        ToolRegistry registry = registry(new RecordingBackend());
        assertTrue(registry.size() <= 10, "MCP tool budget is ten tools");
        assertEquals(10, registry.size());

        List<String> names = registry.tools().stream().map(ToolSpec::name).toList();
        assertEquals(List.of(
                "companion_list",
                "companion_state",
                "companion_move",
                "companion_interact",
                "companion_context",
                "companion_inventory",
                "companion_set_behavior",
                "companion_wait_events",
                "companion_say",
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
        ToolResult result = registry(backend).invoke("companion_wait_events", Map.of());

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
                .invoke("companion_wait_events", Map.of(
                        "companion_id", COMPANION.toString(),
                        "after_seq", -1));

        assertEquals(ToolResult.Status.REJECTED, result.status());
    }

    @Test
    void validCallReachesTheBody() {
        RecordingBackend backend = new RecordingBackend();
        ToolResult result = registry(backend).invoke("companion_wait_events", Map.of(
                "companion_id", COMPANION.toString(),
                "after_seq", 7));

        assertEquals(ToolResult.Status.OK, result.status());
        assertEquals(1, backend.waits.get());
    }

    @Test
    void sayRejectsBlankAndOverlongText() {
        RecordingBackend backend = new RecordingBackend();
        ToolRegistry registry = registry(backend);

        ToolResult blank = registry.invoke("companion_say", Map.of(
                "companion_id", COMPANION.toString(),
                "text", "   "));
        assertEquals(ToolResult.Status.REJECTED, blank.status());

        ToolResult tooLong = registry.invoke("companion_say", Map.of(
                "companion_id", COMPANION.toString(),
                "text", "x".repeat(281)));
        assertEquals(ToolResult.Status.REJECTED, tooLong.status());
        assertEquals(null, backend.said);
    }

    @Test
    void sayAcceptsAShortLine() {
        RecordingBackend backend = new RecordingBackend();
        ToolResult result = registry(backend).invoke("companion_say", Map.of(
                "companion_id", COMPANION.toString(),
                "text", "Wheat harvested."));

        assertEquals(ToolResult.Status.OK, result.status());
        assertEquals("Wheat harvested.", backend.said);
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
    void contextFiltersAndCategoryReachTheBody() {
        RecordingBackend backend = new RecordingBackend();
        ToolResult result = registry(backend).invoke("companion_context", Map.of(
                "companion_id", COMPANION.toString(),
                "category", "blocks",
                "radius", 12,
                "tags", List.of("minecraft:crops"),
                "block_ids", List.of("minecraft:wheat", "minecraft:carrots"),
                "max_results", 24));

        assertEquals(ToolResult.Status.OK, result.status());
        var query = backend.lastContext;
        assertEquals(com.touhoulittlemaid.mini.core.ContextCategory.BLOCKS, query.category());
        assertEquals(12, query.radius());
        assertEquals(List.of("minecraft:crops"), query.tags());
        assertEquals(List.of("minecraft:wheat", "minecraft:carrots"), query.blockIds());
        assertEquals(24, query.maxResults());
    }

    @Test
    void contextDefaultsToBlocksAndRejectsUnknownCategory() {
        RecordingBackend backend = new RecordingBackend();
        ToolRegistry registry = registry(backend);
        registry.invoke("companion_context", Map.of("companion_id", COMPANION.toString()));
        assertEquals(com.touhoulittlemaid.mini.core.ContextCategory.BLOCKS,
                backend.lastContext.category());
        assertEquals(com.touhoulittlemaid.mini.core.ContextQuery.DEFAULT_RADIUS,
                backend.lastContext.radius());

        ToolResult invalid = registry.invoke("companion_context", Map.of(
                "companion_id", COMPANION.toString(),
                "category", "farm"));
        assertEquals(ToolResult.Status.REJECTED, invalid.status());
        assertEquals("invalid_params", invalid.code());
    }

    @Test
    void contextRejectsArrayParametersOfTheWrongElementType() {
        ToolResult result = registry(new RecordingBackend()).invoke("companion_context", Map.of(
                "companion_id", COMPANION.toString(),
                "tags", List.of(1, 2)));
        assertEquals(ToolResult.Status.REJECTED, result.status());
        assertEquals("invalid_params", result.code());
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
