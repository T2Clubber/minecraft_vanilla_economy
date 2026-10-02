package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.Tier;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** /city create|info|add|kick|leave|coowner|contribute|upgrade|disband. */
public final class CityCommand implements TabExecutor {

    private enum Pending { DISBAND, UPGRADE }

    private record Confirmation(Pending action, int cityId, long expiresAt) {
    }

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "info", "add", "kick", "leave", "coowner", "contribute", "upgrade", "disband", "help");

    private final CityManager cities;
    private final MessageConfig msg;
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();

    public CityCommand(CityManager cities, MessageConfig msg) {
        this.cities = cities;
        this.msg = msg;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players_only");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "create" -> create(player, args);
                case "info" -> info(player, args);
                case "add" -> add(player, args);
                case "kick" -> kick(player, args);
                case "leave" -> leave(player, args);
                case "coowner" -> coOwner(player, args);
                case "contribute" -> contribute(player, args);
                case "upgrade" -> upgrade(player, args);
                case "disband" -> disband(player, args);
                default -> usage(player);
            }
        } catch (CityException e) {
            msg.send(player, e.key(), e.vars());
        }
        return true;
    }

    private void usage(Player player) {
        player.sendMessage(msg.get("usage", "cost", Messages.coins(cities.config().creationCost)));
    }

    private void create(Player player, String[] args) throws CityException {
        if (args.length != 2) {
            usage(player);
            return;
        }
        City city = cities.create(player, args[1]);
        msg.send(player, "created", "city", city.name(), "size", city.territory().size(),
                "cost", Messages.coins(cities.config().creationCost));
    }

    private void info(Player player, String[] args) throws CityException {
        City city;
        if (args.length >= 2) {
            city = cities.require(args[1]);
        } else {
            city = cities.at(player.getLocation());
            if (city == null) {
                city = cities.ownedBy(player.getUniqueId())
                        .or(() -> cities.citiesOf(player.getUniqueId()).stream().findFirst())
                        .orElseThrow(() -> new CityException("no_managed_city"));
            }
        }
        sendInfo(player, city);
    }

    void sendInfo(CommandSender to, City city) {
        to.sendMessage(msg.get("info", "city", city.name(), "owner", CityManager.nameOf(city.owner()),
                "tier", city.tier(), "size", city.territory().size(), "x", city.centerX(), "z", city.centerZ(),
                "world", city.world(), "members", city.members().size(), "balance", Messages.coins(city.balance())));
    }

    private void add(Player player, String[] args) throws CityException {
        if (args.length < 2 || args.length > 3) {
            usage(player);
            return;
        }
        City city = cities.managedCity(player.getUniqueId(), args.length == 3 ? args[2] : null);
        UUID target = resolvePlayer(args[1]);
        cities.addMember(city, player.getUniqueId(), target);
        String name = CityManager.nameOf(target);
        msg.send(player, "added", "player", name, "city", city.name());
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            msg.send(online, "added_notify", "city", city.name(), "actor", player.getName());
        }
    }

    private void kick(Player player, String[] args) throws CityException {
        if (args.length < 2 || args.length > 3) {
            usage(player);
            return;
        }
        City city = cities.managedCity(player.getUniqueId(), args.length == 3 ? args[2] : null);
        UUID target = resolvePlayer(args[1]);
        cities.kick(city, player.getUniqueId(), target);
        msg.send(player, "kicked", "player", CityManager.nameOf(target), "city", city.name());
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            msg.send(online, "kicked_notify", "city", city.name());
        }
    }

    private void leave(Player player, String[] args) throws CityException {
        if (args.length != 2) {
            usage(player);
            return;
        }
        City city = cities.require(args[1]);
        cities.leave(city, player.getUniqueId());
        msg.send(player, "left", "city", city.name());
    }

    private void coOwner(Player player, String[] args) throws CityException {
        if (args.length != 3 || !(args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("unset"))) {
            usage(player);
            return;
        }
        City city = cities.ownedBy(player.getUniqueId()).orElseThrow(() -> new CityException("no_managed_city"));
        UUID target = resolvePlayer(args[2]);
        boolean set = args[1].equalsIgnoreCase("set");
        cities.setCoOwner(city, player.getUniqueId(), target, set);
        msg.send(player, set ? "coowner_set" : "coowner_unset", "player", CityManager.nameOf(target), "city", city.name());
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            msg.send(online, set ? "coowner_notify_set" : "coowner_notify_unset", "city", city.name());
        }
    }

    private void contribute(Player player, String[] args) throws CityException {
        if (args.length != 3) {
            usage(player);
            return;
        }
        City city = cities.require(args[1]);
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            throw new CityException("invalid_amount");
        }
        cities.contribute(city, player.getUniqueId(), amount);
        msg.send(player, "contributed", "amount", Messages.coins(amount), "city", city.name(),
                "balance", Messages.coins(city.balance()));
    }

    private void upgrade(Player player, String[] args) throws CityException {
        City city = cities.ownedBy(player.getUniqueId()).orElseThrow(() -> new CityException("no_managed_city"));
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            consumeConfirmation(player, Pending.UPGRADE, city);
            Tier tier = cities.upgrade(city, player.getUniqueId());
            msg.send(player, "upgraded", "city", city.name(), "tier", tier.level(), "size", tier.size());
            return;
        }
        Tier next = cities.checkUpgrade(city, player.getUniqueId());
        confirmations.put(player.getUniqueId(), new Confirmation(Pending.UPGRADE, city.id(),
                System.currentTimeMillis() + cities.config().confirmMillis));
        msg.send(player, "upgrade_confirm", "tier", next.level(), "size", next.size(),
                "price", Messages.coins(next.price()), "seconds", cities.config().confirmMillis / 1000);
    }

    private void disband(Player player, String[] args) throws CityException {
        City city = cities.ownedBy(player.getUniqueId()).orElseThrow(() -> new CityException("no_managed_city"));
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            consumeConfirmation(player, Pending.DISBAND, city);
            Map<UUID, Long> refunds = cities.disband(city, player.getUniqueId());
            msg.send(player, "disbanded", "city", city.name());
            notifyRefunds(msg, city, refunds);
            return;
        }
        if (!city.roleOf(player.getUniqueId()).canDisband()) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        confirmations.put(player.getUniqueId(), new Confirmation(Pending.DISBAND, city.id(),
                System.currentTimeMillis() + cities.config().confirmMillis));
        msg.send(player, "disband_confirm", "city", city.name(), "balance", Messages.coins(city.balance()),
                "seconds", cities.config().confirmMillis / 1000);
    }

    static void notifyRefunds(MessageConfig msg, City city, Map<UUID, Long> refunds) {
        refunds.forEach((uuid, amount) -> {
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                msg.send(online, "refund_notify", "city", city.name(), "amount", Messages.coins(amount));
            }
        });
    }

    private void consumeConfirmation(Player player, Pending action, City city) throws CityException {
        Confirmation c = confirmations.remove(player.getUniqueId());
        if (c == null || c.action() != action || c.cityId() != city.id() || c.expiresAt() < System.currentTimeMillis()) {
            throw new CityException("confirm_expired");
        }
    }

    /** Online name, UUID, or a player the server has already seen (never a blocking web lookup). */
    static UUID resolvePlayer(String raw) throws CityException {
        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            // not a UUID
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(raw);
        if (cached == null) {
            throw new CityException("unknown_player", "player", raw);
        }
        return cached.getUniqueId();
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        UUID me = player.getUniqueId();
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(SUBCOMMANDS);
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "info" -> {
                    if (args.length == 2) {
                        cities.all().forEach(c -> options.add(c.name()));
                    }
                }
                case "leave", "contribute" -> {
                    if (args.length == 2) {
                        cities.citiesOf(me).forEach(c -> options.add(c.name()));
                    }
                }
                case "add" -> {
                    if (args.length == 2) {
                        Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                    } else if (args.length == 3) {
                        managedNames(me).forEach(options::add);
                    }
                }
                case "kick" -> {
                    if (args.length == 2) {
                        cities.citiesOf(me).stream()
                                .filter(c -> c.roleOf(me).canAddMembers())
                                .flatMap(c -> c.members().keySet().stream())
                                .filter(u -> !u.equals(me))
                                .map(CityManager::nameOf).distinct().forEach(options::add);
                    } else if (args.length == 3) {
                        managedNames(me).forEach(options::add);
                    }
                }
                case "coowner" -> {
                    if (args.length == 2) {
                        options.addAll(List.of("set", "unset"));
                    } else if (args.length == 3) {
                        cities.ownedBy(me).ifPresent(c -> c.members().keySet().stream()
                                .filter(u -> !u.equals(me)).map(CityManager::nameOf).forEach(options::add));
                    }
                }
                case "upgrade", "disband" -> {
                    if (args.length == 2) {
                        options.add("confirm");
                    }
                }
                default -> {
                }
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }

    private Stream<String> managedNames(UUID me) {
        return cities.citiesOf(me).stream().filter(c -> c.roleOf(me).canAddMembers()).map(City::name);
    }
}
