package fr.vanillaeconomy.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discord announcements through a channel webhook (no bot, no hosting): the plugin posts
 * directly to the URL configured in config.yml, asynchronously, under the name "Nitwit".
 */
public final class DiscordAnnouncer {

    private static final int PROMO_COLOR = 0xF1C40F;

    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String url;
    private final String username;
    private final String avatarUrl;
    private final String roleId;

    public DiscordAnnouncer(ConfigurationSection cfg, Logger logger) {
        this.logger = logger;
        String configured = cfg == null ? "" : cfg.getString("webhook_url", "").trim();
        if (!configured.isEmpty() && !configured.startsWith("https://discord.com/api/webhooks/")
                && !configured.startsWith("https://discordapp.com/api/webhooks/")) {
            logger.warning("discord.webhook_url ne ressemble pas à un webhook Discord : annonces désactivées.");
            configured = "";
        }
        this.url = configured;
        this.username = cfg == null ? "Nitwit" : cfg.getString("username", "Nitwit");
        this.avatarUrl = cfg == null ? "" : cfg.getString("avatar_url", "").trim();
        String role = cfg == null ? "" : cfg.getString("mention_role_id", "").trim();
        this.roleId = role.matches("\\d{5,25}") ? role : "";
    }

    public boolean enabled() {
        return !url.isEmpty();
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
        send("🏷️ Promotion à venir : -" + percent + " % !",
                "Dans **" + hours + " h**, de **" + start + "** à **" + end + "**, les idiots du village passent en "
                        + "**PROMO jusqu'à -" + percent + " %** :\n"
                        + "• ils **paieront plus cher** ce que vous leur vendez ;\n"
                        + "• ils **vendront moins cher** leurs articles.\n\n"
                        + "Le détail des articles sera visible en jeu à l'ouverture des nouveaux étals.",
                PROMO_COLOR);
    }

    /** Posts an embed; completes with the HTTP status (or -1 on network error). Never blocks the caller. */
    public CompletableFuture<Integer> send(String title, String description, int color) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(-1);
        }
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        if (!avatarUrl.isEmpty()) {
            body.addProperty("avatar_url", avatarUrl);
        }
        body.add("embeds", embeds);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        if (!roleId.isEmpty()) {
            body.addProperty("content", "<@&" + roleId + ">");
            JsonArray roles = new JsonArray();
            roles.add(roleId);
            mentions.add("roles", roles);
        }
        body.add("allowed_mentions", mentions);

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        logger.log(Level.WARNING, "Annonce Discord non envoyée : " + error.getMessage());
                        return -1;
                    }
                    if (response.statusCode() >= 300) {
                        logger.warning("Annonce Discord refusée (HTTP " + response.statusCode() + ") : " + response.body());
                    }
                    return response.statusCode();
                });
    }
}
