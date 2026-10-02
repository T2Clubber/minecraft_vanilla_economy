package fr.vanillaeconomy.command;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SoldeCommand implements CommandExecutor {

    private final CurrencyManager currency;
    private final Logger logger;

    public SoldeCommand(CurrencyManager currency, Logger logger) {
        this.currency = currency;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "<red>Commande réservée aux joueurs.");
            return true;
        }
        try {
            long balance = currency.getBalance(player.getUniqueId());
            Messages.send(player, "<gray>Votre solde : <gold><amount></gold>", Messages.p("amount", Messages.coins(balance)));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Lecture du solde impossible", e);
            Messages.send(player, "<red>Erreur interne, réessayez plus tard.");
        }
        return true;
    }
}
