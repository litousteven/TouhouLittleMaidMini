package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.touhoulittlemaid.mini.core.ActionKind;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TokenScopeAndAuditTest {

    private static final String TOKEN = "scope-test-token";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final List<AuditLog.AuditEvent> events = new CopyOnWriteArrayList<>();
    private McpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void readOnlyScopeBlocksMutatingTools() throws Exception {
        start(TokenScope.READ_ONLY);

        JsonObject observe = call("companion_list", new JsonObject());
        assertFalse(observe.getAsJsonObject("result").get("isError").getAsBoolean());

        JsonObject mutate = call("companion_move", arguments());
        assertTrue(mutate.getAsJsonObject("result").get("isError").getAsBoolean());
        assertTrue(text(mutate).contains("scope_denied"));
    }

    @Test
    void fullScopeAllowsMutatingTools() throws Exception {
        start(TokenScope.FULL);
        JsonObject mutate = call("companion_move", arguments());
        assertFalse(text(mutate).contains("scope_denied"));
    }

    @Test
    void auditRecordsToolAndOutcomeWithoutTheToken() throws Exception {
        start(TokenScope.FULL);
        call("companion_list", new JsonObject());

        assertEquals(1, events.size());
        AuditLog.AuditEvent event = events.get(0);
        assertEquals("companion_list", event.toolName());
        assertEquals("OK", event.status());
        assertFalse(event.format().contains(TOKEN), "audit must never contain the token");
        assertEquals(12, event.tokenDigestId().length());
    }

    @Test
    void auditIsWrittenForRefusalsToo() throws Exception {
        start(TokenScope.READ_ONLY);
        call("companion_move", arguments());

        assertEquals(1, events.size());
        assertEquals("REJECTED", events.get(0).status());
        assertEquals("scope_denied", events.get(0).code());
    }

    @Test
    void scopeMapsEveryToolToOneActionFamily() {
        assertEquals(ActionKind.OBSERVE, TokenScope.actionOf("companion_list"));
        assertEquals(ActionKind.OBSERVE, TokenScope.actionOf("companion_state"));
        assertEquals(ActionKind.INVENTORY, TokenScope.actionOf("companion_inventory"));
        assertEquals(ActionKind.OBSERVE, TokenScope.actionOf("companion_wait_events"));
        assertEquals(ActionKind.MOVE, TokenScope.actionOf("companion_move"));
        assertEquals(ActionKind.INTERACT, TokenScope.actionOf("companion_interact"));
        assertEquals(ActionKind.STOP, TokenScope.actionOf("companion_stop"));
    }

    @Test
    void digestIdIsStableAndShort() {
        assertEquals(AuditLog.digestId(TOKEN), AuditLog.digestId(TOKEN));
        assertFalse(AuditLog.digestId(TOKEN).equals(AuditLog.digestId("other")));
        assertEquals(12, AuditLog.digestId(TOKEN).length());
    }

    private void start(TokenScope scope) throws Exception {
        server = new McpServer(
                new McpServerConfig(McpServerConfig.LOOPBACK, 0, TOKEN),
                CompanionTools.registry(new EmptyBackend()),
                scope,
                events::add);
        server.start();
    }

    private JsonObject arguments() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("companion_id", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        arguments.addProperty("x", 1);
        arguments.addProperty("y", 2);
        arguments.addProperty("z", 3);
        return arguments;
    }

    private String text(JsonObject response) {
        return response.getAsJsonObject("result").getAsJsonArray("content")
                .get(0).getAsJsonObject().get("text").getAsString();
    }

    private JsonObject call(String tool, JsonObject arguments) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", arguments);

        JsonObject body = new JsonObject();
        body.addProperty("jsonrpc", "2.0");
        body.addProperty("id", 1);
        body.addProperty("method", "tools/call");
        body.add("params", params);

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint())
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.port() + McpServer.PATH);
    }

    private static final class EmptyBackend implements CompanionTools.CompanionBackend {
        @Override
        public List<com.touhoulittlemaid.mini.core.CompanionSnapshot> listCompanions() {
            return List.of();
        }

        @Override
        public com.touhoulittlemaid.mini.core.CompanionSnapshot state(java.util.UUID id) {
            return null;
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult move(
                java.util.UUID id, int x, int y, int z, double speed) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("moving", "moving");
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult interact(
                java.util.UUID id, int x, int y, int z) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("interacted", "interacted");
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult context(java.util.UUID id,
                com.touhoulittlemaid.mini.core.ContextQuery query) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("context", "{}");
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult inventory(java.util.UUID id,
                com.touhoulittlemaid.mini.core.InventoryRequest request) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("inventory", "{}");
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult setBehavior(
                java.util.UUID id, com.touhoulittlemaid.mini.core.LocalBehavior behavior) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("behavior_set", behavior.id());
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult stop(java.util.UUID id) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("stopped", "stopped");
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult waitEvents(
                java.util.UUID id, long afterSequence, int timeoutSeconds, int maxEvents) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("events", "{}");
        }

        @Override
        public com.touhoulittlemaid.mini.core.ToolResult say(java.util.UUID id, String text) {
            return com.touhoulittlemaid.mini.core.ToolResult.ok("said", "said");
        }
    }
}
