package io.github.w133y.voicenotes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/** MiniMessage-formatted messages from config.yml. */
final class Messages {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final ConfigurationSection section;
    private final String prefix;

    Messages(FileConfiguration config) {
        ConfigurationSection loaded = config.getConfigurationSection("messages");
        this.section = loaded != null ? loaded : new YamlConfiguration();
        this.prefix = section.getString("prefix", "");
    }

    Component prefix() {
        return miniMessage.deserialize(prefix);
    }

    Component get(String key, TagResolver... placeholders) {
        return miniMessage.deserialize(raw(key), placeholders);
    }

    Component prefixed(String key, TagResolver... placeholders) {
        return miniMessage.deserialize(prefix + raw(key), placeholders);
    }

    void send(CommandSender target, String key, TagResolver... placeholders) {
        target.sendMessage(prefixed(key, placeholders));
    }

    private String raw(String key) {
        return section.getString(key, "<red>Missing message: " + key + "</red>");
    }
}
