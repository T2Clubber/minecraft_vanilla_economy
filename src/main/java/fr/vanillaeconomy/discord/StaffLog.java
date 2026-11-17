package fr.vanillaeconomy.discord;

import fr.vanillaeconomy.util.ServerTime;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Staff journal posted to #logs-serveur through its own webhook (discord.logs_webhook_url):
 * anti-dupe alerts, admin commands, city life cycle, large payments, server start / stop,
 * a journal of every other successful plugin command and a journal of the connections. Every
 * method is a no-op when the webhook is not configured.
 */
public final class StaffLog {

    private static final int RED = 0xE74C3C;
    private static final int ORANGE = 0xE67E22;
    private static final int TEAL = 0x1ABC9C;
    private static final int BLUE = 0x3498DB;
    private static final int GREY = 0x95A5A6;
    private static final int PURPLE = 0x9B59B6;
    private static final int GREEN = 0x2ECC71;
    /** Below Discord's 4096 characters per embed description. */
    private static final int MAX_JOURNAL = 3800;
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final DiscordWebhook webhook;
    private final long payThreshold;
    private final boolean logCommands;
    private final boolean logConnections;
    private final List<String> journal = new ArrayList<>();
    private final List<String> connections = new ArrayList<>();

    public StaffLog(ConfigurationSection cfg, Logger logger) {
        this.webhook = new DiscordWebhook(
                cfg == null ? "" : cfg.getString("logs_webhook_url", ""),
                cfg == null ? "Nitwit" : cfg.getString("username", "Nitwit") + " · Logs",
                cfg == null ? "" : cfg.getString("avatar_url", ""),
                "", "discord.logs_webhook_url", logger);
        this.payThreshold = cfg == null ? 1000 : Math.max(1, cfg.getLong("logs_pay_threshold", 1000));
        this.logCommands = cfg == null || cfg.getBoolean("logs_commands", true);
        this.logConnections = cfg == null || cfg.getBoolean("logs_connections", true);
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

    /** Payments of at least {@code logs_pay_threshold} coins get their own message, the others go to the journal. */
    public void pay(CommandSender sender, Command command, String[] args, String to, long amount) {
        if (amount >= payThreshold) {
            webhook.send("💸 Gros transfert", "**" + md(sender.getName()) + "** → **" + md(to) + "** : " + amount + " pièces", BLUE);
        } else {
            command(sender, command, args, amount + " pièces envoyées à " + md(to));
        }
    }

    /**
     * A successful plugin command that has no message of its own (admin actions, city
     * foundation / expansion / dissolution and large payments keep theirs). Lines are
     * grouped into one message every few seconds ({@link #flushJournal}) so that busy
     * hours never hit the webhook rate limit.
     */
    public void command(CommandSender sender, Command command, String[] args, String result) {
        if (!enabled() || !logCommands) {
            return;
        }
        action(sender, "/" + command.getName() + (args.length == 0 ? "" : " " + String.join(" ", args)), result);
    }

    /** Same journal, for an action made through an interface ({@code what} describes it). */
    public void action(CommandSender sender, String line, String result) {
        if (!enabled() || !logCommands) {
            return;
        }
        journal.add("`" + now() + "` **" + md(sender.getName()) + "** `"
                + line.replace('`', '\'') + "` → " + result);
    }

    // ------------------------------------------------------------------
    // Connections (grouped like the commands)
    // ------------------------------------------------------------------

    /** A player joined; {@code online}: players online, him included. */
    public void joined(String player, boolean firstJoin, int online) {
        if (enabled() && logConnections) {
            connections.add(joinLine(now(), player, firstJoin, online));
        }
    }

    /** A player left; {@code reason} null for a normal disconnection, {@code online}: players still online. */
    public void left(String player, long sessionMillis, String reason, int online) {
        if (enabled() && logConnections) {
            connections.add(quitLine(now(), player, sessionMillis, reason, online));
        }
    }

    static String joinLine(String hour, String player, boolean firstJoin, int online) {
        return "`" + hour + "` 🟢 **" + md(player) + "** connexion" + (firstJoin ? " *(première connexion)*" : "")
                + " · " + online + " en ligne";
    }

    /** {@code reason}: null for a normal disconnection, else why the player left (kick, timeout). */
    static String quitLine(String hour, String player, long sessionMillis, String reason, int online) {
        return "`" + hour + "` 🔴 **" + md(player) + "** " + (reason == null ? "déconnexion" : md(reason))
                + " après " + fr.vanillaeconomy.util.Messages.duration(sessionMillis) + " · " + online + " en ligne";
    }

    private static String now() {
        return LocalTime.now(ServerTime.zone()).format(HOUR);
    }

    // ------------------------------------------------------------------
    // Posting
    // ------------------------------------------------------------------

    /** Posts the pending journal lines (main thread, every few seconds and at shutdown). */
    public void flushJournal(boolean wait) {
        flush(journal, "📜 Commandes", PURPLE, wait);
        flush(connections, "👥 Connexions", GREEN, wait);
    }

    private void flush(List<String> lines, String title, int colour, boolean wait) {
        StringBuilder chunk = new StringBuilder();
        for (String line : lines) {
            if (!chunk.isEmpty() && chunk.length() + line.length() + 1 > MAX_JOURNAL) {
                post(title, chunk.toString(), colour, wait);
                chunk.setLength(0);
            }
            chunk.append(chunk.isEmpty() ? "" : "\n").append(line.length() > MAX_JOURNAL ? line.substring(0, MAX_JOURNAL) : line);
        }
        if (!chunk.isEmpty()) {
            post(title, chunk.toString(), colour, wait);
        }
        lines.clear();
    }

    private void post(String title, String text, int colour, boolean wait) {
        if (wait) {
            webhook.sendAndWait(title, text, colour);
        } else {
            webhook.send(title, text, colour);
        }
    }

    /** Player and city names may contain Discord markdown characters (an underscore italicises). */
    public static String md(String text) {
        return text.replaceAll("([\\\\_*~`|>])", "\\\\$1");
    }

    public void serverStarted(String details) {
        webhook.send("🟢 Serveur démarré", details, GREY);
    }

    /** Waits a few seconds: the server is stopping. */
    public void serverStopped() {
        if (enabled()) {
            flushJournal(true);
            webhook.sendAndWait("🔴 Serveur arrêté", "Arrêt du serveur Minecraft.", GREY);
        }
    }
}
