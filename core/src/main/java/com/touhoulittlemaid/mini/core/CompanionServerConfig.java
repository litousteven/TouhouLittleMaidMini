package com.touhoulittlemaid.mini.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Minecraft-free server configuration for a companion.
 *
 * <p>This is a deliberately small TOML subset: flat {@code key = value} lines, section
 * headers and one flat string array for the enabled action families. The mod never needs
 * a full TOML parser, and keeping the parsing here lets configuration rules be unit
 * tested without starting a game.
 */
public record CompanionServerConfig(
        int configVersion,
        boolean mcpEnabled,
        String mcpHost,
        int mcpPort,
        int maxCompanions,
        int maxActionRadius,
        int defaultWorkRadius,
        int disconnectGraceSeconds,
        boolean continueOperationAfterDisconnect,
        LocalBehavior safeModeAfterDisconnect,
        int hungerDecaySeconds,
        int lowHealthPercent,
        int eventRetentionDays,
        int eventMaxFileMb,
        Limits limits,
        Safety safety,
        Set<ActionKind> enabledActions) {

    public static final int CURRENT_CONFIG_VERSION = 1;

    public static final String LOOPBACK_V4 = "127.0.0.1";
    public static final String LOOPBACK_V6 = "::1";
    public static final String LOOPBACK_NAME = "localhost";

    public static final int DEFAULT_PORT = 8765;
    public static final int MAX_RADIUS = 128;

    /** Bounds for the MCP transport. Values outside the range fall back to defaults. */
    public record Limits(int maxConcurrentCalls, int maxCallsPerMinutePerToken,
            int maxFailedAuthPerMinutePerIp, int defaultToolTimeoutSeconds,
            int defaultEventWaitSeconds) {

        public Limits {
            maxConcurrentCalls = clamp(maxConcurrentCalls, 1, 128, 4);
            maxCallsPerMinutePerToken = clamp(maxCallsPerMinutePerToken, 1, 10_000, 60);
            maxFailedAuthPerMinutePerIp = clamp(maxFailedAuthPerMinutePerIp, 1, 10_000, 10);
            defaultToolTimeoutSeconds = clamp(defaultToolTimeoutSeconds, 1, 600, 90);
            defaultEventWaitSeconds = clamp(defaultEventWaitSeconds, 1, 120, 50);
        }

        public static Limits defaults() {
            return new Limits(4, 60, 10, 90, 50);
        }
    }

    /** Human-authorization rules for destructive or third-party world changes. */
    public record Safety(int ownerConfirmationTimeoutSeconds,
            boolean requireConfirmationForOtherPlayers,
            boolean requireConfirmationForNamedMobs,
            boolean requireConfirmationForVillagers,
            boolean requireConfirmationForOtherContainers) {

        public Safety {
            ownerConfirmationTimeoutSeconds =
                    clamp(ownerConfirmationTimeoutSeconds, 5, 3600, 120);
        }

        public static Safety defaults() {
            return new Safety(120, true, true, true, true);
        }
    }

    public CompanionServerConfig {
        Objects.requireNonNull(mcpHost, "mcpHost");
        Objects.requireNonNull(safeModeAfterDisconnect, "safeModeAfterDisconnect");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(enabledActions, "enabledActions");
        configVersion = Math.max(1, configVersion);
        mcpHost = stripBrackets(mcpHost);
        if (!isLoopback(mcpHost)) {
            throw new IllegalArgumentException("MCP host must be a loopback address: " + mcpHost);
        }
        mcpPort = clamp(mcpPort, 1, 65535, DEFAULT_PORT);
        maxCompanions = clamp(maxCompanions, 1, 64, 16);
        maxActionRadius = clamp(maxActionRadius, 1, MAX_RADIUS, 16);
        defaultWorkRadius = clamp(defaultWorkRadius, 1, maxActionRadius, 16);
        disconnectGraceSeconds = clamp(disconnectGraceSeconds, 0, 3600, 60);
        hungerDecaySeconds = clamp(hungerDecaySeconds, 5, 600, 30);
        lowHealthPercent = clamp(lowHealthPercent, 5, 90, 30);
        eventRetentionDays = clamp(eventRetentionDays, 0, 365, 7);
        eventMaxFileMb = clamp(eventMaxFileMb, 1, 1024, 16);
        enabledActions = enabledActions.isEmpty()
                ? Set.copyOf(EnumSet.allOf(ActionKind.class))
                : Set.copyOf(enabledActions);
    }

    /** Backwards-compatible constructor used by callers that only set M1/M3 fields. */
    public CompanionServerConfig(boolean mcpEnabled, String mcpHost, int mcpPort,
            int maxCompanions, int maxActionRadius, int defaultWorkRadius,
            int disconnectGraceSeconds, boolean continueOperationAfterDisconnect,
            LocalBehavior safeModeAfterDisconnect, int hungerDecaySeconds,
            int lowHealthPercent, int eventRetentionDays, int eventMaxFileMb) {
        this(CURRENT_CONFIG_VERSION, mcpEnabled, mcpHost, mcpPort, maxCompanions,
                maxActionRadius, defaultWorkRadius, disconnectGraceSeconds,
                continueOperationAfterDisconnect, safeModeAfterDisconnect, hungerDecaySeconds,
                lowHealthPercent, eventRetentionDays, eventMaxFileMb,
                Limits.defaults(), Safety.defaults(), allActions());
    }

    public static CompanionServerConfig defaults() {
        return new CompanionServerConfig(CURRENT_CONFIG_VERSION, true, LOOPBACK_V4,
                DEFAULT_PORT, 16, 16, 16, 60, false, LocalBehavior.STAY, 30, 30, 7, 16,
                Limits.defaults(), Safety.defaults(), allActions());
    }

    /**
     * Reads the supported subset of the server TOML.
     *
     * <p>Unknown keys are ignored so a newer configuration file does not break an older
     * build. Invalid values keep the default and are reported instead of silently
     * producing an unsafe configuration. Missing keys are reported as a migration so
     * the loader can write the full current file back.
     */
    public static ParseResult parse(String text) {
        Objects.requireNonNull(text, "text");
        List<String> problems = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        String section = "";
        for (String rawLine : text.split("\\R")) {
            String line = stripComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).strip().toLowerCase(Locale.ROOT);
                continue;
            }
            int equals = line.indexOf('=');
            if (equals < 0) {
                problems.add("ignored malformed line: " + abbreviate(rawLine));
                continue;
            }
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) {
                problems.add("ignored line with an empty key");
                continue;
            }
            String value = line.substring(equals + 1).strip();
            values.put(section.isEmpty() ? key : section + "." + key, unquote(value));
        }

        boolean versionMissing = !values.containsKey("config_version");
        int parsedVersion = integer(values, "config_version", CURRENT_CONFIG_VERSION,
                1, 9999, problems);
        if (parsedVersion > CURRENT_CONFIG_VERSION) {
            problems.add("config_version " + parsedVersion + " is newer than "
                    + CURRENT_CONFIG_VERSION + "; newer keys are ignored");
        }

        boolean enabled = bool(values, "enabled", true, problems);
        String host = values.getOrDefault("host", LOOPBACK_V4);
        if (!isLoopback(stripBrackets(host))) {
            problems.add("host must be a loopback address; using " + LOOPBACK_V4);
            host = LOOPBACK_V4;
        }
        int port = integer(values, "port", DEFAULT_PORT, 1, 65535, problems);
        int maxCompanions = integer(values, "max_companions", 16, 1, 64, problems);
        int maxActionRadius = integer(values, "max_action_radius", 16, 1, MAX_RADIUS, problems);
        int defaultWorkRadius = integer(values, "default_work_radius",
                Math.min(16, maxActionRadius), 1, maxActionRadius, problems);
        int grace = integer(values, "disconnect_grace_seconds", 60, 0, 3600, problems);
        boolean continueAfterDisconnect =
                bool(values, "continue_operation_after_disconnect", false, problems);
        LocalBehavior safeMode = behavior(values, "safe_mode_after_disconnect",
                LocalBehavior.STAY, problems);
        int hungerDecay = integer(values, "hunger_decay_seconds", 30, 5, 600, problems);
        int lowHealth = integer(values, "low_health_percent", 30, 5, 90, problems);
        int retentionDays = integer(values, "event_retention_days", 7, 0, 365, problems);
        int eventMaxFileMb = integer(values, "event_max_file_mb", 16, 1, 1024, problems);

        Limits limits = new Limits(
                integer(values, "limits.max_concurrent_calls",
                        Limits.defaults().maxConcurrentCalls(), 1, 128, problems),
                integer(values, "limits.max_calls_per_minute_per_token",
                        Limits.defaults().maxCallsPerMinutePerToken(), 1, 10_000, problems),
                integer(values, "limits.max_failed_auth_per_minute_per_ip",
                        Limits.defaults().maxFailedAuthPerMinutePerIp(), 1, 10_000, problems),
                integer(values, "limits.default_tool_timeout_seconds",
                        Limits.defaults().defaultToolTimeoutSeconds(), 1, 600, problems),
                integer(values, "limits.default_event_wait_seconds",
                        Limits.defaults().defaultEventWaitSeconds(), 1, 120, problems));

        Safety safety = new Safety(
                integer(values, "safety.owner_confirmation_timeout_seconds",
                        Safety.defaults().ownerConfirmationTimeoutSeconds(), 5, 3600, problems),
                bool(values, "safety.require_confirmation_for_other_players",
                        Safety.defaults().requireConfirmationForOtherPlayers(), problems),
                bool(values, "safety.require_confirmation_for_named_mobs",
                        Safety.defaults().requireConfirmationForNamedMobs(), problems),
                bool(values, "safety.require_confirmation_for_villagers",
                        Safety.defaults().requireConfirmationForVillagers(), problems),
                bool(values, "safety.require_confirmation_for_other_containers",
                        Safety.defaults().requireConfirmationForOtherContainers(), problems));

        Set<ActionKind> actions = actions(values, "actions.enabled", problems);
        boolean migrated = versionMissing || parsedVersion < CURRENT_CONFIG_VERSION;

        CompanionServerConfig config = new CompanionServerConfig(CURRENT_CONFIG_VERSION,
                enabled, host, port, maxCompanions, maxActionRadius, defaultWorkRadius, grace,
                continueAfterDisconnect, safeMode, hungerDecay, lowHealth,
                retentionDays, eventMaxFileMb, limits, safety, actions);
        return new ParseResult(config, List.copyOf(problems), migrated);
    }

    public String toToml() {
        StringBuilder toml = new StringBuilder();
        toml.append("""
                # Touhou Little Maid Mini server configuration.
                # The MCP endpoint is loopback-only and never accepts a public bind address.
                config_version = %d
                enabled = %s
                host = "%s"
                port = %d
                max_companions = %d
                max_action_radius = %d
                default_work_radius = %d
                disconnect_grace_seconds = %d
                continue_operation_after_disconnect = %s
                safe_mode_after_disconnect = "%s"
                hunger_decay_seconds = %d
                low_health_percent = %d
                event_retention_days = %d
                event_max_file_mb = %d
                """.formatted(configVersion, mcpEnabled, mcpHost, mcpPort, maxCompanions,
                maxActionRadius, defaultWorkRadius, disconnectGraceSeconds,
                continueOperationAfterDisconnect, safeModeAfterDisconnect.id(),
                hungerDecaySeconds, lowHealthPercent, eventRetentionDays, eventMaxFileMb));
        toml.append("\n[limits]\n")
                .append("max_concurrent_calls = ").append(limits.maxConcurrentCalls()).append('\n')
                .append("max_calls_per_minute_per_token = ")
                .append(limits.maxCallsPerMinutePerToken()).append('\n')
                .append("max_failed_auth_per_minute_per_ip = ")
                .append(limits.maxFailedAuthPerMinutePerIp()).append('\n')
                .append("default_tool_timeout_seconds = ")
                .append(limits.defaultToolTimeoutSeconds()).append('\n')
                .append("default_event_wait_seconds = ")
                .append(limits.defaultEventWaitSeconds()).append("\n\n");
        toml.append("[actions]\n").append("enabled = [");
        boolean first = true;
        for (ActionKind kind : ActionKind.values()) {
            if (!enabledActions.contains(kind)) {
                continue;
            }
            if (!first) {
                toml.append(", ");
            }
            first = false;
            toml.append('"').append(kind.id()).append('"');
        }
        toml.append("]\n\n");
        toml.append("[safety]\n")
                .append("owner_confirmation_timeout_seconds = ")
                .append(safety.ownerConfirmationTimeoutSeconds()).append('\n')
                .append("require_confirmation_for_other_players = ")
                .append(safety.requireConfirmationForOtherPlayers()).append('\n')
                .append("require_confirmation_for_named_mobs = ")
                .append(safety.requireConfirmationForNamedMobs()).append('\n')
                .append("require_confirmation_for_villagers = ")
                .append(safety.requireConfirmationForVillagers()).append('\n')
                .append("require_confirmation_for_other_containers = ")
                .append(safety.requireConfirmationForOtherContainers()).append('\n');
        return toml.toString();
    }

    public CompanionServerConfig withDisconnectGraceSeconds(int graceSeconds) {
        return new CompanionServerConfig(configVersion, mcpEnabled, mcpHost, mcpPort,
                maxCompanions, maxActionRadius, defaultWorkRadius, graceSeconds,
                continueOperationAfterDisconnect, safeModeAfterDisconnect, hungerDecaySeconds,
                lowHealthPercent, eventRetentionDays, eventMaxFileMb, limits, safety,
                enabledActions);
    }

    public int clampedRadius(int requested) {
        return Math.max(1, Math.min(requested, maxActionRadius));
    }

    public boolean isSafetyStopped(float health, float maxHealth) {
        if (maxHealth <= 0.0F) {
            return false;
        }
        return health * 100.0F / maxHealth <= lowHealthPercent;
    }

    public static boolean isLoopback(String host) {
        return LOOPBACK_V4.equals(host) || LOOPBACK_V6.equals(host) || LOOPBACK_NAME.equals(host);
    }

    private static Set<ActionKind> allActions() {
        return Set.copyOf(EnumSet.allOf(ActionKind.class));
    }

    private static String stripBrackets(String host) {
        String trimmed = host.strip();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                quoted = !quoted;
            } else if (character == '#' && !quoted) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int integer(Map<String, String> values, String key, int fallback,
            int minimum, int maximum, List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.strip());
            if (parsed < minimum || parsed > maximum) {
                problems.add(key + " must be between " + minimum + " and " + maximum
                        + "; using " + fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException exception) {
            problems.add(key + " is not an integer; using " + fallback);
            return fallback;
        }
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback,
            List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        problems.add(key + " is not a boolean; using " + fallback);
        return fallback;
    }

    private static LocalBehavior behavior(Map<String, String> values, String key,
            LocalBehavior fallback, List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            return LocalBehavior.fromId(raw.strip().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            problems.add(key + " must be stay, follow or guard; using " + fallback.id());
            return fallback;
        }
    }

    private static Set<ActionKind> actions(Map<String, String> values, String key,
            List<String> problems) {
        String raw = values.get(key);
        if (raw == null) {
            return allActions();
        }
        String list = raw.strip();
        if (!list.startsWith("[") || !list.endsWith("]")) {
            problems.add(key + " must be a list of action ids; using all actions");
            return allActions();
        }
        String body = list.substring(1, list.length() - 1).strip();
        if (body.isEmpty()) {
            problems.add(key + " must not be empty; using all actions");
            return allActions();
        }
        Set<ActionKind> parsed = new LinkedHashSet<>();
        for (String entry : body.split(",")) {
            String value = unquote(entry.strip());
            if (value.isEmpty()) {
                continue;
            }
            try {
                parsed.add(ActionKind.fromId(value.toLowerCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                problems.add(key + " contains unknown action " + value + "; using all actions");
                return allActions();
            }
        }
        return parsed.isEmpty() ? allActions() : Set.copyOf(parsed);
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value < minimum) {
            return Math.max(minimum, Math.min(maximum, fallback));
        }
        return Math.min(value, maximum);
    }

    private static String abbreviate(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "...";
    }

    /** Parsed configuration plus non-fatal problems and whether a rewrite is needed. */
    public record ParseResult(CompanionServerConfig config, List<String> problems,
            boolean migrated) {

        public ParseResult {
            Objects.requireNonNull(config, "config");
            problems = List.copyOf(problems);
        }

        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        public Optional<CompanionServerConfig> configIfClean() {
            return problems.isEmpty() ? Optional.of(config) : Optional.empty();
        }
    }
}
