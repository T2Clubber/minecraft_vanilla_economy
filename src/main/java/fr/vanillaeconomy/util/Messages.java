package fr.vanillaeconomy.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** Small MiniMessage helper. All player-facing text is French. */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gold>Marché</gold>]</dark_gray> ";

    private Messages() {
    }

    public static Component parse(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers);
    }

    /** Parses text meant for item names / lore (no default italic). */
    public static Component item(String miniMessage, TagResolver... resolvers) {
        return MM.deserialize(miniMessage, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void send(CommandSender to, String miniMessage, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(PREFIX + miniMessage, resolvers));
    }

    public static TagResolver p(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver p(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** "1 pièce" / "12 pièces". */
    public static String coins(long amount) {
        return amount + (Math.abs(amount) > 1 ? " pièces" : " pièce");
    }

    /** Unit price with enough precision to be meaningful for cheap items. */
    public static String unitPrice(double unit) {
        if (unit >= 10) {
            return String.format(Locale.FRANCE, "%.1f", unit);
        }
        if (unit >= 1) {
            return String.format(Locale.FRANCE, "%.2f", unit);
        }
        return String.format(Locale.FRANCE, "%.4f", unit);
    }
}
