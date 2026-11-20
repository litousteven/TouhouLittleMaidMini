package com.touhoulittlemaid.mini.mcp;

import java.util.Objects;

public record McpServerConfig(String host, int port, String bearerToken) {

    public static final String LOOPBACK = "127.0.0.1";

    public McpServerConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(bearerToken, "bearerToken");
        if (!LOOPBACK.equals(host) && !"localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("M0 MCP endpoint must bind to a loopback address");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (bearerToken.isBlank()) {
            throw new IllegalArgumentException("bearerToken must not be blank");
        }
    }
}
