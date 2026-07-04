package com.voicebridge.mod.voice;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoicePlugin implements VoicechatPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("voicebridge-svc");
    private static VoicechatServerApi voicechatApi;

    public static VoicechatServerApi getVoicechatApi() {
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
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        voicechatApi = event.getVoicechat();
        LOGGER.info("VoiceBridge SVC server started");
    }
}
