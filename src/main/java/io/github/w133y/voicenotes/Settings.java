package io.github.w133y.voicenotes;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

record Settings(int maxDurationMs, int minDurationMs, long cooldownMs, long expireAfterMs,
                boolean muteWhileRecording, boolean commandDelivery, String privateCommand,
                String replyCommand, Set<String> trackCommands, Set<String> trackReplyCommands,
                boolean waveformEnabled, int waveformBars) {

    static Settings from(FileConfiguration config) {
        int max = clamp(config.getInt("max-duration", 60), 3, 300);
        int min = clamp(config.getInt("min-duration", 1), 0, max);
        return new Settings(
                max * 1000,
                min * 1000,
                Math.max(0, config.getLong("cooldown", 10)) * 1000L,
                Math.max(1, config.getLong("expire-after", 30)) * 60_000L,
                config.getBoolean("mute-while-recording", true),
                "command".equalsIgnoreCase(config.getString("private.delivery", "internal")),
                stripSlash(config.getString("private.command", "msg {recipient} {note}")),
                stripSlash(config.getString("private.reply-command", "r {note}")),
                lowerSet(config, "private.track-commands"),
                lowerSet(config, "private.track-reply-commands"),
                config.getBoolean("waveform.enabled", true),
                clamp(config.getInt("waveform.bars", 24), 4, 80));
    }

    int maxFrames() {
        return maxDurationMs / VoiceNote.FRAME_MS;
    }

    private static Set<String> lowerSet(FileConfiguration config, String path) {
        return config.getStringList(path).stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
