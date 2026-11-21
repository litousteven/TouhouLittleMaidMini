package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(10, list.size());
        assertTrue(list.size() <= 10, "MCP tool budget is ten tools");

        JsonObject first = list.get(0).getAsJsonObject();
        assertEquals("companion_list", first.get("name").getAsString());
        assertTrue(first.getAsJsonObject("inputSchema").has("properties"));
        assertTrue(first.getAsJsonObject("annotations").get("readOnlyHint").getAsBoolean());
    }

    @Test
    void disabledActionFamilyRemovesItsTools() {
        ToolRegistry registry = CompanionTools.registry(new StubBackend())
                .filter(spec -> TokenScope.actionOf(spec.name())
                        != com.touhoulittlemaid.mini.core.ActionKind.MOVE);
        assertTrue(registry.spec("companion_move").isEmpty());
        assertTrue(registry.spec("companion_set_behavior").isEmpty());
        assertTrue(registry.spec("companion_list").isPresent());
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
        assertEquals(1, required.size());
        assertEquals("companion_id", required.get(0).getAsString());
        assertEquals("integer", schema.getAsJsonObject("properties")
                .getAsJsonObject("x").get("type").getAsString());
        assertEquals("boolean", schema.getAsJsonObject("properties")
                .getAsJsonObject("replace").get("type").getAsString());
    }

    @Test
    void contextSchemaExposesArrayFiltersAndOptionalRadius() throws Exception {
        server = start();
        JsonObject tools = call(endpoint(), request(8, "tools/list", new JsonObject()));
        JsonArray list = tools.getAsJsonObject("result").getAsJsonArray("tools");

        JsonObject context = null;
        for (var element : list) {
            if ("companion_context".equals(element.getAsJsonObject().get("name").getAsString())) {
                context = element.getAsJsonObject();
            }
        }
        assertTrue(context != null, "companion_context must be listed");

        JsonObject schema = context.getAsJsonObject("inputSchema");
        JsonObject properties = schema.getAsJsonObject("properties");
        assertEquals("array", properties.getAsJsonObject("tags").get("type").getAsString());
        assertEquals("string", properties.getAsJsonObject("tags")
                .getAsJsonObject("items").get("type").getAsString());
        assertEquals(1, schema.getAsJsonArray("required").size(),
                "only companion_id is required");
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
    void repeatedRequestIdReplaysWithoutRunningTheBodyTwice() throws Exception {
        StubBackend backend = new StubBackend();
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN),
                CompanionTools.registry(backend));
        server.start();

        JsonObject arguments = new JsonObject();
        arguments.addProperty("companion_id", COMPANION.toString());
        arguments.addProperty("after_seq", 0);

        JsonObject first = new JsonObject();
        first.addProperty("name", "companion_wait_events");
        first.add("arguments", arguments);
        first.addProperty("request_id", "req-abc-123");

        call(endpoint(), request(20, "tools/call", first));
        call(endpoint(), request(21, "tools/call", first));

        assertEquals(1, backend.waitCalls.get(),
                "a repeated request_id must not run the body a second time");
    }

    @Test
    void issuedCompanionTokenIsScopedToItsCompanion() throws Exception {
        CompanionTokenStore store = new CompanionTokenStore();
        CompanionTokenStore.Issued issued = store.issueFull(COMPANION,
                java.time.Duration.ofDays(1));
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN),
                CompanionTools.registry(new StubBackend()),
                store,
                event -> { });
        server.start();

        JsonObject allowed = callWithToken(endpoint(), issued.plaintext(),
                request(30, "tools/list", new JsonObject()));
        assertTrue(allowed.has("result"));

        JsonObject listed = callWithToken(endpoint(), issued.plaintext(),
                toolCall(31, "companion_list", new JsonObject()));
        assertTrue(listed.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString().contains("Alice"));

        UUID other = UUID.fromString("99999999-9999-9999-9999-999999999999");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("companion_id", other.toString());
        JsonObject denied = callWithToken(endpoint(), issued.plaintext(),
                toolCall(32, "companion_state", arguments));
        assertTrue(denied.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString().contains("scope_denied"));
    }

    private JsonObject toolCall(int id, String name, JsonObject arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments);
        return request(id, "tools/call", params);
    }

    private JsonObject callWithToken(URI endpoint, String token, JsonObject body)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    @Test
    void pingExposesInjectedServerHealthSnapshot() throws Exception {
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN),
                CompanionTools.registry(new StubBackend()),
                TokenAuthenticator.staticToken(TOKEN, TokenScope.FULL),
                event -> { },
                () -> new com.touhoulittlemaid.mini.core.ServerMetrics(
                        3, 4.5D, 9.0D, 128, 1024, 42, 17, 1, true));
        server.start();

        JsonObject response = call(endpoint(), request(90, "ping", new JsonObject()));
        JsonObject result = response.getAsJsonObject("result");
        assertEquals(3, result.get("companion_count").getAsInt());
        assertEquals(4.5D, result.get("mspt_average").getAsDouble());
        assertEquals(9.0D, result.get("mspt_max").getAsDouble());
        assertEquals(42, result.get("thread_count").getAsInt());
        assertEquals(17, result.get("retained_events").getAsInt());
        assertTrue(result.get("mcp_connected").getAsBoolean());
    }

    @Test
    void perTokenRateLimitReturnsRateLimitedResult() throws Exception {
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN, 4, 1, 10, 90, "test"),
                CompanionTools.registry(new StubBackend()));
        server.start();

        JsonObject first = call(endpoint(), toolCall(70, "companion_list", new JsonObject()));
        assertFalse(first.getAsJsonObject("result").get("isError").getAsBoolean());

        JsonObject second = call(endpoint(), toolCall(71, "companion_list", new JsonObject()));
        String text = second.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("rate_limited"), text);
    }

    @Test
    void concurrentLimitRejectsSecondCallWhileFirstIsWaiting() throws Exception {
        StubBackend backend = new StubBackend();
        backend.waitStarted = new java.util.concurrent.CountDownLatch(1);
        backend.releaseWait = new java.util.concurrent.CountDownLatch(1);
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN, 1, 100, 10, 90, "test"),
                CompanionTools.registry(backend));
        server.start();

        JsonObject arguments = new JsonObject();
        arguments.addProperty("companion_id", COMPANION.toString());
        arguments.addProperty("after_seq", 0);
        arguments.addProperty("timeout_seconds", 0);
        JsonObject waiting = toolCall(72, "companion_wait_events", arguments);

        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return call(endpoint(), waiting);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        assertTrue(backend.waitStarted.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the first call must be in flight");

        JsonObject second = call(endpoint(), toolCall(73, "companion_list", new JsonObject()));
        String text = second.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.contains("rate_limited"), text);

        backend.releaseWait.countDown();
        first.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void rejectsANonLoopbackBrowserOrigin() throws Exception {
        server = start();
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "http://evil.example")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        request(85, "ping", new JsonObject()).toString(),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, response.statusCode());
    }

    @Test
    void acceptsALoopbackBrowserOrigin() throws Exception {
        server = start();
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "http://127.0.0.1:" + server.port())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        request(86, "ping", new JsonObject()).toString(),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void repeatedFailedAuthenticationIsThrottled() throws Exception {
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN, 4, 60, 2, 90, "test"),
                CompanionTools.registry(new StubBackend()));
        server.start();

        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(401, sendWithToken("wrong-token",
                    request(80 + attempt, "ping", new JsonObject())).statusCode());
        }
        assertEquals(429, sendWithToken("wrong-token",
                request(82, "ping", new JsonObject())).statusCode());
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

    private HttpResponse<String> sendWithToken(String token, JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
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

        private final java.util.concurrent.atomic.AtomicInteger waitCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile java.util.concurrent.CountDownLatch waitStarted;
        private volatile java.util.concurrent.CountDownLatch releaseWait;

        @Override
        public List<CompanionSnapshot> listCompanions() {
            return List.of(new CompanionSnapshot(COMPANION, Optional.empty(), "Alice",
                    LocalBehavior.STAY, com.touhoulittlemaid.mini.core.CompanionSchedule.ALL,
                    false));
        }

        @Override
        public CompanionSnapshot state(UUID companionId) {
            return new CompanionSnapshot(companionId, Optional.empty(), "Alice",
                    LocalBehavior.STAY, com.touhoulittlemaid.mini.core.CompanionSchedule.ALL,
                    false);
        }

        @Override
        public ToolResult move(UUID companionId,
                com.touhoulittlemaid.mini.core.MoveRequest request) {
            return ToolResult.ok("moving", "moving");
        }

        @Override
        public ToolResult interact(UUID companionId,
                com.touhoulittlemaid.mini.core.InteractRequest request) {
            return ToolResult.ok("interacted", "interacted");
        }

        @Override
        public ToolResult context(UUID companionId,
                com.touhoulittlemaid.mini.core.ContextQuery query) {
            return ToolResult.ok("context", "{\"scanned\":1,\"blocks\":{}}");
        }

        @Override
        public ToolResult inventory(UUID companionId,
                com.touhoulittlemaid.mini.core.InventoryRequest request) {
            return ToolResult.ok("inventory",
                    "{\"action\":\"" + request.action().id() + "\"}");
        }

        @Override
        public ToolResult setBehavior(UUID companionId, LocalBehavior behavior,
                Optional<com.touhoulittlemaid.mini.core.CompanionSchedule> schedule) {
            return ToolResult.ok("behavior_set", behavior.id());
        }

        @Override
        public ToolResult stop(UUID companionId) {
            return ToolResult.ok("stopped", "stopped");
        }

        @Override
        public ToolResult waitEvents(UUID companionId, long afterSequence,
                int timeoutSeconds, int maxEvents) {
            waitCalls.incrementAndGet();
            java.util.concurrent.CountDownLatch started = waitStarted;
            java.util.concurrent.CountDownLatch release = releaseWait;
            if (started != null) {
                started.countDown();
            }
            if (release != null) {
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return ToolResult.ok("events",
                    "{\"events\":[],\"next_seq\":" + afterSequence + ",\"timed_out\":true}");
        }

        @Override
        public ToolResult say(UUID companionId, String text) {
            return ToolResult.ok("said", "said");
        }
    }
}
