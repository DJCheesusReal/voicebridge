package com.voicebridge.mod;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.voicebridge.mod.server.BridgeWebSocketServer;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static net.minecraft.server.command.CommandManager.literal;

public class VoiceBridge implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("voicebridge");
    private static final int WS_PORT = 8080;
    private static final String WEB_URL = "https://yourdomain.com/?token=";

    private BridgeWebSocketServer wsServer;
    private final ConcurrentMap<String, UUID> tokenRegistry = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private int tickCounter = 0;

    @Override
    public void onInitializeServer() {
        wsServer = new BridgeWebSocketServer(WS_PORT, tokenRegistry);
        Thread wsThread = new Thread(wsServer, "VoiceBridge-WS");
        wsThread.setDaemon(true);
        wsThread.start();
        LOGGER.info("VoiceBridge WebSocket server started on port {}", WS_PORT);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(literal("voicechat")
                .then(literal("web")
                    .executes(this::executeWebCommand)
                )
            );
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (wsServer != null) {
                try {
                    wsServer.stop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.warn("WebSocket server stop interrupted", e);
                }
                LOGGER.info("VoiceBridge WebSocket server stopped");
            }
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 2 != 0) return;

            if (wsServer == null || !wsServer.hasAuthenticatedClients()) return;

            JsonArray playersArray = new JsonArray();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                JsonObject playerObj = new JsonObject();
                playerObj.addProperty("uuid", player.getUuidAsString());
                playerObj.addProperty("x", player.getX());
                playerObj.addProperty("y", player.getY());
                playerObj.addProperty("z", player.getZ());
                playersArray.add(playerObj);
            }

            JsonObject msg = new JsonObject();
            msg.addProperty("type", "positions");
            msg.add("players", playersArray);

            wsServer.broadcast(msg.toString());
        });
    }

    private int executeWebCommand(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendFeedback(() -> Text.literal("This command can only be used by a player."), false);
            return 0;
        }

        String token = String.format("%04d", random.nextInt(10000));
        UUID uuid = player.getUuid();
        tokenRegistry.put(token, uuid);

        Text message = Text.literal("Open VoiceBridge: ")
            .append(Text.literal(WEB_URL + token)
                .styled(style -> style
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, WEB_URL + token))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Click to open the VoiceBridge web app")))
                    .withUnderline(true)
                    .withColor(Formatting.BLUE)
                )
            );

        source.sendFeedback(() -> message, false);
        LOGGER.info("Token {} generated for player {}", token, uuid);

        return Command.SINGLE_SUCCESS;
    }
}
