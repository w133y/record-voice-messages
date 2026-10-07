package io.github.w133y.voicenotes;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * VoiceNotes - voice messages in chat, recorded through Simple Voice Chat.
 * Works on Paper, Purpur and Folia: only async and entity schedulers are used.
 *
 * @author w1z4r_d
 */
public final class VoiceNotesPlugin extends JavaPlugin implements Listener {

    // Volatile because /vn reload swaps them while other threads read them
    private volatile Settings settings;
    private volatile Messages messages;
    private volatile NoteFormat noteFormat;
    private NoteStore notes;
    private RecordingManager recordings;
    private PlaybackManager playback;
    private VoicechatBridge bridge;
    private ConversationTracker conversations;
    private NoteTokens tokens;
    // Kept as a Runnable so this class never references PacketEvents types directly
    private @Nullable Runnable packetUnhook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().severe("Simple Voice Chat service not found, disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        notes = new NoteStore();
        playback = new PlaybackManager();
        recordings = new RecordingManager(this);
        bridge = new VoicechatBridge(this);
        service.registerPlugin(bridge);
        conversations = new ConversationTracker(this);
        tokens = new NoteTokens(this);

        if (getServer().getPluginManager().isPluginEnabled("packetevents")) {
            packetUnhook = PacketEventsHook.register(tokens);
            getLogger().info("PacketEvents found, [vn:id] tokens in chat will be clickable.");
        }
        warnIfCommandDeliveryUnavailable();

        VoiceNoteCommand command = new VoiceNoteCommand(this);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("voicenote"));
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(conversations, this);

        // Action bar updates and recording time limits; player work is handed to entity schedulers
        getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> recordings.tick(), 250, 250, TimeUnit.MILLISECONDS);
        // Drop expired notes once a minute
        getServer().getAsyncScheduler().runAtFixedRate(this,
                task -> notes.purge(settings.expireAfterMs()), 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public void onDisable() {
        getServer().getAsyncScheduler().cancelTasks(this);
        if (packetUnhook != null) packetUnhook.run();
        if (recordings != null) recordings.clear();
        if (playback != null) playback.shutdown();
    }

    void reload() {
        reloadConfig();
        loadConfigValues();
        warnIfCommandDeliveryUnavailable();
    }

    /** True when private notes should go through the chat plugin command. */
    boolean commandDelivery() {
        return settings.commandDelivery() && packetUnhook != null;
    }

    private void warnIfCommandDeliveryUnavailable() {
        if (settings.commandDelivery() && packetUnhook == null) {
            getLogger().warning("private.delivery is 'command' but PacketEvents is not installed, using 'internal'.");
        }
    }

    private void loadConfigValues() {
        settings = Settings.from(getConfig());
        messages = new Messages(getConfig());
        noteFormat = new NoteFormat(getConfig(), messages.prefix(), getLogger());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        recordings.cancel(event.getPlayer().getUniqueId());
        playback.stop(event.getPlayer().getUniqueId());
    }

    /** Posts the note to chat using the configured lines. Sending messages is thread-safe. */
    void announce(VoiceNote note, Player sender, @Nullable Player recipient) {
        NoteFormat format = noteFormat;
        if (recipient == null) {
            Component line = format.line("global", note);
            Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(line));
            Bukkit.getConsoleSender().sendMessage(line);
        } else {
            recipient.sendMessage(format.line("private", note));
            sender.sendMessage(format.line("private-sent", note));
        }
    }

    static String formatDuration(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    Settings settings() { return settings; }
    Messages messages() { return messages; }
    NoteFormat noteFormat() { return noteFormat; }
    NoteStore notes() { return notes; }
    RecordingManager recordings() { return recordings; }
    PlaybackManager playback() { return playback; }
    VoicechatBridge bridge() { return bridge; }
    ConversationTracker conversations() { return conversations; }
}
