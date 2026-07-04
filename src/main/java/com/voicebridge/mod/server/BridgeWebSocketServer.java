package com.voicebridge.mod.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class BridgeWebSocketServer extends WebSocketServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("voicebridge-ws");

    private final ConcurrentMap<String, UUID> tokenRegistry;
    private final ConcurrentMap<WebSocket, UUID> authenticatedSessions = new ConcurrentHashMap<>();

    public BridgeWebSocketServer(int port, ConcurrentMap<String, UUID> tokenRegistry) {
        super(new InetSocketAddress(port));
        this.tokenRegistry = tokenRegistry;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        LOGGER.info("WebSocket client connected: {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (authenticatedSessions.containsKey(conn)) {
            return;
        }
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String type = json.get("type").getAsString();
            if (!"auth".equals(type)) {
                conn.close(4001, "Expected auth message");
                return;
            }
            String token = json.get("token").getAsString();
            UUID playerUuid = tokenRegistry.remove(token);
            if (playerUuid == null) {
                conn.close(4002, "Invalid or expired token");
                return;
            }
            authenticatedSessions.put(conn, playerUuid);
            JsonObject response = new JsonObject();
            response.addProperty("type", "auth_success");
            response.addProperty("uuid", playerUuid.toString());
            conn.send(response.toString());
            LOGGER.info("WebSocket client authenticated for player {}", playerUuid);
        } catch (Exception e) {
            conn.close(4000, "Invalid message format");
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        authenticatedSessions.remove(conn);
        LOGGER.info("WebSocket client disconnected: {} (code: {})", conn.getRemoteSocketAddress(), code);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        LOGGER.error("WebSocket error on {}: {}", conn != null ? conn.getRemoteSocketAddress() : "unknown", ex.getMessage());
    }

    @Override
    public void onStart() {
        LOGGER.info("WebSocket server started on {}", getAddress());
    }

    public void broadcast(String message) {
        if (authenticatedSessions.isEmpty()) return;
        for (WebSocket conn : authenticatedSessions.keySet()) {
            if (conn.isOpen()) {
                conn.send(message);
            }
        }
    }

    public boolean hasAuthenticatedClients() {
        return !authenticatedSessions.isEmpty();
    }
}
