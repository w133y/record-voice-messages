package io.github.w133y.voicenotes;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Hooks into Simple Voice Chat: captures microphone packets and exposes the server API. */
public final class VoicechatBridge implements VoicechatPlugin {

    /** Volume category, so players can adjust note volume in the voice chat menu. */
    public static final String CATEGORY_ID = "voicenotes";

    private final VoiceNotesPlugin plugin;
    private volatile @Nullable VoicechatServerApi serverApi;

    VoicechatBridge(VoiceNotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getPluginId() {
        return "voicenotes";
    }

    @Override
    public void initialize(VoicechatApi api) {
        if (api instanceof VoicechatServerApi server) {
            serverApi = server;
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(VoicechatServerStoppedEvent.class, event -> serverApi = null);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();
        VolumeCategory category = api.volumeCategoryBuilder()
                .setId(CATEGORY_ID)
                .setName("Voice Notes")
                .setDescription("Volume of voice notes played from chat")
                .build();
        api.registerVolumeCategory(category);
        serverApi = api;
    }

    // Runs on the voice chat network thread, keep it cheap
    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) return;

        UUID playerId = sender.getPlayer().getUuid();
        if (!plugin.recordings().isRecording(playerId)) return;

        byte[] opus = event.getPacket().getOpusEncodedData();
        // Empty packets only mark the end of speech
        if (opus.length > 0) {
            plugin.recordings().accept(playerId, opus);
        }
        if (plugin.settings().muteWhileRecording()) {
            event.cancel();
        }
    }

    @Nullable VoicechatServerApi api() {
        return serverApi;
    }

    /** Returns a usable connection for the player, or null if they can't send/hear voice. */
    @Nullable VoicechatConnection usableConnection(UUID playerId) {
        VoicechatServerApi api = serverApi;
        if (api == null) return null;
        VoicechatConnection connection = api.getConnectionOf(playerId);
        if (connection == null || !connection.isConnected() || connection.isDisabled()) return null;
        return connection;
    }
}
