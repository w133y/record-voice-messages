package io.github.w133y.voicenotes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

/** Plain-text tokens like [vn:abc123] that survive any chat plugin and get rendered as the "token" line. */
final class NoteTokens {

    private static final String MARKER = "[vn:";
    private static final Pattern PATTERN = Pattern.compile("\\[vn:([a-z0-9]{6})]");

    private final VoiceNotesPlugin plugin;
    private final TextReplacementConfig replacement;

    NoteTokens(VoiceNotesPlugin plugin) {
        this.plugin = plugin;
        this.replacement = TextReplacementConfig.builder()
                .match(PATTERN)
                .replacement((match, builder) -> render(match.group(1)))
                .build();
    }

    static String of(VoiceNote note) {
        return MARKER + note.id() + "]";
    }

    /** Returns the same instance when there is nothing to replace, so callers can skip re-encoding. */
    Component render(Component message) {
        if (!PlainTextComponentSerializer.plainText().serialize(message).contains(MARKER)) return message;
        return message.replaceText(replacement);
    }

    private Component render(String id) {
        VoiceNote note = plugin.notes().get(id);
        if (note == null) return plugin.messages().get("token-expired");
        return plugin.noteFormat().line("token", note);
    }
}
