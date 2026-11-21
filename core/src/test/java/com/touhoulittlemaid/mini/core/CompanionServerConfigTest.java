package com.touhoulittlemaid.mini.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    void parsesTheShippedExample() throws IOException {
        Path example = shippedExamplePath();
        String text = Files.readString(example);
        CompanionServerConfig.ParseResult result = CompanionServerConfig.parse(text);
        assertFalse(result.hasProblems(), () -> result.problems().toString());
        // The example is the published template, not a free-form sample. Comparing it to
        // the code defaults makes doc drift a test failure instead of a silent mismatch
        // between what the operator reads and what the server actually does.
        assertEquals(CompanionServerConfig.defaults(), result.config());
    }

    private static Path shippedExamplePath() {
        Path fromRoot = Path.of("..", "config-examples", "touhou-little-maid-mini-server.toml");
        if (Files.exists(fromRoot)) {
            return fromRoot;
        }
        Path fromModule = Path.of("config-examples", "touhou-little-maid-mini-server.toml");
        assumeTrue(Files.exists(fromModule),
                "config-examples is not reachable from the test working directory");
        return fromModule;
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
                true, "10.0.0.5", 8765, 16, 16, 16, 60, false, LocalBehavior.STAY, 30, 30, 7, 16));
    }

    @Test
    void tomlRoundTripKeepsTheValues() {
        CompanionServerConfig original = new CompanionServerConfig(
                true, "localhost", 9000, 8, 32, 20, 120, true, LocalBehavior.GUARD, 45, 25, 3, 8);
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
    void parsesLimitsSafetyAndEnabledActions() {
        CompanionServerConfig.ParseResult result = CompanionServerConfig.parse("""
                config_version = 1
                [limits]
                max_concurrent_calls = 8
                max_calls_per_minute_per_token = 120
                max_failed_auth_per_minute_per_ip = 5
                default_tool_timeout_seconds = 45
                default_event_wait_seconds = 30

                [actions]
                enabled = ["observe", "move", "interact"]

                [safety]
                owner_confirmation_timeout_seconds = 90
                require_confirmation_for_other_players = false
                require_confirmation_for_named_mobs = false
                require_confirmation_for_villagers = true
                require_confirmation_for_other_containers = false
                """);
        assertFalse(result.hasProblems(), () -> result.problems().toString());
        assertFalse(result.migrated());
        CompanionServerConfig config = result.config();
        assertEquals(8, config.limits().maxConcurrentCalls());
        assertEquals(120, config.limits().maxCallsPerMinutePerToken());
        assertEquals(5, config.limits().maxFailedAuthPerMinutePerIp());
        assertEquals(45, config.limits().defaultToolTimeoutSeconds());
        assertEquals(30, config.limits().defaultEventWaitSeconds());
        assertEquals(90, config.safety().ownerConfirmationTimeoutSeconds());
        assertFalse(config.safety().requireConfirmationForOtherPlayers());
        assertTrue(config.safety().requireConfirmationForVillagers());
        assertEquals(java.util.Set.of(ActionKind.OBSERVE, ActionKind.MOVE,
                ActionKind.INTERACT), config.enabledActions());
    }

    @Test
    void missingVersionIsReportedAsMigrationAndWritesCurrentVersion() {
        CompanionServerConfig.ParseResult result =
                CompanionServerConfig.parse("port = 9000\n");
        assertTrue(result.migrated());
        assertFalse(result.hasProblems(), () -> result.problems().toString());
        assertTrue(result.config().toToml().contains(
                "config_version = " + CompanionServerConfig.CURRENT_CONFIG_VERSION));
    }

    @Test
    void invalidActionListFallsBackToAllActions() {
        CompanionServerConfig.ParseResult result = CompanionServerConfig.parse("""
                config_version = 1
                [actions]
                enabled = ["observe", "farm"]
                """);
        assertTrue(result.hasProblems());
        assertEquals(java.util.Set.copyOf(java.util.EnumSet.allOf(ActionKind.class)),
                result.config().enabledActions());
    }

    @Test
    void newerConfigVersionIsReportedButStillUsable() {
        CompanionServerConfig.ParseResult result =
                CompanionServerConfig.parse("config_version = 99\n");
        assertTrue(result.hasProblems());
        assertEquals(CompanionServerConfig.CURRENT_CONFIG_VERSION,
                result.config().configVersion());
    }

    @Test
    void radiusAndSafetyHelpersClampToConfiguredLimits() {
        CompanionServerConfig config = new CompanionServerConfig(
                true, "127.0.0.1", 8765, 16, 24, 12, 60, false, LocalBehavior.STAY, 30, 30, 7, 16);
        assertEquals(24, config.clampedRadius(200));
        assertEquals(1, config.clampedRadius(-5));
        assertTrue(config.isSafetyStopped(4.0F, 20.0F));
        assertFalse(config.isSafetyStopped(20.0F, 20.0F));
    }
}
