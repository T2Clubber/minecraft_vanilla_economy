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

/** /marketadmin rotate [promo] | info &lt;item&gt; | setstock &lt;item&gt; &lt;quantité&gt; */
public final class MarketAdminCommand implements TabExecutor {

    private final MarketManager market;

    public MarketAdminCommand(MarketManager market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if ((args.length == 1 || args.length == 2) && args[0].equalsIgnoreCase("rotate")) {
            boolean promo = args.length == 2 && args[1].equalsIgnoreCase("promo");
            market.runCycle(true, promo ? Boolean.TRUE : null);
            Messages.send(sender, "<green>Rotation forcée (cycle n°<n>)<promo>.", Messages.p("n", market.cycle()),
                    Messages.p("promo", market.promoActive() ? " avec promotions" : ""));
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
            Messages.send(sender, "<red>Usage : /marketadmin rotate [promo] | info <item> | setstock <item> <quantité>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("rotate", "info", "setstock").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
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
