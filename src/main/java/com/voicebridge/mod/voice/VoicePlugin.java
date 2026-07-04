package com.voicebridge.mod.voice;

import com.voicebridge.mod.VoiceBridge;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.audiosender.AudioSender;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class VoicePlugin implements VoicechatPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("voicebridge-svc");
    private static VoicechatServerApi voicechatApi;

    private static final Map<UUID, AudioPlaybackSession> playbackSessions = new ConcurrentHashMap<>();
    private static final Map<UUID, Consumer<ForwardedAudio>> outputForwarders = new ConcurrentHashMap<>();

    public static final double PROXIMITY_DISTANCE = 48.0;

    // Bridge codecs: shared encoder/decoder for PCM↔Opus conversion
    // between raw browser audio and the SVC Opus pipeline.
    private static OpusEncoder bridgeEncoder;
    private static OpusDecoder bridgeDecoder;
    private static final Object CODEC_LOCK = new Object();

    public static VoicechatServerApi getApi() {
        return voicechatApi;
    }

    @Override
    public String getPluginId() {
        return "voicebridge";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi serverApi) {
            voicechatApi = serverApi;
            LOGGER.info("VoiceBridge SVC plugin initialized");
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        voicechatApi = event.getVoicechat();
        LOGGER.info("VoiceBridge SVC server started, broadcast range: {}", voicechatApi.getBroadcastRange());
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (outputForwarders.isEmpty()) return;

        MicrophonePacket packet = event.getPacket();
        VoicechatConnection senderConn = event.getSenderConnection();
        if (senderConn == null) return;

        byte[] opusData = packet.getOpusEncodedData();
        if (opusData == null || opusData.length == 0) return;

        boolean whispering = packet.isWhispering();
        ServerPlayer svcPlayer = senderConn.getPlayer();
        UUID senderUuid = svcPlayer.getUuid();

        MinecraftServer server = VoiceBridge.getServer();
        if (server == null) return;

        ServerPlayerEntity senderMc = server.getPlayerManager().getPlayer(senderUuid);
        if (senderMc == null) return;

        for (Map.Entry<UUID, Consumer<ForwardedAudio>> entry : outputForwarders.entrySet()) {
            UUID listenerUuid = entry.getKey();
            if (listenerUuid.equals(senderUuid)) continue;

            ServerPlayerEntity listenerMc = server.getPlayerManager().getPlayer(listenerUuid);
            if (listenerMc == null) continue;
            if (!senderMc.getServerWorld().equals(listenerMc.getServerWorld())) continue;
            if (listenerMc.distanceTo(senderMc) > PROXIMITY_DISTANCE) continue;

            entry.getValue().accept(new ForwardedAudio(senderUuid, opusData, whispering));
        }
    }

    public static void createPlaybackSession(UUID playerUuid, ServerPlayerEntity player) {
        if (voicechatApi == null) {
            LOGGER.warn("Cannot create playback session: SVC API not available");
            return;
        }

        destroyPlaybackSession(playerUuid);
        VoicechatConnection conn = voicechatApi.getConnectionOf(playerUuid);

        if (conn != null && !conn.isInstalled()) {
            AudioSender sender = voicechatApi.createAudioSender(conn);
            if (sender != null && voicechatApi.registerAudioSender(sender)) {
                playbackSessions.put(playerUuid, new AudioPlaybackSession(sender));
                LOGGER.info("Created AudioSender playback session for {}", playerUuid);
                return;
            }
        }

        Entity svcEntity = voicechatApi.fromEntity(player);
        if (svcEntity == null) {
            LOGGER.warn("Cannot create entity audio channel: entity is null");
            return;
        }

        EntityAudioChannel channel = voicechatApi.createEntityAudioChannel(UUID.randomUUID(), svcEntity);
        if (channel == null) {
            LOGGER.warn("Cannot create entity audio channel: channel is null");
            return;
        }

        channel.setDistance((float) PROXIMITY_DISTANCE);

        BlockingQueue<short[]> audioQueue = new LinkedBlockingQueue<>();
        OpusDecoder decoder = voicechatApi.createDecoder();
        OpusEncoder encoder = voicechatApi.createEncoder();

        AudioPlayer audioPlayer = voicechatApi.createAudioPlayer(channel, encoder, () -> {
            try {
                return audioQueue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        });
        audioPlayer.startPlaying();

        playbackSessions.put(playerUuid, new AudioPlaybackSession(audioPlayer, audioQueue, encoder, decoder));
        LOGGER.info("Created EntityAudioChannel playback session for {}", playerUuid);
    }

    public static void feedAudio(UUID playerUuid, byte[] opusData, boolean whispering) {
        AudioPlaybackSession session = playbackSessions.get(playerUuid);
        if (session == null) {
            LOGGER.debug("No playback session for {}, dropping audio", playerUuid);
            return;
        }

        if (session.audioSender != null) {
            session.audioSender.whispering(whispering);
            session.audioSender.send(opusData);
            return;
        }

        if (session.decoder == null || session.queue == null) return;

        short[] pcm;
        try {
            pcm = session.decoder.decode(opusData);
        } catch (Exception e) {
            LOGGER.warn("Failed to decode Opus data for {}", playerUuid);
            return;
        }
        if (pcm == null || pcm.length == 0) return;

        int frameSize = 960;
        for (int offset = 0; offset < pcm.length; offset += frameSize) {
            int frameLen = Math.min(frameSize, pcm.length - offset);
            short[] frame = new short[frameSize];
            System.arraycopy(pcm, offset, frame, 0, frameLen);
            session.queue.offer(frame);
        }

        if (session.audioPlayer != null && session.audioPlayer.isStopped()) {
            session.audioPlayer.startPlaying();
        }
    }

    public static void destroyPlaybackSession(UUID playerUuid) {
        AudioPlaybackSession session = playbackSessions.remove(playerUuid);
        if (session == null) return;

        if (session.audioSender != null) {
            try { voicechatApi.unregisterAudioSender(session.audioSender); } catch (Exception e) { /* ignore */ }
        }
        if (session.audioPlayer != null) {
            try { session.audioPlayer.stopPlaying(); } catch (Exception e) { /* ignore */ }
        }
        if (session.encoder != null) {
            try { session.encoder.close(); } catch (Exception e) { /* ignore */ }
        }
        if (session.decoder != null) {
            try { session.decoder.close(); } catch (Exception e) { /* ignore */ }
        }
        LOGGER.info("Destroyed playback session for {}", playerUuid);
    }

    public static void setOutputForwarder(UUID listenerUuid, Consumer<ForwardedAudio> forwarder) {
        outputForwarders.put(listenerUuid, forwarder);
        LOGGER.info("Registered output forwarder for {}", listenerUuid);
    }

    public static void removeOutputForwarder(UUID listenerUuid) {
        outputForwarders.remove(listenerUuid);
        LOGGER.info("Removed output forwarder for {}", listenerUuid);
    }

    // ===================== PCM↔Opus Bridge Codecs =====================

    private static void ensureBridgeCodecs() {
        if (bridgeEncoder != null) return;
        synchronized (CODEC_LOCK) {
            if (bridgeEncoder == null && voicechatApi != null) {
                bridgeEncoder = voicechatApi.createEncoder();
                bridgeDecoder = voicechatApi.createDecoder();
                LOGGER.info("Bridge codecs initialized");
            }
        }
    }

    public static byte[] pcmToOpus(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2 || (pcmData.length & 1) != 0) {
            LOGGER.warn("pcmToOpus: invalid PCM data (len={})", pcmData == null ? 0 : pcmData.length);
            return pcmData;
        }
        ensureBridgeCodecs();
        synchronized (CODEC_LOCK) {
            if (bridgeEncoder == null) {
                LOGGER.warn("pcmToOpus: encoder not available");
                return pcmData;
            }
            short[] samples = new short[pcmData.length / 2];
            ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            return bridgeEncoder.encode(samples);
        }
    }

    public static byte[] opusToPcm(byte[] opusData) {
        if (opusData == null || opusData.length == 0) return opusData;
        ensureBridgeCodecs();
        synchronized (CODEC_LOCK) {
            if (bridgeDecoder == null) {
                LOGGER.warn("opusToPcm: decoder not available");
                return opusData;
            }
            short[] samples = bridgeDecoder.decode(opusData);
            if (samples == null || samples.length == 0) return opusData;
            ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            buf.asShortBuffer().put(samples);
            return buf.array();
        }
    }

    public record ForwardedAudio(UUID senderUuid, byte[] opusData, boolean whispering) {
        public byte[] toBinary() {
            ByteBuffer buf = ByteBuffer.allocate(1 + 16 + opusData.length);
            buf.put((byte) (whispering ? 1 : 0));
            buf.putLong(senderUuid.getMostSignificantBits());
            buf.putLong(senderUuid.getLeastSignificantBits());
            buf.put(opusData);
            return buf.array();
        }
    }

    private static class AudioPlaybackSession {
        final AudioSender audioSender;
        final AudioPlayer audioPlayer;
        final BlockingQueue<short[]> queue;
        final OpusEncoder encoder;
        final OpusDecoder decoder;

        AudioPlaybackSession(AudioSender sender) {
            this.audioSender = sender;
            this.audioPlayer = null;
            this.queue = null;
            this.encoder = null;
            this.decoder = null;
        }

        AudioPlaybackSession(AudioPlayer player, BlockingQueue<short[]> queue, OpusEncoder encoder, OpusDecoder decoder) {
            this.audioSender = null;
            this.audioPlayer = player;
            this.queue = queue;
            this.encoder = encoder;
            this.decoder = decoder;
        }
    }
}
