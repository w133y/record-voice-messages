package io.github.w133y.voicenotes;

import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Streams stored Opus frames to a single listener, one frame every 20 ms. */
final class PlaybackManager {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VoiceNotes-Playback");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Playback> active = new ConcurrentHashMap<>();

    private final class Playback implements Runnable {
        private final UUID listener;
        private final StaticAudioChannel channel;
        private final List<byte[]> frames;
        private int index;
        private boolean done;
        private ScheduledFuture<?> future;

        Playback(UUID listener, StaticAudioChannel channel, List<byte[]> frames) {
            this.listener = listener;
            this.channel = channel;
            this.frames = frames;
        }

        @Override
        public synchronized void run() {
            if (done) return;
            try {
                if (index >= frames.size()) {
                    finish();
                    return;
                }
                channel.send(frames.get(index++));
            } catch (Throwable t) {
                finish();
            }
        }

        synchronized void finish() {
            if (done) return;
            done = true;
            // Tells the client the stream has ended
            channel.flush();
            if (future != null) future.cancel(false);
            active.remove(listener, this);
        }
    }

    /** Starts playback, replacing whatever the listener was hearing. */
    boolean play(VoicechatServerApi api, VoicechatConnection target, UUID listener, VoiceNote note) {
        stop(listener);
        StaticAudioChannel channel = api.createStaticAudioChannel(UUID.randomUUID());
        if (channel == null) return false;
        channel.addTarget(target);
        channel.setCategory(VoicechatBridge.CATEGORY_ID);

        Playback playback = new Playback(listener, channel, note.frames());
        active.put(listener, playback);
        synchronized (playback) {
            playback.future = executor.scheduleAtFixedRate(playback, 0, VoiceNote.FRAME_MS, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    void stop(UUID listener) {
        Playback playback = active.remove(listener);
        if (playback != null) playback.finish();
    }

    void shutdown() {
        List.copyOf(active.values()).forEach(Playback::finish);
        executor.shutdownNow();
    }
}
