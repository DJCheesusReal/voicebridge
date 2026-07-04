package com.voicebridge.mod.server;

import com.voicebridge.mod.voice.VoicePlugin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Manages the lifecycle of audio playback sessions and output forwarders
 * for authenticated WebSocket clients.
 *
 * <p>This class bridges {@link BridgeWebSocketServer} events with the
 * static API in {@link VoicePlugin}, handling player lookups and
 * session cleanup.</p>
 */
public final class ServerWebSocketManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("voicebridge-svc-mgr");

    private final BridgeWebSocketServer wsServer;
    private final MinecraftServer mcServer;

    public ServerWebSocketManager(BridgeWebSocketServer wsServer, MinecraftServer mcServer) {
        this.wsServer = wsServer;
        this.mcServer = mcServer;
    }

    /**
     * Called when a WebSocket client successfully authenticates.
     * Sets up a playback session (web → game) and an output forwarder
     * (game → web) for the linked Minecraft player.
     */
    public void onClientAuthenticated(UUID playerUuid) {
        ServerPlayerEntity player = mcServer.getPlayerManager().getPlayer(playerUuid);
        if (player == null) {
            LOGGER.warn("Authenticated player {} is not online", playerUuid);
            return;
        }

        VoicePlugin.createPlaybackSession(playerUuid, player);

        VoicePlugin.setOutputForwarder(playerUuid, forwardedAudio -> {
            if (wsServer.hasAuthenticatedClients()) {
                wsServer.broadcast(forwardedAudio.toBinary());
            }
        });

        LOGGER.info("Session established for player {}", playerUuid);
    }

    /**
     * Called when a WebSocket client disconnects.
     * Tears down the playback session and output forwarder.
     */
    public void onClientDisconnected(UUID playerUuid) {
        VoicePlugin.destroyPlaybackSession(playerUuid);
        VoicePlugin.removeOutputForwarder(playerUuid);
        LOGGER.info("Session torn down for player {}", playerUuid);
    }
}
