package fr.vanillaeconomy.city;

import fr.vanillaeconomy.discord.StaffLog;
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

/**
 * /cityadmin delete &lt;cité&gt; | settier &lt;cité&gt; &lt;palier&gt; | rename &lt;cité&gt; &lt;nom&gt; | info &lt;cité&gt;
 * | promos &lt;cité&gt; | promo &lt;cité&gt; &lt;pourcentage&gt;.
 */
public final class CityAdminCommand implements TabExecutor {

    private final CityManager cities;
    private final MessageConfig msg;
    private final CityCommand cityCommand;
    private final CityNotifier notifier;
    private final StaffLog staffLog;
    private final CityPromotionScheduler promotions;

    public CityAdminCommand(CityManager cities, MessageConfig msg, CityCommand cityCommand, CityNotifier notifier,
                            StaffLog staffLog, CityPromotionScheduler promotions) {
        this.staffLog = staffLog;
        this.promotions = promotions;
        this.cities = cities;
        this.msg = msg;
        this.cityCommand = cityCommand;
        this.notifier = notifier;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        try {
            String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("delete") && args.length == 2) {
                City city = cities.require(args[1]);
                List<UUID> members = new ArrayList<>(city.members().keySet());
                Map<UUID, Long> refunds = cities.delete(city);
                msg.send(sender, "admin_deleted", "city", city.name());
                staffLog.admin(sender.getName(), "`/cityadmin delete` cité **" + city.name() + "** supprimée, "
                        + refunds.values().stream().mapToLong(Long::longValue).sum() + " pièces remboursées");
                notifier.dissolution(city, members, null, refunds, "admin_deleted_notify");
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
                staffLog.admin(sender.getName(), "`/cityadmin settier` cité **" + city.name() + "** → palier " + tier);
            } else if (sub.equals("rename") && args.length == 3) {
                City city = cities.require(args[1]);
                String oldName = city.name();
                cities.adminRename(city, args[2]);
                staffLog.admin(sender.getName(), "`/cityadmin rename` **" + oldName + "** → **" + city.name() + "**");
                msg.send(sender, "admin_renamed", "city", city.name());
            } else if (sub.equals("promos") && args.length == 2) {
                City city = cities.require(args[1]);
                msg.send(sender, "admin_promos", "city", city.name(), "plans", promotions.describePlans(city));
                staffLog.command(sender, command, args, "planning des promos de **" + StaffLog.md(city.name()) + "** consulté");
            } else if (sub.equals("promo") && args.length == 3) {
                City city = cities.require(args[1]);
                if (!city.hasExtension(CityExtension.PROMO_FRENZY)) {
                    throw new CityException("admin_no_frenzy", "city", city.name());
                }
                int percent;
                try {
                    percent = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    percent = -1;
                }
                if (percent < 1 || percent > 99) {
                    throw new CityException("invalid_amount");
                }
                try {
                    promotions.forceNow(city, percent);
                } catch (java.sql.SQLException e) {
                    throw new CityException("internal_error");
                }
                msg.send(sender, "admin_promo_forced", "city", city.name(), "percent", percent);
                staffLog.admin(sender.getName(), "`/cityadmin promo` promo de cité -" + percent + " % forcée pour **"
                        + StaffLog.md(city.name()) + "** sur la rotation en cours");
            } else if (sub.equals("info") && args.length == 2) {
                City city = cities.require(args[1]);
                cityCommand.sendInfo(sender, city);
                staffLog.command(sender, command, args, "fiche de **" + StaffLog.md(city.name()) + "** consultée");
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
            options.addAll(List.of("delete", "settier", "rename", "info", "promos", "promo"));
        } else if (args.length == 2) {
            cities.all().forEach(c -> options.add(c.name()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("settier")) {
            cities.config().tiers.forEach(t -> options.add(String.valueOf(t.level())));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
