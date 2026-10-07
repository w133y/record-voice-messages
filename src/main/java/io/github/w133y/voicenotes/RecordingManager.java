package io.github.w133y.voicenotes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active recordings. Frames arrive from the voice chat thread; anything touching
 * a player runs on that player's entity scheduler, so it is safe on Folia regions.
 */
final class RecordingManager {

    /** Where a recording goes once it is finished. */
    enum Kind {
        /** Everyone online, delivered by VoiceNotes. */
        GLOBAL,
        /** A specific player, delivered internally or through the configured chat plugin command. */
        PRIVATE,
        /** Command delivery only: the chat plugin's reply command picks the recipient. */
        REPLY
    }

    /** Extra wall-clock time on top of max duration, for players who stay silent. */
    private static final long IDLE_GRACE_MS = 15_000;

    private final VoiceNotesPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSent = new ConcurrentHashMap<>();

    RecordingManager(VoiceNotesPlugin plugin) {
        this.plugin = plugin;
    }

    private static final class Session {
        final Kind kind;
        final @Nullable UUID recipient;
        final long startedAt = System.currentTimeMillis();
        private final List<byte[]> frames = new ArrayList<>();
        // Loudness of each frame, measured as it arrives so finishing costs nothing
        private final List<Float> levels = new ArrayList<>();
        private @Nullable OpusDecoder decoder;
        private boolean full;

        Session(Kind kind, @Nullable UUID recipient, @Nullable OpusDecoder decoder) {
            this.kind = kind;
            this.recipient = recipient;
            this.decoder = decoder;
        }

        /** Returns true exactly once, when the limit is first exceeded. */
        synchronized boolean addAndCheckFull(byte[] frame, int maxFrames) {
            if (frames.size() < maxFrames) {
                frames.add(frame);
                levels.add(Waveform.level(decoder, frame));
                return false;
            }
            if (full) return false;
            full = true;
            return true;
        }

        synchronized int frameCount() {
            return frames.size();
        }

        synchronized List<byte[]> frames() {
            return List.copyOf(frames);
        }

        synchronized List<Float> levels() {
            return List.copyOf(levels);
        }

        /** Frees the native decoder; safe to call more than once. */
        synchronized void close() {
            if (decoder != null) {
                decoder.close();
                decoder = null;
            }
        }
    }

    boolean isRecording(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    void start(Player player, Kind kind, @Nullable Player recipient) {
        OpusDecoder decoder = null;
        VoicechatServerApi api = plugin.bridge().api();
        if (api != null && plugin.settings().waveformEnabled()) {
            try {
                decoder = api.createDecoder();
            } catch (Throwable t) {
                // No native Opus on this machine, Waveform falls back to packet sizes
            }
        }
        Session previous = sessions.put(player.getUniqueId(),
                new Session(kind, recipient == null ? null : recipient.getUniqueId(), decoder));
        if (previous != null) previous.close();
    }

    /** Called from the voice chat thread. */
    void accept(UUID playerId, byte[] opusFrame) {
        Session session = sessions.get(playerId);
        if (session == null) return;
        if (session.addAndCheckFull(opusFrame, plugin.settings().maxFrames())) {
            finishLater(playerId);
        }
    }

    long cooldownLeftMs(UUID playerId) {
        Long last = lastSent.get(playerId);
        if (last == null) return 0;
        return Math.max(0, last + plugin.settings().cooldownMs() - System.currentTimeMillis());
    }

    /** Schedules finish() on the player's own thread (main thread on Paper, region thread on Folia). */
    private void finishLater(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !plugin.isEnabled()) {
            discard(playerId);
            return;
        }
        player.getScheduler().run(plugin, task -> finish(playerId), () -> discard(playerId));
    }

    /** Stops the recording and delivers the note. Call from the player's thread. */
    void finish(UUID playerId) {
        // remove() is atomic, so a second concurrent call simply does nothing
        Session session = sessions.remove(playerId);
        if (session == null) return;
        session.close();
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        player.sendActionBar(Component.empty());

        List<byte[]> frames = session.frames();
        if ((long) frames.size() * VoiceNote.FRAME_MS < plugin.settings().minDurationMs()) {
            plugin.messages().send(player, "too-short");
            return;
        }

        long now = System.currentTimeMillis();
        String name = player.getName();
        Settings settings = plugin.settings();
        NoteStore notes = plugin.notes();
        float[] wave = settings.waveformEnabled()
                ? Waveform.compute(session.levels(), settings.waveformBars())
                : new float[0];

        switch (session.kind) {
            case GLOBAL -> {
                VoiceNote note = notes.create(id -> new VoiceNote(id, playerId, name, null, null, false, frames, wave, now));
                plugin.announce(note, player, null);
            }
            case PRIVATE -> {
                Player recipient = session.recipient == null ? null : Bukkit.getPlayer(session.recipient);
                if (recipient == null) {
                    plugin.messages().send(player, "recipient-offline");
                    return;
                }
                if (plugin.commandDelivery()) {
                    VoiceNote note = notes.create(id -> new VoiceNote(id, playerId, name, recipient.getUniqueId(), recipient.getName(), true, frames, wave, now));
                    player.performCommand(settings.privateCommand()
                            .replace("{recipient}", recipient.getName())
                            .replace("{note}", NoteTokens.of(note)));
                } else {
                    VoiceNote note = notes.create(id -> new VoiceNote(id, playerId, name, recipient.getUniqueId(), recipient.getName(), false, frames, wave, now));
                    plugin.announce(note, player, recipient);
                    plugin.conversations().link(playerId, recipient.getUniqueId());
                }
            }
            case REPLY -> {
                VoiceNote note = notes.create(id -> new VoiceNote(id, playerId, name, null, null, true, frames, wave, now));
                player.performCommand(settings.replyCommand().replace("{note}", NoteTokens.of(note)));
            }
        }
        lastSent.put(playerId, now);
    }

    /** Discards the recording. Returns false if the player wasn't recording. */
    boolean cancel(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session == null) return false;
        session.close();
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) player.sendActionBar(Component.empty());
        return true;
    }

    /** Runs on the async scheduler every 250 ms. */
    void tick() {
        Settings settings = plugin.settings();
        Messages messages = plugin.messages();
        String max = VoiceNotesPlugin.formatDuration(settings.maxDurationMs());
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            UUID playerId = entry.getKey();
            Session session = entry.getValue();
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) {
                if (sessions.remove(playerId, session)) session.close();
                continue;
            }
            if (now - session.startedAt > settings.maxDurationMs() + IDLE_GRACE_MS) {
                finishLater(playerId);
                continue;
            }
            long recordedMs = (long) session.frameCount() * VoiceNote.FRAME_MS;
            Component bar = messages.get("recording-actionbar",
                    Placeholder.unparsed("time", VoiceNotesPlugin.formatDuration(recordedMs)),
                    Placeholder.unparsed("max", max));
            player.getScheduler().run(plugin, task -> {
                // The recording may have ended while this was queued
                if (sessions.get(playerId) == session) player.sendActionBar(bar);
            }, null);
        }
    }

    private void discard(UUID playerId) {
        Session session = sessions.remove(playerId);
        if (session != null) session.close();
    }

    void clear() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }
}
