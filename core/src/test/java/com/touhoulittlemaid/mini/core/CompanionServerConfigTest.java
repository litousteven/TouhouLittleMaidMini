package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompanionServerConfigTest {

    @Test
    void defaultsAreLoopbackAndSafe() {
        CompanionServerConfig config = CompanionServerConfig.defaults();
        assertTrue(config.mcpEnabled());
        assertEquals("127.0.0.1", config.mcpHost());
        assertEquals(8765, config.mcpPort());
        assertEquals(16, config.maxActionRadius());
        assertEquals(LocalBehavior.STAY, config.safeModeAfterDisconnect());
        assertFalse(config.continueOperationAfterDisconnect());
    }

    @Test
    void parsesTheShippedExample() {
        String example = """
                enabled = true
                host = "127.0.0.1"
                port = 8765
                require_token = true
                disconnect_grace_seconds = 60
                continue_operation_after_disconnect = false
                safe_mode_after_disconnect = "stay"
                max_companions = 16
                max_action_radius = 64
                event_retention_days = 7
                event_max_file_mb = 16

                [limits]
                max_concurrent_calls = 4
                default_event_wait_seconds = 50

                [safety]
                owner_confirmation_timeout_seconds = 120
                require_confirmation_for_other_players = true
                """;
        CompanionServerConfig.ParseResult result = CompanionServerConfig.parse(example);
        assertFalse(result.hasProblems(), () -> result.problems().toString());
        CompanionServerConfig config = result.config();
        assertEquals(64, config.maxActionRadius());
        assertEquals(60, config.disconnectGraceSeconds());
        assertEquals(LocalBehavior.STAY, config.safeModeAfterDisconnect());
        assertEquals(16, config.maxCompanions());
    }

    @Test
    void invalidValuesKeepDefaultsAndReportProblems() {
        CompanionServerConfig.ParseResult result = CompanionServerConfig.parse("""
                port = 70000
                max_action_radius = 1000
                disconnect_grace_seconds = -1
                safe_mode_after_disconnect = "dig"
                low_health_percent = 200
                hunger_decay_seconds = "often"
                """);
        assertTrue(result.hasProblems());
        CompanionServerConfig config = result.config();
        assertEquals(CompanionServerConfig.DEFAULT_PORT, config.mcpPort());
        assertEquals(16, config.maxActionRadius());
        assertEquals(60, config.disconnectGraceSeconds());
        assertEquals(LocalBehavior.STAY, config.safeModeAfterDisconnect());
        assertEquals(30, config.lowHealthPercent());
        assertEquals(30, config.hungerDecaySeconds());
        assertTrue(result.problems().size() >= 5, () -> result.problems().toString());
    }

    @Test
    void publicBindAddressIsRefused() {
        CompanionServerConfig.ParseResult result =
                CompanionServerConfig.parse("host = \"0.0.0.0\"\n");
        assertEquals("127.0.0.1", result.config().mcpHost());
        assertTrue(result.hasProblems());
    }

    @Test
    void loopbackVariantsAreAccepted() {
        assertEquals("::1", CompanionServerConfig.parse("host = \"::1\"\n").config().mcpHost());
        assertEquals("localhost",
                CompanionServerConfig.parse("host = \"localhost\"\n").config().mcpHost());
    }

    @Test
    void recordRejectsNonLoopbackDirectly() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionServerConfig(
                true, "10.0.0.5", 8765, 16, 16, 16, 60, false, LocalBehavior.STAY, 30, 30));
    }

    @Test
    void tomlRoundTripKeepsTheValues() {
        CompanionServerConfig original = new CompanionServerConfig(
                true, "localhost", 9000, 8, 32, 20, 120, true, LocalBehavior.GUARD, 45, 25);
        CompanionServerConfig.ParseResult parsed =
                CompanionServerConfig.parse(original.toToml());
        assertFalse(parsed.hasProblems(), () -> parsed.problems().toString());
        assertEquals(original, parsed.config());
    }

    @Test
    void commentsInsideQuotesAreKept() {
        CompanionServerConfig.ParseResult result =
                CompanionServerConfig.parse("host = \"127.0.0.1\" # loopback only\n");
        assertFalse(result.hasProblems());
        assertEquals("127.0.0.1", result.config().mcpHost());
    }

    @Test
    void radiusAndSafetyHelpersClampToConfiguredLimits() {
        CompanionServerConfig config = new CompanionServerConfig(
                true, "127.0.0.1", 8765, 16, 24, 12, 60, false, LocalBehavior.STAY, 30, 30);
        assertEquals(24, config.clampedRadius(200));
        assertEquals(1, config.clampedRadius(-5));
        assertTrue(config.isSafetyStopped(4.0F, 20.0F));
        assertFalse(config.isSafetyStopped(20.0F, 20.0F));
    }
}
