package com.touhoulittlemaid.mini.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.touhoulittlemaid.mini.core.ActionKind;
import com.touhoulittlemaid.mini.core.ServerMetrics;
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.core.ToolSpec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Minimal MCP 2025-11-25 Streamable HTTP endpoint used by the M0 probe.
 *
 * <p>The transport knows only the immutable MCP tools and a nullable Minecraft-free state snapshot.
 * It never receives Minecraft objects or invokes game code from an HTTP thread.
 */
public final class McpServer implements AutoCloseable {

    public static final String PATH = "/mcp";
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final int IDEMPOTENCY_CAPACITY = 256;

    private final McpServerConfig config;
    private final ToolRegistry tools;
    private final Gson gson;
    private final TokenAuthenticator authenticator;
    private final AuditLog.Sink auditSink;
    private final java.util.function.Supplier<ServerMetrics> diagnostics;

    /** Replays a repeated request_id so a retried call never runs the body twice. */
    private final Map<String, Map<String, JsonObject>> idempotency = new LinkedHashMap<>();

    private final Semaphore concurrentCalls;
    private final Map<String, RateWindow> tokenWindows = new LinkedHashMap<>();
    private final Map<String, RateWindow> failedAuthWindows = new LinkedHashMap<>();

    private HttpServer server;
    private ExecutorService executor;

    /** Small synchronized sliding window; one instance per token or remote address. */
    private static final class RateWindow {

        private final Deque<Long> timestamps = new ArrayDeque<>();

        synchronized boolean allow(int maxPerMinute) {
            if (isFull(maxPerMinute)) {
                return false;
            }
            timestamps.addLast(System.currentTimeMillis());
            return true;
        }

        synchronized boolean isFull(int maxPerMinute) {
            prune();
            return timestamps.size() >= maxPerMinute;
        }

        synchronized void record() {
            prune();
            timestamps.addLast(System.currentTimeMillis());
        }

        private void prune() {
            long cutoff = System.currentTimeMillis() - 60_000L;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
        }
    }

    public McpServer(McpServerConfig config, ToolRegistry tools) {
        this(config, tools, TokenScope.FULL, event -> { });
    }

    public McpServer(McpServerConfig config, ToolRegistry tools,
            TokenScope scope, AuditLog.Sink auditSink) {
        this(config, tools,
                TokenAuthenticator.staticToken(config.bearerToken(), scope), auditSink);
    }

    public McpServer(McpServerConfig config, ToolRegistry tools,
            TokenAuthenticator authenticator, AuditLog.Sink auditSink) {
        this(config, tools, authenticator, auditSink, ServerMetrics::empty);
    }

    public McpServer(McpServerConfig config, ToolRegistry tools,
            TokenAuthenticator authenticator, AuditLog.Sink auditSink,
            java.util.function.Supplier<ServerMetrics> diagnostics) {
        this.config = Objects.requireNonNull(config, "config");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.concurrentCalls = new Semaphore(config.maxConcurrentCalls(), true);
        this.gson = new Gson();
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("MCP server is already running");
        }

