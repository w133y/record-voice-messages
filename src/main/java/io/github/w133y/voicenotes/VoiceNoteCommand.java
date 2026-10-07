package io.github.w133y.voicenotes;

import io.github.w133y.voicenotes.RecordingManager.Kind;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /vn                 - start recording for global chat, or send if already recording
 * /vn to &lt;player&gt;     - start a private recording (meant for buttons in chat plugin formats)
 * /vn &lt;player&gt;        - same as above, shorter for typing
 * /vn reply           - record a reply to the last private conversation
 * /vn send | cancel   - send or discard the current recording
 * /vn play &lt;id&gt;       - play a note (used by clickable chat messages)
 * /vn stopplay        - stop the note you are listening to
 * /vn reload          - reload the config
 */
final class VoiceNoteCommand implements TabExecutor {

    private final VoiceNotesPlugin plugin;

    VoiceNoteCommand(VoiceNotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Messages messages = plugin.messages();

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voicenotes.admin")) {
                messages.send(sender, "no-permission");
                return true;
            }
            plugin.reload();
            plugin.messages().send(sender, "reloaded");
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return true;
        }
        UUID playerId = player.getUniqueId();
        RecordingManager recordings = plugin.recordings();

        if (args.length == 0) {
            if (recordings.isRecording(playerId)) {
                recordings.finish(playerId);
            } else {
                startRecording(player, Kind.GLOBAL, null);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "send", "stop" -> {
                if (recordings.isRecording(playerId)) recordings.finish(playerId);
                else messages.send(player, "not-recording");
            }
            case "cancel" -> {
                if (recordings.cancel(playerId)) messages.send(player, "recording-cancelled");
                else messages.send(player, "not-recording");
            }
            case "play" -> {
                if (args.length >= 2) play(player, args[1]);
            }
            case "reply" -> startReply(player);
            case "stopplay" -> plugin.playback().stop(playerId);
            case "to" -> {
                if (args.length >= 2) startPrivate(player, args[1]);
                else messages.send(player, "player-not-found");
            }
            default -> startPrivate(player, args[0]);
        }
        return true;
    }

    private void startPrivate(Player player, String targetName) {
        Messages messages = plugin.messages();
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !player.canSee(target)) {
            messages.send(player, "player-not-found");
        } else if (target.equals(player)) {
            messages.send(player, "cannot-send-self");
        } else {
            startRecording(player, Kind.PRIVATE, target);
        }
    }

    private void startReply(Player player) {
        if (plugin.commandDelivery()) {
            // The chat plugin's own reply command resolves the recipient when the note is sent
            startRecording(player, Kind.REPLY, null);
            return;
        }
        UUID partner = plugin.conversations().partnerOf(player.getUniqueId());
        Player target = partner == null ? null : Bukkit.getPlayer(partner);
        if (target == null) {
            plugin.messages().send(player, "no-reply-target");
            return;
        }
        startRecording(player, Kind.PRIVATE, target);
    }

    private void startRecording(Player player, Kind kind, @Nullable Player recipient) {
        Messages messages = plugin.messages();
        UUID playerId = player.getUniqueId();
        TagResolver buttons = Placeholder.component("buttons", messages.get("record-buttons"));

        if (plugin.recordings().isRecording(playerId)) {
            messages.send(player, "already-recording", buttons);
            return;
        }
        String permission = kind == Kind.GLOBAL ? "voicenotes.send" : "voicenotes.send.private";
        if (!player.hasPermission(permission)) {
            messages.send(player, "no-permission");
            return;
        }
        long cooldown = plugin.recordings().cooldownLeftMs(playerId);
        if (cooldown > 0 && !player.hasPermission("voicenotes.bypass-cooldown")) {
            messages.send(player, "cooldown",
                    Placeholder.unparsed("seconds", String.valueOf((cooldown + 999) / 1000)));
            return;
        }
        if (plugin.bridge().api() == null) {
            messages.send(player, "voicechat-not-ready");
            return;
        }
        if (plugin.bridge().usableConnection(playerId) == null) {
            messages.send(player, "no-voicechat");
            return;
        }

        plugin.recordings().start(player, kind, recipient);
        switch (kind) {
            case GLOBAL -> messages.send(player, "recording-started", buttons);
            case REPLY -> messages.send(player, "recording-started-reply", buttons);
            case PRIVATE -> messages.send(player, "recording-started-private", buttons,
                    Placeholder.unparsed("recipient", recipient == null ? "" : recipient.getName()));
        }
    }

    private void play(Player player, String noteId) {
        Messages messages = plugin.messages();
        VoiceNote note = plugin.notes().get(noteId);
        // Private notes look expired to everyone else, so their existence doesn't leak
        if (note == null || !note.canBeHeardBy(player.getUniqueId())) {
            messages.send(player, "expired");
            return;
        }
        VoicechatServerApi api = plugin.bridge().api();
        if (api == null) {
            messages.send(player, "voicechat-not-ready");
            return;
        }
        VoicechatConnection connection = plugin.bridge().usableConnection(player.getUniqueId());
        if (connection == null) {
            messages.send(player, "no-voicechat");
            return;
        }
        if (!plugin.playback().play(api, connection, player.getUniqueId(), note)) {
            messages.send(player, "playback-failed");
            return;
        }
        player.sendActionBar(messages.get("now-playing",
                Placeholder.unparsed("sender", note.senderName()),
                Placeholder.unparsed("duration", VoiceNotesPlugin.formatDuration(note.durationMs())),
                Placeholder.component("wave", plugin.noteFormat().wave(note))));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("to")) {
            return playerNames(sender, args[1]);
        }
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>(List.of("send", "cancel", "reply", "to"));
        if (sender.hasPermission("voicenotes.admin")) options.add("reload");
        options.removeIf(option -> !option.startsWith(prefix));
        options.addAll(playerNames(sender, args[0]));
        return options;
    }

    private static List<String> playerNames(CommandSender sender, String typed) {
        if (!sender.hasPermission("voicenotes.send.private")) return List.of();
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(sender)) continue;
            if (sender instanceof Player p && !p.canSee(online)) continue;
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) names.add(online.getName());
        }
        return names;
    }
}
