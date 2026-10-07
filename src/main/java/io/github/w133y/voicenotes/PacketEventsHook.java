package io.github.w133y.voicenotes;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import net.kyori.adventure.text.Component;

/**
 * Rewrites outgoing system chat messages, replacing [vn:id] tokens with clickable buttons.
 * Chat plugins send private messages as system messages, so this works with any of them.
 * Only loaded when PacketEvents is installed.
 */
final class PacketEventsHook extends PacketListenerAbstract {

    private final NoteTokens tokens;

    private PacketEventsHook(NoteTokens tokens) {
        super(PacketListenerPriority.NORMAL);
        this.tokens = tokens;
    }

    /** Registers the listener and returns an action that unregisters it. */
    static Runnable register(NoteTokens tokens) {
        PacketEventsHook hook = new PacketEventsHook(tokens);
        PacketEvents.getAPI().getEventManager().registerListener(hook);
        return () -> PacketEvents.getAPI().getEventManager().unregisterListener(hook);
    }

    // Runs on netty threads
    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.SYSTEM_CHAT_MESSAGE) return;
        WrapperPlayServerSystemChatMessage packet = new WrapperPlayServerSystemChatMessage(event);
        Component original = packet.getMessage();
        if (original == null) return;
        Component rendered = tokens.render(original);
        if (rendered != original) {
            packet.setMessage(rendered);
            event.markForReEncode(true);
        }
    }
}
