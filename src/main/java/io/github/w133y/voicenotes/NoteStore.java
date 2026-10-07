package io.github.w133y.voicenotes;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/** In-memory, thread-safe note storage. Notes are lost on restart, which is fine for chat messages. */
final class NoteStore {

    // No look-alike characters, ids end up in chat commands
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int ID_LENGTH = 6;

    private final Map<String, VoiceNote> notes = new ConcurrentHashMap<>();

    /** Builds a note with a fresh unique id and stores it atomically (safe across Folia regions). */
    VoiceNote create(Function<String, VoiceNote> factory) {
        while (true) {
            VoiceNote note = factory.apply(randomId());
            if (notes.putIfAbsent(note.id(), note) == null) return note;
        }
    }

    @Nullable VoiceNote get(String id) {
        return notes.get(id);
    }

    void purge(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        notes.values().removeIf(note -> note.createdAt() < cutoff);
    }

    private static String randomId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder builder = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
