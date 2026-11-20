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
import com.touhoulittlemaid.mini.core.ToolResult;
import com.touhoulittlemaid.mini.core.ToolSpec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final McpServerConfig config;
    private final ToolRegistry tools;
    private final Gson gson;
    private final byte[] tokenDigest;

    private HttpServer server;
    private ExecutorService executor;

    public McpServer(McpServerConfig config, ToolRegistry tools) {
        this.config = Objects.requireNonNull(config, "config");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.gson = new Gson();
        this.tokenDigest = sha256(config.bearerToken());
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
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                sendJson(exchange, 401, error(null, -32001, "Invalid bearer token"));
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                sendJson(exchange, 405, error(null, -32600, "MCP Streamable HTTP endpoint expects POST"));
                return;
            }
            handlePost(exchange);
        } catch (JsonParseException exception) {
            sendJson(exchange, 400, error(null, -32700, "Parse error"));
        } catch (RuntimeException exception) {
            sendJson(exchange, 500, error(null, -32603, "Internal error"));
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
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
            case "ping" -> new JsonObject();
            case "tools/list" -> toolsListResult();
            case "tools/call" -> callTool(request.get("params"));
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

    private JsonObject initializeResult() {
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "touhou-little-maid-mini");
        serverInfo.addProperty("title", "Touhou Little Maid Mini");
        serverInfo.addProperty("version", "0.1.0-alpha.1");

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "M0 diagnostics only. This endpoint exposes platform state; it cannot modify the world.");
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

    private JsonObject callTool(JsonElement paramsElement) {
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
        ToolResult result = tools.invoke(nameElement.getAsString(), arguments);
        return errorResult(result);
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

    private boolean authorized(HttpExchange exchange) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String prefix = "Bearer ";
        if (authorization == null || !authorization.startsWith(prefix)) {
            return false;
        }
        byte[] candidate = sha256(authorization.substring(prefix.length()));
        return MessageDigest.isEqual(tokenDigest, candidate);
    }

    private void sendJson(HttpExchange exchange, int status, JsonElement body) throws IOException {
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
