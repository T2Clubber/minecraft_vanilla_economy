package fr.vanillaeconomy.command;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.util.Messages;
import fr.vanillaeconomy.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * /eco give|take|set &lt;joueur&gt; &lt;montant&gt;: admin balance changes (tests, rewards,
 * corrections). Works for offline players the server has already seen; every change
 * is written to the server log.
 */
public final class EcoCommand implements TabExecutor {

    /** Guards against typos such as an extra zero turning into an absurd amount. */
    private static final long MAX_AMOUNT = 1_000_000_000L;

    private final CurrencyManager currency;
    private final Logger logger;
    private final StaffLog staffLog;

    public EcoCommand(CurrencyManager currency, Logger logger, StaffLog staffLog) {
        this.staffLog = staffLog;
        this.currency = currency;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (args.length != 3 || !List.of("give", "take", "set").contains(sub)) {
            Messages.send(sender, "<red>Usage : /eco give|take|set <joueur> <montant>");
            return true;
        }
        Optional<UUID> target = Players.resolve(args[1]);
        if (target.isEmpty()) {
            Messages.send(sender, "<red>Joueur inconnu : <white><player></white> (il doit s'être connecté au moins une fois).",
                    Messages.p("player", args[1]));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            amount = -1;
        }
        boolean zeroAllowed = sub.equals("set");
        if (amount < (zeroAllowed ? 0 : 1) || amount > MAX_AMOUNT) {
            Messages.send(sender, "<red>Montant invalide (entier de <min> à <max>).",
                    Messages.p("min", zeroAllowed ? 0 : 1), Messages.p("max", MAX_AMOUNT));
            return true;
        }
        UUID uuid = target.get();
        String name = Players.name(uuid);
        try {
            switch (sub) {
                case "give" -> {
                    long balance = currency.adminGive(uuid, amount);
                    Messages.send(sender, "<green><amount></green> données à <white><player></white>. Nouveau solde : <gold><balance></gold>.",
                            Messages.p("amount", Messages.coins(amount)), Messages.p("player", name),
                            Messages.p("balance", Messages.coins(balance)));
                    notifyTarget(uuid, "<green>Vous avez reçu <gold><amount></gold> de l'administration.",
                            Messages.coins(amount));
                }
                case "take" -> {
                    long removed = currency.adminTake(uuid, amount);
                    Messages.send(sender, "<yellow><amount></yellow> retirées à <white><player></white>. Nouveau solde : <gold><balance></gold>.",
                            Messages.p("amount", Messages.coins(removed)), Messages.p("player", name),
                            Messages.p("balance", Messages.coins(currency.getBalance(uuid))));
                    amount = removed;
                    if (removed > 0) {
                        notifyTarget(uuid, "<yellow>L'administration vous a retiré <gold><amount></gold>.", Messages.coins(removed));
                    }
                }
                default -> {
                    currency.adminSet(uuid, amount);
                    Messages.send(sender, "<green>Solde de <white><player></white> fixé à <gold><amount></gold>.",
                            Messages.p("player", name), Messages.p("amount", Messages.coins(amount)));
                    notifyTarget(uuid, "<yellow>L'administration a fixé votre solde à <gold><amount></gold>.",
                            Messages.coins(amount));
                }
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "/eco " + sub + " " + name + " " + amount + " a échoué", e);
            Messages.send(sender, "<red>Erreur interne, aucun solde n'a été modifié.");
            return true;
        }
        logger.info("[Admin] " + sender.getName() + " : eco " + sub + " " + name + " " + amount);
        try {
            staffLog.admin(sender.getName(), "`/eco " + sub + "` **" + name + "** " + amount + " pièces → solde "
                    + currency.getBalance(uuid) + " pièces");
        } catch (SQLException ignored) {
            staffLog.admin(sender.getName(), "`/eco " + sub + "` **" + name + "** " + amount + " pièces");
        }
        return true;
    }

    private static void notifyTarget(UUID uuid, String text, String amount) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            Messages.send(online, text, Messages.p("amount", amount));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("give", "take", "set"));
        } else if (args.length == 2) {
            Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
        } else if (args.length == 3) {
            options.addAll(List.of("100", "1000", "10000"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
