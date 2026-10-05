package fr.vanillaeconomy.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** One Discord channel webhook: posts embeds asynchronously with java.net.http (no library, no bot). */
public final class DiscordWebhook {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Logger logger;
    private final String url;
    private final String username;
    private final String avatarUrl;
    private final String roleId;

    /**
     * @param url     webhook URL, or empty / invalid to disable
     * @param roleId  role mentioned with each message, or empty
     */
    public DiscordWebhook(String url, String username, String avatarUrl, String roleId, String configKey, Logger logger) {
        this.logger = logger;
        String u = url == null ? "" : url.trim();
        if (!u.isEmpty() && !u.startsWith("https://discord.com/api/webhooks/")
                && !u.startsWith("https://discordapp.com/api/webhooks/")) {
            logger.warning(configKey + " ne ressemble pas à un webhook Discord : envoi désactivé.");
            u = "";
        }
        this.url = u;
        this.username = username == null || username.isBlank() ? "Nitwit" : username;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl.trim();
        String role = roleId == null ? "" : roleId.trim();
        this.roleId = role.matches("\\d{5,25}") ? role : "";
    }

    public boolean enabled() {
        return !url.isEmpty();
    }

    /** Completes with the HTTP status, or -1 on network error / disabled. Never blocks the caller. */
    public CompletableFuture<Integer> send(String title, String description, int color) {
        if (!enabled()) {
            return CompletableFuture.completedFuture(-1);
        }
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        embed.addProperty("timestamp", Instant.now().toString());
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
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        logger.warning("Message Discord non envoyé (" + title + ") : " + error.getMessage());
                        return -1;
                    }
                    if (response.statusCode() >= 300) {
                        logger.warning("Message Discord refusé (HTTP " + response.statusCode() + ") : " + response.body());
                    }
                    return response.statusCode();
                });
    }

    /** For shutdown only: waits at most a few seconds so the message leaves before the JVM stops. */
    public void sendAndWait(String title, String description, int color) {
        try {
            send(title, description, color).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("Message Discord d'arrêt non confirmé : " + e.getMessage());
        }
    }
}
