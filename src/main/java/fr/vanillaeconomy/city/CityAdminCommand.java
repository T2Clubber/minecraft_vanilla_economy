package fr.vanillaeconomy.city;

import fr.vanillaeconomy.util.MessageConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /cityadmin delete &lt;cité&gt; | settier &lt;cité&gt; &lt;palier&gt; | rename &lt;cité&gt; &lt;nom&gt; | info &lt;cité&gt;. */
public final class CityAdminCommand implements TabExecutor {

    private final CityManager cities;
    private final MessageConfig msg;
    private final CityCommand cityCommand;

    public CityAdminCommand(CityManager cities, MessageConfig msg, CityCommand cityCommand) {
        this.cities = cities;
        this.msg = msg;
        this.cityCommand = cityCommand;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("delete") && args.length == 2) {
                City city = cities.require(args[1]);
                Map<UUID, Long> refunds = cities.delete(city);
                msg.send(sender, "admin_deleted", "city", city.name());
                CityCommand.notifyRefunds(msg, city, refunds);
            } else if (sub.equals("settier") && args.length == 3) {
                City city = cities.require(args[1]);
                int tier;
                try {
                    tier = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    throw new CityException("invalid_amount");
                }
                cities.adminSetTier(city, tier);
                msg.send(sender, "admin_tier", "city", city.name(), "tier", tier);
            } else if (sub.equals("rename") && args.length == 3) {
                City city = cities.require(args[1]);
                cities.adminRename(city, args[2]);
                msg.send(sender, "admin_renamed", "city", city.name());
            } else if (sub.equals("info") && args.length == 2) {
                cityCommand.sendInfo(sender, cities.require(args[1]));
            } else {
                msg.send(sender, "admin_usage");
            }
        } catch (CityException e) {
            msg.send(sender, e.key(), e.vars());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("delete", "settier", "rename", "info"));
        } else if (args.length == 2) {
            cities.all().forEach(c -> options.add(c.name()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("settier")) {
            cities.config().tiers.forEach(t -> options.add(String.valueOf(t.level())));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
