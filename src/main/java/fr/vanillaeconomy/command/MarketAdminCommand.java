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

/** /marketadmin rotate | info &lt;item&gt; */
public final class MarketAdminCommand implements TabExecutor {

    private final MarketManager market;

    public MarketAdminCommand(MarketManager market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("rotate")) {
            market.runCycle(true);
            Messages.send(sender, "<green>Rotation forcée (cycle n°<n>).", Messages.p("n", market.cycle()));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            Material material = Material.matchMaterial(args[1]);
            Messages.send(sender, "<gray><info>", Messages.p("info",
                    material == null ? "Item inconnu : " + args[1] : market.describe(material)));
        } else {
            Messages.send(sender, "<red>Usage : /marketadmin rotate | info <item>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("rotate", "info").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            String prefix = args[1].toUpperCase();
            return Arrays.stream(Material.values())
                    .filter(m -> market.item(m) != null && m.name().startsWith(prefix))
                    .map(Material::name).toList();
        }
        return List.of();
    }
}
