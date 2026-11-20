package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.touhoulittlemaid.mini.core.CompanionSnapshot;
import com.touhoulittlemaid.mini.core.LocalBehavior;
import com.touhoulittlemaid.mini.core.ToolResult;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpServerTest {

    private static final String TOKEN = "m2-test-token";
    private static final UUID COMPANION = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private McpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void initializesAndListsTheM2ToolSurface() throws Exception {
        server = start();
        URI endpoint = endpoint();

        JsonObject initialized = call(endpoint, request(1, "initialize", new JsonObject()));
        assertEquals(McpServer.PROTOCOL_VERSION,
                initialized.getAsJsonObject("result").get("protocolVersion").getAsString());

        JsonObject tools = call(endpoint, request(2, "tools/list", new JsonObject()));
        JsonArray list = tools.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(8, list.size());
        assertTrue(list.size() <= 10, "MCP tool budget is ten tools");

        JsonObject first = list.get(0).getAsJsonObject();
        assertEquals("companion_list", first.get("name").getAsString());
        assertTrue(first.getAsJsonObject("inputSchema").has("properties"));
        assertTrue(first.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
    }

    @Test
    void toolSchemaExposesRequiredParameters() throws Exception {
        server = start();
        JsonObject tools = call(endpoint(), request(2, "tools/list", new JsonObject()));
        JsonArray list = tools.getAsJsonObject("result").getAsJsonArray("tools");

        JsonObject move = null;
        for (var element : list) {
            if ("companion_move".equals(element.getAsJsonObject().get("name").getAsString())) {
                move = element.getAsJsonObject();
            }
        }
        assertTrue(move != null, "companion_move must be listed");

        JsonObject schema = move.getAsJsonObject("inputSchema");
        JsonArray required = schema.getAsJsonArray("required");
        assertEquals(4, required.size());
        assertEquals("integer", schema.getAsJsonObject("properties")
                .getAsJsonObject("x").get("type").getAsString());
    }

    @Test
    void validCallReturnsStructuredResult() throws Exception {
        server = start();
        JsonObject params = new JsonObject();
        params.addProperty("name", "companion_list");
        params.add("arguments", new JsonObject());

        JsonObject response = call(endpoint(), request(3, "tools/call", params));
        JsonObject result = response.getAsJsonObject("result");
        assertEquals(false, result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString().contains("Alice"));
    }

    @Test
    void invalidArgumentsBecomeErrorResults() throws Exception {
        server = start();
        JsonObject params = new JsonObject();
        params.addProperty("name", "companion_move");
        params.add("arguments", new JsonObject());

        JsonObject response = call(endpoint(), request(4, "tools/call", params));
        JsonObject result = response.getAsJsonObject("result");
        assertEquals(true, result.get("isError").getAsBoolean());
        assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString().contains("invalid_params"));
    }

    @Test
    void jsonIntegersPassTypeValidation() throws Exception {
        server = start();
        JsonObject params = new JsonObject();
        params.addProperty("name", "companion_move");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("companion_id", COMPANION.toString());
        arguments.addProperty("x", 1);
        arguments.addProperty("y", 64);
        arguments.addProperty("z", -3);
        params.add("arguments", arguments);

        JsonObject response = call(endpoint(), request(7, "tools/call", params));
        String text = response.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(!text.contains("invalid_params"),
                "JSON integers must not be rejected as the wrong type: " + text);
    }

    @Test
    void unknownToolAndMethodAreDistinguished() throws Exception {
        server = start();

        JsonObject toolParams = new JsonObject();
        toolParams.addProperty("name", "companion_do_everything");
        toolParams.add("arguments", new JsonObject());
        JsonObject toolResponse = call(endpoint(), request(5, "tools/call", toolParams));
        assertTrue(toolResponse.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString().contains("unknown_tool"));

        JsonObject methodResponse = call(endpoint(), request(6, "bogus/method", new JsonObject()));
        assertEquals(-32601, methodResponse.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void rejectsMissingAndWrongTokensWithoutStartingWork() throws Exception {
        server = start();
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        request(1, "ping", new JsonObject()).toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> missing = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, missing.statusCode());

        HttpResponse<String> wrong = http.send(HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer wrong")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        request(1, "ping", new JsonObject()).toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
    }

    @Test
    void closeReleasesTheListeningPort() throws Exception {
        server = start();
        int port = server.port();
        server.close();
        server = null;

        try (ServerSocket rebound = new ServerSocket(port, 1,
                java.net.InetAddress.getByName(McpServerConfig.LOOPBACK))) {
            assertTrue(rebound.isBound());
        }
    }

    @Test
    void configRejectsNonLoopbackBinding() {
        assertThrows(IllegalArgumentException.class,
                () -> new McpServerConfig("0.0.0.0", 8765, TOKEN));
    }

    private McpServer start() throws Exception {
        McpServer started = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN),
                CompanionTools.registry(new StubBackend()));
        started.start();
        assertTrue(started.isRunning());
        return started;
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.port() + McpServer.PATH);
    }

    private JsonObject call(URI endpoint, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject request(int id, String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params);
        return request;
    }

    private static final class StubBackend implements CompanionTools.CompanionBackend {

        @Override
        public List<CompanionSnapshot> listCompanions() {
            return List.of(new CompanionSnapshot(COMPANION, Optional.empty(), "Alice",
                    LocalBehavior.STAY, false));
        }

        @Override
        public CompanionSnapshot state(UUID companionId) {
            return new CompanionSnapshot(companionId, Optional.empty(), "Alice",
                    LocalBehavior.STAY, false);
        }

        @Override
        public ToolResult move(UUID companionId, int x, int y, int z, double speed) {
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
            return ToolResult.ok("behavior_set", behavior.id());
        }

        @Override
        public ToolResult stop(UUID companionId) {
            return ToolResult.ok("stopped", "stopped");
        }

        @Override
        public ToolResult wait(UUID companionId, int ticks) {
            return ToolResult.ok("waited", "waited");
        }
    }
}
