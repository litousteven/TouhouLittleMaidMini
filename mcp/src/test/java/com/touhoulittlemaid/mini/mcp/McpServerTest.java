package com.touhoulittlemaid.mini.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.touhoulittlemaid.mini.core.PlatformState;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpServerTest {

    private static final String TOKEN = "m0-test-token";
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
    void initializesListsToolsAndReadsState() throws Exception {
        server = start();
        URI endpoint = endpoint();

        JsonObject initialized = call(endpoint, request(1, "initialize", new JsonObject()));
        assertEquals(McpServer.PROTOCOL_VERSION,
                initialized.getAsJsonObject("result").get("protocolVersion").getAsString());

        JsonObject tools = call(endpoint, request(2, "tools/list", new JsonObject()));
        JsonArray names = tools.getAsJsonObject("result").getAsJsonArray("tools");
        assertEquals(2, names.size());
        assertEquals("ping", names.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("state", names.get(1).getAsJsonObject().get("name").getAsString());

        JsonObject params = new JsonObject();
        params.addProperty("name", "state");
        params.add("arguments", new JsonObject());
        JsonObject state = call(endpoint, request(3, "tools/call", params));
        String stateText = state.getAsJsonObject("result")
                .getAsJsonArray("content")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
        assertTrue(stateText.contains("\"minecraftVersion\":\"26.3\""));
        assertTrue(stateText.contains("\"serverReady\":true"));
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
                () -> new PlatformState("0.1.0-alpha.1", "26.3", true, "ok"));
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
}
