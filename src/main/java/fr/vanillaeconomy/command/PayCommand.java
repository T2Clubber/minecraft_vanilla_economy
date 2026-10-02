package fr.vanillaeconomy.command;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** /pay &lt;joueur&gt; &lt;montant&gt; — same world and within max_distance blocks, atomic transfer. */
public final class PayCommand implements TabExecutor {

    private final CurrencyManager currency;
    private final Logger logger;
    private final double maxDistance;

    public PayCommand(CurrencyManager currency, Logger logger, double maxDistance) {
        this.currency = currency;
        this.logger = logger;
        this.maxDistance = maxDistance;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player payer)) {
            Messages.send(sender, "<red>Commande réservée aux joueurs.");
            return true;
        }
        if (args.length != 2) {
            Messages.send(payer, "<red>Usage : /pay <joueur> <montant>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            Messages.send(payer, "<red>Joueur introuvable ou hors ligne.");
            return true;
        }
        if (target.equals(payer)) {
            Messages.send(payer, "<red>Vous ne pouvez pas vous payer vous-même.");
            return true;
        }
        long amount = MoneyCommand.parseAmount(args[1]);
        if (amount <= 0) {
            Messages.send(payer, "<red>Le montant doit être un entier strictement positif.");
            return true;
        }
        if (!payer.getWorld().equals(target.getWorld())) {
            Messages.send(payer, "<red><target> n'est pas dans le même monde que vous.", Messages.p("target", target.getName()));
            return true;
        }
        if (payer.getLocation().distanceSquared(target.getLocation()) > maxDistance * maxDistance) {
            Messages.send(payer, "<red><target> est trop loin (<max> blocs maximum).",
                    Messages.p("target", target.getName()), Messages.p("max", (int) maxDistance));
            return true;
        }
        try {
            if (!currency.transfer(payer.getUniqueId(), target.getUniqueId(), amount)) {
                Messages.send(payer, "<red>Solde insuffisant.");
                return true;
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Échec du transfert " + payer.getName() + " -> " + target.getName(), e);
            Messages.send(payer, "<red>Erreur interne, aucun argent n'a été transféré.");
            return true;
        }
        Messages.send(payer, "<gray>Vous avez envoyé <gold><amount></gold> à <white><target></white>.",
                Messages.p("amount", Messages.coins(amount)), Messages.p("target", target.getName()));
        Messages.send(target, "<white><payer></white> <gray>vous a envoyé <gold><amount></gold>.",
                Messages.p("amount", Messages.coins(amount)), Messages.p("payer", payer.getName()));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            double maxSq = maxDistance * maxDistance;
            return player.getWorld().getPlayers().stream()
                    .filter(p -> !p.equals(player) && p.getLocation().distanceSquared(player.getLocation()) <= maxSq)
                    .map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
