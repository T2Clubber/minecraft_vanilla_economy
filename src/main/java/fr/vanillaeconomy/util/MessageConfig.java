package fr.vanillaeconomy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Texts of messages.yml (MiniMessage). Missing keys fall back to the defaults shipped in
 * the jar. {@code {name}} variables are injected as unparsed placeholders, so a player or
 * city name can never inject formatting.
 */
public final class MessageConfig {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final YamlConfiguration yaml;
    private final String section;

    public MessageConfig(Plugin plugin, String section) {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.yaml = YamlConfiguration.loadConfiguration(file);
        var defaults = plugin.getResource("messages.yml");
        if (defaults != null) {
            yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        this.section = section;
    }

    /** Key/value pairs: "city", "Paris", "owner", "Bob"... */
    public Component get(String key, Object... vars) {
        String raw = yaml.getString(section + "." + key, "<red>[message manquant : " + section + "." + key + "]");
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i + 1 < vars.length; i += 2) {
            String name = String.valueOf(vars[i]);
            raw = raw.replace("{" + name + "}", "<" + name + ">");
            resolvers.add(Placeholder.unparsed(name, String.valueOf(vars[i + 1])));
        }
        return MM.deserialize(raw, TagResolver.resolver(resolvers));
    }

    /** Chat message with the section prefix. */
    public void send(CommandSender to, String key, Object... vars) {
        to.sendMessage(get("prefix").append(get(key, vars)));
    }

    public void actionBar(Player to, String key, Object... vars) {
        to.sendActionBar(get(key, vars));
    }
}
