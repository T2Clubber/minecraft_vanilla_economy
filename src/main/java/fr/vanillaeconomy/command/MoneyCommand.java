package fr.vanillaeconomy.command;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.currency.CurrencyManager.DepositResult;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** /money drop &lt;montant&gt; and /money deposit. */
public final class MoneyCommand implements TabExecutor {

    private final CurrencyManager currency;
    private final Logger logger;
    private final long maxDrop;
    private final boolean confiscateInvalid;
    private final StaffLog staffLog;

    public MoneyCommand(CurrencyManager currency, Logger logger, long maxDrop, boolean confiscateInvalid, StaffLog staffLog) {
        this.staffLog = staffLog;
        this.currency = currency;
        this.logger = logger;
        this.maxDrop = maxDrop;
        this.confiscateInvalid = confiscateInvalid;
    }

    /** Parses a strictly positive integer amount; returns -1 when invalid. */
    static long parseAmount(String raw) {
        try {
            long value = Long.parseLong(raw);
            return value > 0 ? value : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            Messages.send(sender, "<red>Commande réservée aux joueurs.");
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("drop")) {
            drop(player, args[1]);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("deposit")) {
            deposit(player);
        } else {
            Messages.send(player, "<red>Usage : /money drop <montant> | /money deposit");
        }
        return true;
    }

    private void drop(Player player, String rawAmount) {
        long amount = parseAmount(rawAmount);
        if (amount <= 0) {
            Messages.send(player, "<red>Le montant doit être un entier strictement positif.");
            return;
        }
        if (amount > maxDrop) {
            Messages.send(player, "<red>Maximum <max> par retrait.", Messages.p("max", Messages.coins(maxDrop)));
            return;
        }
        // Freshly minted coins never stack with existing ones (new serial): empty slots are needed.
        int needed = CurrencyManager.stacksNeeded(amount);
        int free = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.isEmpty()) {
                free++;
            }
        }
        if (free < needed) {
            Messages.send(player, "<red>Il vous faut <n> emplacement(s) libre(s) dans l'inventaire.", Messages.p("n", needed));
            return;
        }
        List<ItemStack> coins;
        try {
            coins = currency.withdrawAsCoins(player.getUniqueId(), amount);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Échec de /money drop pour " + player.getName(), e);
            Messages.send(player, "<red>Erreur interne, votre solde n'a pas été modifié.");
            return;
        }
        if (coins.isEmpty()) {
            Messages.send(player, "<red>Solde insuffisant.");
            return;
        }
        var leftovers = player.getInventory().addItem(coins.toArray(ItemStack[]::new));
        // Should not happen (space checked above), but never destroy money.
        leftovers.values().forEach(item -> player.getWorld().dropItem(player.getLocation(), item));
        Messages.send(player, "<gray>Vous avez retiré <gold><amount></gold>.", Messages.p("amount", Messages.coins(amount)));
    }

    private void deposit(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!currency.isCoin(hand)) {
            Messages.send(player, "<red>Tenez des pièces dans votre main principale.");
            return;
        }
        DepositResult result;
        try {
            result = currency.deposit(player.getUniqueId(), hand);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Échec de /money deposit pour " + player.getName(), e);
            Messages.send(player, "<red>Erreur interne, rien n'a été déposé.");
            return;
        }
        int consumed = (int) result.credited();
        if (result.rejected() > 0) {
            alertStaff(player, result, currency.serialOf(hand));
            if (confiscateInvalid) {
                consumed += (int) result.rejected();
            }
        }
        if (consumed >= hand.getAmount()) {
            player.getInventory().setItemInMainHand(null);
        } else if (consumed > 0) {
            hand.setAmount(hand.getAmount() - consumed);
            player.getInventory().setItemInMainHand(hand);
        }
        switch (result.status()) {
            case OK -> Messages.send(player, "<gray>Dépôt de <gold><amount></gold> effectué.",
                    Messages.p("amount", Messages.coins(result.credited())));
            case PARTIAL_DUPLICATE -> Messages.send(player,
                    "<yellow>Seules <gold><amount></gold> ont été acceptées : <red><rejected></red> pièce(s) dupliquée(s) refusée(s).",
                    Messages.p("amount", Messages.coins(result.credited())), Messages.p("rejected", result.rejected()));
            case FORGED, UNKNOWN_SERIAL -> Messages.send(player, "<red>Ces pièces sont falsifiées et ont été refusées.");
            case ALREADY_REDEEMED -> Messages.send(player, "<red>Ces pièces ont déjà été déposées (duplication détectée).");
            case NOT_A_COIN -> Messages.send(player, "<red>Tenez des pièces dans votre main principale.");
        }
    }

    private void alertStaff(Player player, DepositResult result, String serial) {
        String line = "Dépôt suspect de " + player.getName() + " : " + result.status() + ", " + result.rejected()
                + " pièce(s) refusée(s), série " + serial;
        logger.warning(line);
        staffLog.antiDupe(player.getName(), result.status().name(), result.rejected(), serial);
        Bukkit.getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("vanillaeconomy.notify"))
                .forEach(p -> Messages.send(p, "<red><line>", Messages.p("line", line)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("drop", "deposit").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
