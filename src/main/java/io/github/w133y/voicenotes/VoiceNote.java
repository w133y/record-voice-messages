package io.github.w133y.voicenotes;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A recorded note: raw Opus frames exactly as the sender's client produced them.
 *
 * @param recipient  null for global notes and chat plugin replies
 * @param linkAccess anyone who got the id can play it; used when a chat plugin delivers the note,
 *                   because then the chat plugin (not us) decides who sees it, e.g. /reply or spy
 * @param waveform   loudness bars in range 0..1, empty when disabled
 */
record VoiceNote(String id, UUID sender, String senderName, @Nullable UUID recipient,
                 @Nullable String recipientName, boolean linkAccess, List<byte[]> frames,
                 float[] waveform, long createdAt) {

    /** Simple Voice Chat sends one 20 ms Opus frame per packet. */
    static final int FRAME_MS = 20;

    long durationMs() {
        return (long) frames.size() * FRAME_MS;
    }

    boolean canBeHeardBy(UUID playerId) {
        return linkAccess || recipient == null || recipient.equals(playerId) || sender.equals(playerId);
    }
}
