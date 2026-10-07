package io.github.w133y.voicenotes;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers who each player last talked to in private, for /vn reply in internal mode.
 * Watches the PM commands of any chat plugin, so it doesn't depend on a specific one.
 */
final class ConversationTracker implements Listener {

    private final VoiceNotesPlugin plugin;
    private final Map<UUID, UUID> partners = new ConcurrentHashMap<>();

    ConversationTracker(VoiceNotesPlugin plugin) {
        this.plugin = plugin;
    }

    void link(UUID a, UUID b) {
        partners.put(a, b);
        partners.put(b, a);
    }

    @Nullable UUID partnerOf(UUID playerId) {
        return partners.get(playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().substring(1).split(" ");
        if (parts.length < 2) return;

        String label = parts[0].toLowerCase(Locale.ROOT);
        int namespace = label.indexOf(':');
        if (namespace >= 0) label = label.substring(namespace + 1);

        Settings settings = plugin.settings();
        UUID sender = event.getPlayer().getUniqueId();

        if (settings.trackCommands().contains(label)) {
            // /msg <player> <text...>
            if (parts.length < 3) return;
            Player target = Bukkit.getPlayerExact(parts[1]);
            if (target != null && !target.equals(event.getPlayer())) link(sender, target.getUniqueId());
        } else if (settings.trackReplyCommands().contains(label)) {
            // /r <text...> keeps the sender's partner and makes the receiver reply back to the sender
            UUID target = partners.get(sender);
            if (target != null) partners.put(target, sender);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        partners.remove(event.getPlayer().getUniqueId());
    }
}
