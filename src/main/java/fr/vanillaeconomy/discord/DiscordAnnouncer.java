package fr.vanillaeconomy.discord;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Public announcements (webhook of the promotions channel), under the name "Nitwit":
 * upcoming promotional rotations.
 */
public final class DiscordAnnouncer {

    private static final int PROMO_COLOR = 0xF1C40F;

    private final DiscordWebhook webhook;

    public DiscordAnnouncer(ConfigurationSection cfg, Logger logger) {
        this.webhook = new DiscordWebhook(
                cfg == null ? "" : cfg.getString("webhook_url", ""),
                cfg == null ? "Nitwit" : cfg.getString("username", "Nitwit"),
                cfg == null ? "" : cfg.getString("avatar_url", ""),
                cfg == null ? "" : cfg.getString("mention_role_id", ""),
                "discord.webhook_url", logger);
    }

    public boolean enabled() {
        return webhook.enabled();
    }

    public CompletableFuture<Integer> send(String title, String description, int color) {
        return webhook.send(title, description, color);
    }

    /**
     * Announces that the next rotation is a promotion. The items are not known yet (they
     * are drawn when the rotation starts), only the discount and the time.
     */
    public void announceUpcomingPromo(int percent, long startMillis, long durationMillis, ZoneId zone) {
        DateTimeFormatter hour = DateTimeFormatter.ofPattern("HH'h'mm");
        String start = hour.format(Instant.ofEpochMilli(startMillis).atZone(zone));
        String end = hour.format(Instant.ofEpochMilli(startMillis + durationMillis).atZone(zone));
        long hours = Math.max(1, Math.round(durationMillis / 3_600_000.0));
        webhook.send("🏷️ Promotion à venir : -" + percent + " % !",
                "Dans **" + hours + " h**, de **" + start + "** à **" + end + "**, les idiots du village passent en "
                        + "**PROMO jusqu'à -" + percent + " %** :\n"
                        + "• ils **paieront plus cher** ce que vous leur vendez ;\n"
                        + "• ils **vendront moins cher** leurs articles.\n\n"
                        + "Le détail des articles sera visible en jeu à l'ouverture des nouveaux étals.",
                PROMO_COLOR);
    }
}
