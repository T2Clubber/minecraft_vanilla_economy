package fr.vanillaeconomy.discord;

import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * Staff journal posted to #logs-serveur through its own webhook (discord.logs_webhook_url):
 * anti-dupe alerts, admin commands, city life cycle, large payments, server start / stop.
 * Every method is a no-op when the webhook is not configured.
 */
public final class StaffLog {

    private static final int RED = 0xE74C3C;
    private static final int ORANGE = 0xE67E22;
    private static final int TEAL = 0x1ABC9C;
    private static final int BLUE = 0x3498DB;
    private static final int GREY = 0x95A5A6;

    private final DiscordWebhook webhook;
    private final long payThreshold;

    public StaffLog(ConfigurationSection cfg, Logger logger) {
        this.webhook = new DiscordWebhook(
                cfg == null ? "" : cfg.getString("logs_webhook_url", ""),
                cfg == null ? "Nitwit" : cfg.getString("username", "Nitwit") + " · Logs",
                cfg == null ? "" : cfg.getString("avatar_url", ""),
                "", "discord.logs_webhook_url", logger);
        this.payThreshold = cfg == null ? 1000 : Math.max(1, cfg.getLong("logs_pay_threshold", 1000));
    }

    public boolean enabled() {
        return webhook.enabled();
    }

    /** Forged or duplicated coins refused at deposit. */
    public void antiDupe(String player, String status, long rejected, String serial) {
        webhook.send("🚨 Dépôt de pièces suspect",
                "**Joueur :** " + player + "\n**Motif :** " + status + "\n**Pièces refusées :** " + rejected
                        + "\n**Série :** `" + serial + "`", RED);
    }

    /** An admin command changing money, the market or a city. */
    public void admin(String actor, String action) {
        webhook.send("🛠️ Action d'administration", "**Par :** " + actor + "\n" + action, ORANGE);
    }

    public void city(String title, String details) {
        webhook.send("🏰 " + title, details, TEAL);
    }

    /** Only payments of at least {@code logs_pay_threshold} coins are logged. */
    public void pay(String from, String to, long amount) {
        if (amount >= payThreshold) {
            webhook.send("💸 Gros transfert", "**" + from + "** → **" + to + "** : " + amount + " pièces", BLUE);
        }
    }

    public void serverStarted(String details) {
        webhook.send("🟢 Serveur démarré", details, GREY);
    }

    /** Waits a few seconds: the server is stopping. */
    public void serverStopped() {
        if (enabled()) {
            webhook.sendAndWait("🔴 Serveur arrêté", "Arrêt du serveur Minecraft.", GREY);
        }
    }
}
