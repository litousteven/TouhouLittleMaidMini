package com.touhoulittlemaid.mini.mcp;

import java.util.Objects;

public record McpServerConfig(String host, int port, String bearerToken,
        int maxConcurrentCalls, int maxCallsPerMinutePerToken,
        int maxFailedAuthPerMinutePerIp, int defaultToolTimeoutSeconds,
        String serverVersion) {

    public static final String LOOPBACK = "127.0.0.1";

    public McpServerConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(bearerToken, "bearerToken");
        Objects.requireNonNull(serverVersion, "serverVersion");
        if (!LOOPBACK.equals(host) && !"localhost".equalsIgnoreCase(host)
                && !"::1".equals(host)) {
            throw new IllegalArgumentException("MCP endpoint must bind to a loopback address");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken must not be blank");
        }
        maxConcurrentCalls = clamp(maxConcurrentCalls, 1, 128, 4);
        maxCallsPerMinutePerToken = clamp(maxCallsPerMinutePerToken, 1, 10_000, 60);
        maxFailedAuthPerMinutePerIp = clamp(maxFailedAuthPerMinutePerIp, 1, 10_000, 10);
        defaultToolTimeoutSeconds = clamp(defaultToolTimeoutSeconds, 1, 600, 90);
        if (serverVersion.isBlank()) {
            serverVersion = "0.1.0-beta";
        }
    }

    /** M0/M2-compatible constructor with the release defaults. */
    public McpServerConfig(String host, int port, String bearerToken) {
        this(host, port, bearerToken, 4, 60, 10, 90, "0.1.0-beta");
    }

    private static int clamp(int value, int minimum, int maximum, int fallback) {
        if (value < minimum) {
            return Math.max(minimum, Math.min(maximum, fallback));
        }
        return Math.min(value, maximum);
    }
}
