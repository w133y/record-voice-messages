package io.github.w133y.voicenotes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Builds the chat line for a voice note from the "note-format" config section.
 * A line is a MiniMessage template made of elements (&lt;sender&gt;, &lt;play&gt;, &lt;wave&gt;...),
 * and every element has its own text, hover and click action.
 */
final class NoteFormat {

    private record Element(String text, @Nullable String hover, @Nullable String click) {
    }

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_-]+");
    private static final String DEFAULT_CHARS = "▁▂▃▄▅▆▇█";

    private final Map<String, String> lines = new LinkedHashMap<>();
    private final Map<String, Element> elements = new LinkedHashMap<>();
    private final int[] glyphs;
    private final List<TextColor> colors = new ArrayList<>();
    private final Component prefix;

    NoteFormat(FileConfiguration config, Component prefix, Logger logger) {
        this.prefix = prefix;

        ConfigurationSection root = config.getConfigurationSection("note-format");
        if (root != null) {
            for (String key : root.getKeys(false)) {
                if (root.isString(key)) lines.put(key.toLowerCase(Locale.ROOT), root.getString(key));
            }
            ConfigurationSection section = root.getConfigurationSection("elements");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    String name = key.toLowerCase(Locale.ROOT);
                    if (!VALID_NAME.matcher(name).matches()) {
                        logger.warning("Skipping element '" + key + "': use only a-z, 0-9, _ and -");
                        continue;
                    }
                    ConfigurationSection element = section.getConfigurationSection(key);
                    if (element == null) continue;
                    elements.put(name, new Element(element.getString("text", ""),
                            element.getString("hover"), element.getString("click")));
                }
            }
        }

        String chars = config.getString("waveform.chars", DEFAULT_CHARS);
        int[] parsed = (chars == null ? "" : chars).codePoints().toArray();
        this.glyphs = parsed.length > 0 ? parsed : DEFAULT_CHARS.codePoints().toArray();

        for (String value : config.getStringList("waveform.colors")) {
            TextColor color = parseColor(value);
            if (color != null) colors.add(color);
            else logger.warning("Unknown waveform color '" + value + "'");
        }
    }

    /** Renders one of the configured lines (global, private, private-sent, token) for a note. */
    Component line(String key, VoiceNote note) {
        String template = lines.get(key);
        if (template == null) return Component.text("[missing note-format." + key + "]");

        Component wave = wave(note);
        TagResolver.Builder resolvers = TagResolver.builder()
                .resolver(Placeholder.component("prefix", prefix));
        for (Map.Entry<String, Element> entry : elements.entrySet()) {
            resolvers.resolver(Placeholder.component(entry.getKey(), element(entry.getValue(), note, wave)));
        }
        return MINI_MESSAGE.deserialize(template, resolvers.build());
    }

    /** The waveform alone, colored per bar when waveform.colors is set. */
    Component wave(VoiceNote note) {
        float[] levels = note.waveform();
        if (levels.length == 0) return Component.empty();

        TextComponent.Builder out = Component.text();
        StringBuilder run = new StringBuilder();
        TextColor runColor = null;
        for (float level : levels) {
            int glyph = glyphs[Math.round(level * (glyphs.length - 1))];
            TextColor color = colors.isEmpty() ? null : colors.get(Math.round(level * (colors.size() - 1)));
            // Merge neighbours with the same color to keep the packet small
            if (run.length() > 0 && !Objects.equals(color, runColor)) {
                out.append(Component.text(run.toString(), runColor));
                run.setLength(0);
            }
            runColor = color;
            run.appendCodePoint(glyph);
        }
        if (run.length() > 0) out.append(Component.text(run.toString(), runColor));
        return out.build();
    }

    private Component element(Element element, VoiceNote note, Component wave) {
        TagResolver waveTag = Placeholder.component("wave", wave);
        Component component = MINI_MESSAGE.deserialize(fill(element.text(), note), waveTag);
        if (element.hover() != null && !element.hover().isEmpty()) {
            component = component.hoverEvent(HoverEvent.showText(
                    MINI_MESSAGE.deserialize(fill(element.hover(), note), waveTag)));
        }
        ClickEvent click = click(element.click(), note);
        if (click != null) component = component.clickEvent(click);
        return component;
    }

    /**
     * Click actions: play, reply, stop, none,
     * run:/command, suggest:/command, url:https://..., copy:text
     */
    private static @Nullable ClickEvent click(@Nullable String action, VoiceNote note) {
        if (action == null || action.isBlank()) return null;
        String value = fill(action.trim(), note);
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.equals("play")) return ClickEvent.runCommand("/voicenote play " + note.id());
        if (lower.equals("reply")) return ClickEvent.runCommand("/voicenote to " + note.senderName());
        if (lower.equals("stop")) return ClickEvent.runCommand("/voicenote stopplay");
        if (lower.startsWith("run:")) return ClickEvent.runCommand(value.substring(4));
        if (lower.startsWith("suggest:")) return ClickEvent.suggestCommand(value.substring(8));
        if (lower.startsWith("url:")) return ClickEvent.openUrl(value.substring(4));
        if (lower.startsWith("copy:")) return ClickEvent.copyToClipboard(value.substring(5));
        return null;
    }

    /** Fills {sender}, {recipient}, {duration} and {id} inside element texts and actions. */
    private static String fill(String text, VoiceNote note) {
        String recipient = note.recipientName() == null ? "" : note.recipientName();
        return text.replace("{sender}", note.senderName())
                .replace("{recipient}", recipient)
                .replace("{duration}", VoiceNotesPlugin.formatDuration(note.durationMs()))
                .replace("{id}", note.id());
    }

    private static @Nullable TextColor parseColor(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) return TextColor.fromHexString(trimmed);
        return NamedTextColor.NAMES.value(trimmed.toLowerCase(Locale.ROOT));
    }
}