        HttpServer candidate = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 0);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        candidate.createContext(PATH, this::handle);
        candidate.setExecutor(pool);
        try {
            candidate.start();
        } catch (RuntimeException exception) {
            candidate.stop(0);
            pool.shutdownNow();
            throw exception;
        }
        server = candidate;
        executor = pool;
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("MCP server is not running");
        }
        return server.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        HttpServer running = server;
        server = null;
        if (running != null) {
            running.stop(0);
        }
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!PATH.equals(exchange.getRequestURI().getPath())) {
                sendJson(exchange, 404, error(null, -32600, "Not found"));
                return;
            }
            if (!allowedOrigin(exchange)) {
                sendJson(exchange, 403, error(null, -32003, "Origin not allowed"));
                return;
            }
            String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (authenticationBlocked(remote)) {
                sendJson(exchange, 429, error(null, -32002,
                        "Too many failed authentication attempts"));
                return;
            }
            Optional<TokenPrincipal> principal = authenticate(exchange);
            if (principal.isEmpty()) {
                recordAuthenticationFailure(remote);
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendJson(exchange, 401, error(null, -32001, "Invalid bearer token"));
                return;
            }
            clearAuthenticationFailures(remote);
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendJson(exchange, 405, error(null, -32600, "MCP Streamable HTTP endpoint expects POST"));
                return;
            }
            handlePost(exchange, principal.get());
        } catch (JsonParseException exception) {
            sendJson(exchange, 400, error(null, -32700, "Parse error"));
        } catch (RuntimeException exception) {
            sendJson(exchange, 500, error(null, -32603, "Internal error"));
        }
    }

    private void handlePost(HttpExchange exchange, TokenPrincipal principal) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            sendJson(exchange, 413, error(null, -32600, "Request body too large"));
            return;
        }

        JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) {
            sendJson(exchange, 400, error(null, -32600, "Invalid Request"));
            return;
        }
        JsonObject request = parsed.getAsJsonObject();
        JsonElement id = request.get("id");
        if (id == null || id.isJsonNull()) {
            exchange.sendResponseHeaders(202, -1);
            return;
        }

        JsonElement methodElement = request.get("method");
        if (methodElement == null || !methodElement.isJsonPrimitive()) {
            sendJson(exchange, 200, error(id, -32600, "Invalid Request"));
            return;
        }

        String method = methodElement.getAsString();
        JsonElement result = switch (method) {
            case "initialize" -> initializeResult();
            case "ping" -> pingResult();
            case "tools/list" -> toolsListResult();
            case "tools/call" -> callTool(request.get("params"), principal);
            default -> null;
        };
        if (result == null) {
            sendJson(exchange, 200, error(id, -32601, "Method not found"));
            return;
        }

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        sendJson(exchange, 200, response);
    }

    private JsonObject pingResult() {
        JsonObject result = new JsonObject();
        try {
            ServerMetrics metrics = diagnostics.get();
            result.addProperty("companion_count", metrics.companionCount());
            result.addProperty("mspt_average", round(metrics.msptAverage()));
            result.addProperty("mspt_max", round(metrics.msptMax()));
            result.addProperty("heap_used_mb", metrics.heapUsedMb());
            result.addProperty("heap_max_mb", metrics.heapMaxMb());
            result.addProperty("thread_count", metrics.threadCount());
            result.addProperty("event_sequence", metrics.lastEventSequence());
            result.addProperty("oldest_event_sequence", metrics.oldestEventSequence());
            result.addProperty("retained_events", metrics.retainedEventCount());
            result.addProperty("mcp_connected", metrics.mcpConnected());
        } catch (RuntimeException exception) {
            result.addProperty("diagnostics", "unavailable");
        }
        return result;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private JsonObject initializeResult() {
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "touhou-little-maid-mini");
        serverInfo.addProperty("title", "Touhou Little Maid Mini");
        serverInfo.addProperty("version", config.serverVersion());

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "Touhou Little Maid Mini companion endpoint. Tools observe and drive one "
                        + "companion through generic move, interact, inventory and wait actions. "
                        + "World changes run on the server thread; destructive actions need "
                        + "in-game owner approval.");
        return result;
    }

    private JsonObject toolsListResult() {
        JsonArray list = new JsonArray();
        for (ToolSpec spec : tools.tools()) {
            list.add(toolJson(spec));
        }

        JsonObject result = new JsonObject();
        result.add("tools", list);
        return result;
    }

    private JsonObject toolJson(ToolSpec spec) {
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Map.Entry<String, ToolSpec.ToolParameter> entry : spec.parameters().entrySet()) {
            ToolSpec.ToolParameter parameter = entry.getValue();
            JsonObject property = new JsonObject();
            property.addProperty("type", parameter.type().jsonName());
            property.addProperty("description", parameter.description());
            if (parameter.numericMinimum() != null) {
                property.addProperty("minimum", parameter.numericMinimum());
            }
            if (parameter.type() == ToolSpec.ParameterType.STRING_ARRAY) {
                JsonObject items = new JsonObject();
                items.addProperty("type", ToolSpec.ParameterType.STRING.jsonName());
                property.add("items", items);
            }
            properties.add(entry.getKey(), property);
            if (parameter.required()) {
                required.add(entry.getKey());
            }
        }

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        schema.addProperty("additionalProperties", false);

        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", spec.readOnly());
        annotations.addProperty("destructiveHint", spec.destructive());
        annotations.addProperty("idempotentHint", spec.idempotent());
        annotations.addProperty("openWorldHint", false);

        JsonObject tool = new JsonObject();
        tool.addProperty("name", spec.name());
        tool.addProperty("title", spec.title());
        tool.addProperty("description", spec.description());
        tool.add("inputSchema", schema);
        tool.add("annotations", annotations);
        return tool;
    }

    private JsonObject callTool(JsonElement paramsElement, TokenPrincipal principal) {
        if (paramsElement == null || !paramsElement.isJsonObject()) {
            return errorResult(ToolResult.rejected("invalid_params",
                    "tools/call requires an object"));
        }
        JsonObject params = paramsElement.getAsJsonObject();
        JsonElement nameElement = params.get("name");
        if (nameElement == null || !nameElement.isJsonPrimitive()) {
            return errorResult(ToolResult.rejected("invalid_params",
                    "tools/call requires a tool name"));
        }

        Map<String, Object> arguments = decodeArguments(params.get("arguments"));
        String requestId = requestId(params);
        String toolName = nameElement.getAsString();

        if (requestId != null) {
            JsonObject cached = replayOf(requestId, toolName);
            if (cached != null) {
                return cached;
            }
        }

        long startedAt = System.nanoTime();
        ToolResult result;
        if (!allowTokenCall(principal.digestId())) {
            result = ToolResult.rejected("rate_limited",
                    "Token exceeded " + config.maxCallsPerMinutePerToken()
                            + " calls per minute");
        } else if (!concurrentCalls.tryAcquire()) {
            result = ToolResult.rejected("rate_limited",
                    "Server is at its concurrent call limit; retry shortly");
        } else {
            try {
                result = scopeCheck(toolName, principal.scope())
                        .orElseGet(() -> CompanionTools.withAllowedCompanions(
                                principal.companionIds(),
                                () -> tools.invoke(toolName, arguments)));
            } finally {
                concurrentCalls.release();
            }
        }
        JsonObject response = errorResult(result);

        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        auditSink.record(new AuditLog.AuditEvent(
                requestId,
                principal.digestId(),
                toolName,
                AuditLog.companionId(arguments).orElse(null),
                result.status().name(),
                result.code(),
                durationMillis));

        if (requestId != null) {
            remember(requestId, toolName, response);
        }
        return response;
    }

    /** Returns a refusal when the token may not run this tool, or empty to proceed. */
    private Optional<ToolResult> scopeCheck(String toolName, TokenScope scope) {
        ActionKind kind;
        try {
            kind = TokenScope.actionOf(toolName);
        } catch (IllegalArgumentException exception) {
            return Optional.of(ToolResult.unknownTool(toolName));
        }
        if (!scope.allows(kind)) {
            return Optional.of(ToolResult.rejected("scope_denied",
                    "This token may not use " + toolName));
        }
        if (kind == ActionKind.INTERACT && !scope.canInteractWithWorld()) {
            return Optional.of(ToolResult.rejected("scope_denied",
                    "This token may not interact with the world"));
        }
        return Optional.empty();
    }

    /** Optional client-supplied idempotency key. */
    private static String requestId(JsonObject params) {
        JsonElement requestId = params.get("request_id");
        if (requestId == null || !requestId.isJsonPrimitive()) {
            return null;
        }
        String value = requestId.getAsString();
        return value.isBlank() ? null : value;
    }

    private JsonObject replayOf(String requestId, String toolName) {
        synchronized (idempotency) {
            Map<String, JsonObject> byTool = idempotency.get(requestId);
            if (byTool == null) {
                return null;
            }
            JsonObject cached = byTool.get(toolName);
            return cached == null ? null : cached.deepCopy();
        }
    }

    private void remember(String requestId, String toolName, JsonObject response) {
        synchronized (idempotency) {
            if (idempotency.size() >= IDEMPOTENCY_CAPACITY) {
                idempotency.clear();
            }
            idempotency.computeIfAbsent(requestId, key -> new LinkedHashMap<>())
                    .put(toolName, response.deepCopy());
        }
    }

    private static Map<String, Object> decodeArguments(JsonElement argumentsElement) {
        if (argumentsElement == null || !argumentsElement.isJsonObject()) {
            return Map.of();
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : argumentsElement.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                var primitive = value.getAsJsonPrimitive();
                if (primitive.isBoolean()) {
                    arguments.put(entry.getKey(), primitive.getAsBoolean());
                } else if (primitive.isNumber()) {
                    arguments.put(entry.getKey(), decodeNumber(primitive));
                } else {
                    arguments.put(entry.getKey(), primitive.getAsString());
                }
            } else if (value.isJsonArray()) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element != null && element.isJsonPrimitive()) {
                        var primitive = element.getAsJsonPrimitive();
                        if (primitive.isBoolean()) {
                            list.add(primitive.getAsBoolean());
                        } else if (primitive.isNumber()) {
                            list.add(decodeNumber(primitive));
                        } else {
                            list.add(primitive.getAsString());
                        }
                    }
                }
                arguments.put(entry.getKey(), list);
            } else {
                arguments.put(entry.getKey(), value.toString());
            }
        }
        return arguments;
    }

    /**
     * Gson hands back {@code LazilyParsedNumber}, which is neither Integer nor Long.
     * Normalise to the widest integer type or to Double so validation can match types.
     */
    private static Object decodeNumber(com.google.gson.JsonPrimitive primitive) {
        String raw = primitive.getAsString();
        if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ignored) {
                return primitive.getAsDouble();
            }
        }
        return primitive.getAsDouble();
    }

    private JsonObject errorResult(ToolResult result) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", result.status() == ToolResult.Status.OK
                ? result.message()
                : result.code() + ": " + result.message());
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject response = new JsonObject();
        response.add("content", contents);
        response.addProperty("isError", result.isError());
        return response;
    }

    private JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("error", error);
        return response;
    }

    /** Browser requests are accepted only from a loopback origin; CLI clients send none. */
    private static boolean allowedOrigin(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank() || "null".equalsIgnoreCase(origin)) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            if (scheme == null
                    || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                return false;
            }
            String host = uri.getHost();
            return host != null && (McpServerConfig.LOOPBACK.equals(host)
                    || "localhost".equalsIgnoreCase(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean allowTokenCall(String digestId) {
        synchronized (tokenWindows) {
            return tokenWindows.computeIfAbsent(digestId, ignored -> new RateWindow())
                    .allow(config.maxCallsPerMinutePerToken());
        }
    }

    private boolean authenticationBlocked(String remote) {
        synchronized (failedAuthWindows) {
            RateWindow window = failedAuthWindows.get(remote);
            return window != null
                    && window.isFull(config.maxFailedAuthPerMinutePerIp());
        }
    }

    private void recordAuthenticationFailure(String remote) {
        synchronized (failedAuthWindows) {
            failedAuthWindows.computeIfAbsent(remote, ignored -> new RateWindow()).record();
        }
    }

    private void clearAuthenticationFailures(String remote) {
        synchronized (failedAuthWindows) {
            failedAuthWindows.remove(remote);
        }
    }

    private Optional<TokenPrincipal> authenticate(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            return Optional.empty();
        }
        return authenticator.authenticate(authorization.substring(prefix.length()));
    }

    private void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

}
