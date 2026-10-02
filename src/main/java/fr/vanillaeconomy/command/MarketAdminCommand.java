package fr.vanillaeconomy.command;

import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;

/** /marketadmin rotate [promo [%]] | promos | info &lt;item&gt; | setstock &lt;item&gt; &lt;quantité&gt; */
public final class MarketAdminCommand implements TabExecutor {

    private final MarketManager market;

    public MarketAdminCommand(MarketManager market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length >= 1 && args.length <= 3 && args[0].equalsIgnoreCase("rotate")) {
            Integer forced = null;
            if (args.length >= 2) {
                if (!args[1].equalsIgnoreCase("promo")) {
                    Messages.send(sender, "<red>Usage : /marketadmin rotate [promo [pourcentage]]");
                    return true;
                }
                forced = args.length == 3 ? parsePercent(args[2]) : 5 + new java.util.Random().nextInt(46);
                if (forced == null) {
                    Messages.send(sender, "<red>Le pourcentage doit être un entier entre 1 et 99.");
                    return true;
                }
            }
            market.runCycle(true, forced);
            Messages.send(sender, "<green>Rotation forcée (cycle n°<n>)<promo>.", Messages.p("n", market.cycle()),
                    Messages.p("promo", market.promoPercent() > 0 ? " avec PROMO -" + market.promoPercent() + " %" : ""));
        } else if (args.length == 1 && args[0].equalsIgnoreCase("promos")) {
            try {
                Messages.send(sender, "<gray>Promotions prévues : <white><plan>", Messages.p("plan", market.upcomingPromotions()));
            } catch (java.sql.SQLException e) {
                Messages.send(sender, "<red>Lecture du planning impossible.");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            Material material = Material.matchMaterial(args[1]);
            Messages.send(sender, "<gray><info>", Messages.p("info",
                    material == null ? "Item inconnu : " + args[1] : market.describe(material)));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("setstock")) {
            Material material = Material.matchMaterial(args[1]);
            long stock;
            try {
                stock = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                stock = -1;
            }
            if (material != null && market.setStock(material, stock)) {
                Messages.send(sender, "<green>Stock de <item> fixé à <n>.", Messages.p("item", material.name()), Messages.p("n", stock));
            } else {
                Messages.send(sender, "<red>Item absent d'items.yml ou quantité invalide.");
            }
        } else {
            Messages.send(sender, "<red>Usage : /marketadmin rotate [promo [pourcentage]] | promos | info <item> | setstock <item> <quantité>");
        }
        return true;
    }

    private static Integer parsePercent(String raw) {
        try {
            int pct = Integer.parseInt(raw);
            return pct >= 1 && pct <= 99 ? pct : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("rotate", "promos", "info", "setstock").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rotate")) {
            return List.of("promo");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("setstock"))) {
            String prefix = args[1].toUpperCase();
            return Arrays.stream(Material.values())
                    .filter(m -> market.item(m) != null && m.name().startsWith(prefix))
                    .map(Material::name).toList();
        }
        return List.of();
    }
}
