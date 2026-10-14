package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.Tier;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import fr.vanillaeconomy.util.Players;
import org.bukkit.Bukkit;
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

/** /city create|info|add|kick|leave|coowner|contribute|upgrade|disband|tp. */
public final class CityCommand implements TabExecutor {

    private enum Pending { DISBAND, UPGRADE }

    private record Confirmation(Pending action, int cityId, long expiresAt) {
    }

    private static final List<String> SUBCOMMANDS = List.of(
            "create", "info", "add", "kick", "leave", "coowner", "contribute", "upgrade", "disband", "tp", "help");

    private final CityManager cities;
    private final MessageConfig msg;
    private final CityNotifier notifier;
    private final StaffLog staffLog;
    private final CityTeleport teleport;
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();

    public CityCommand(CityManager cities, MessageConfig msg, CityNotifier notifier, StaffLog staffLog,
                       CityTeleport teleport) {
        this.cities = cities;
        this.msg = msg;
        this.notifier = notifier;
        this.staffLog = staffLog;
        this.teleport = teleport;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players_only");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            // Journal line of a successful sub-command; foundation, expansion and dissolution
            // have their own staff message (CityManager audit).
            String result = switch (sub) {
                case "create" -> create(player, args);
                case "info" -> info(player, args);
                case "add" -> add(player, args);
                case "kick" -> kick(player, args);
                case "leave" -> leave(player, args);
                case "coowner" -> coOwner(player, args);
                case "contribute" -> contribute(player, args);
                case "upgrade" -> upgrade(player, args);
                case "disband" -> disband(player, args);
                case "tp" -> {
                    // asynchronous: logged by CityTeleport once the player has arrived
                    if (args.length != 2) {
                        msg.send(player, "tp_usage");
                    } else {
                        teleport.start(player, cities.require(args[1]), command, args);
                    }
                    yield null;
                }
                default -> {
                    usage(player);
                    yield null;
                }
            };
            if (result != null) {
                staffLog.command(sender, command, args, result);
            }
        } catch (CityException e) {
            msg.send(player, e.key(), e.vars());
        }
        return true;
    }

    private void usage(Player player) {
        player.sendMessage(msg.get("usage", "cost", Messages.coins(cities.config().creationCost)));
    }

    private String create(Player player, String[] args) throws CityException {
        if (args.length != 2) {
            usage(player);
            return null;
        }
        City city = cities.create(player, args[1]);
        msg.send(player, "created", "city", city.name(), "size", city.territory().size(),
                "cost", Messages.coins(cities.config().creationCost));
        return null;
    }

    private String info(Player player, String[] args) throws CityException {
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
        return "fiche de **" + StaffLog.md(city.name()) + "** consultée";
    }

    void sendInfo(CommandSender to, City city) {
        to.sendMessage(msg.get("info", "city", city.name(), "owner", CityManager.nameOf(city.owner()),
                "tier", city.tier(), "size", city.territory().size(), "x", city.centerX(), "z", city.centerZ(),
                "world", city.world(), "members", city.members().size(), "balance", Messages.coins(city.balance())));
    }

    private String add(Player player, String[] args) throws CityException {
        if (args.length < 2 || args.length > 3) {
            usage(player);
            return null;
        }
        City city = cities.managedCity(player.getUniqueId(), args.length == 3 ? args[2] : null);
        UUID target = resolvePlayer(args[1]);
        cities.addMember(city, player.getUniqueId(), target);
        String name = CityManager.nameOf(target);
        msg.send(player, "added", "player", name, "city", city.name());
        notifier.notify(target, "added_notify", "city", city.name(), "actor", player.getName());
        return StaffLog.md(name) + " ajouté à **" + StaffLog.md(city.name()) + "**";
    }

    private String kick(Player player, String[] args) throws CityException {
        if (args.length < 2 || args.length > 3) {
            usage(player);
            return null;
        }
        City city = cities.managedCity(player.getUniqueId(), args.length == 3 ? args[2] : null);
        UUID target = resolvePlayer(args[1]);
        cities.kick(city, player.getUniqueId(), target);
        msg.send(player, "kicked", "player", CityManager.nameOf(target), "city", city.name());
        notifier.notify(target, "kicked_notify", "city", city.name());
        return StaffLog.md(CityManager.nameOf(target)) + " exclu de **" + StaffLog.md(city.name()) + "**";
    }

    private String leave(Player player, String[] args) throws CityException {
        if (args.length != 2) {
            usage(player);
            return null;
        }
        City city = cities.require(args[1]);
        cities.leave(city, player.getUniqueId());
        msg.send(player, "left", "city", city.name());
        notifier.notify(city.owner(), "left_notify", "player", player.getName(), "city", city.name());
        return "a quitté **" + StaffLog.md(city.name()) + "**";
    }

    private String coOwner(Player player, String[] args) throws CityException {
        if (args.length != 3 || !(args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("unset"))) {
            usage(player);
            return null;
        }
        City city = cities.ownedBy(player.getUniqueId()).orElseThrow(() -> new CityException("no_managed_city"));
        UUID target = resolvePlayer(args[2]);
        boolean set = args[1].equalsIgnoreCase("set");
        cities.setCoOwner(city, player.getUniqueId(), target, set);
        msg.send(player, set ? "coowner_set" : "coowner_unset", "player", CityManager.nameOf(target), "city", city.name());
        notifier.notify(target, set ? "coowner_notify_set" : "coowner_notify_unset", "city", city.name());
        return StaffLog.md(CityManager.nameOf(target)) + (set ? " promu" : " n'est plus") + " co-propriétaire de **"
                + StaffLog.md(city.name()) + "**";
    }

    private String contribute(Player player, String[] args) throws CityException {
        if (args.length != 3) {
            usage(player);
            return null;
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
        if (!city.owner().equals(player.getUniqueId())) {
            notifier.notify(city.owner(), "contribution_notify", "player", player.getName(),
                    "amount", Messages.coins(amount), "city", city.name(), "balance", Messages.coins(city.balance()));
        }
        return amount + " pièces versées à **" + StaffLog.md(city.name()) + "** (trésorerie : " + city.balance() + ")";
    }

    private String upgrade(Player player, String[] args) throws CityException {
        City city = cities.ownedBy(player.getUniqueId()).orElseThrow(() -> new CityException("no_managed_city"));
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            consumeConfirmation(player, Pending.UPGRADE, city);
            Tier tier = cities.upgrade(city, player.getUniqueId());
            msg.send(player, "upgraded", "city", city.name(), "tier", tier.level(), "size", tier.size());
            return null;
        }
        Tier next = cities.checkUpgrade(city, player.getUniqueId());
        confirmations.put(player.getUniqueId(), new Confirmation(Pending.UPGRADE, city.id(),
                System.currentTimeMillis() + cities.config().confirmMillis));
        msg.send(player, "upgrade_confirm", "tier", next.level(), "size", next.size(),
                "price", Messages.coins(next.price()), "seconds", cities.config().confirmMillis / 1000);
        return "confirmation demandée pour le palier " + next.level() + " de **" + StaffLog.md(city.name()) + "**";
    }

    private String disband(Player player, String[] args) throws CityException {
        City city = cities.ownedBy(player.getUniqueId()).orElseThrow(() -> new CityException("no_managed_city"));
        if (args.length == 2 && args[1].equalsIgnoreCase("confirm")) {
            consumeConfirmation(player, Pending.DISBAND, city);
            List<UUID> members = new ArrayList<>(city.members().keySet());
            Map<UUID, Long> refunds = cities.disband(city, player.getUniqueId());
            msg.send(player, "disbanded", "city", city.name());
            notifier.dissolution(city, members, player.getUniqueId(), refunds, "disbanded_notify");
            return null;
        }
        if (!city.roleOf(player.getUniqueId()).canDisband()) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        confirmations.put(player.getUniqueId(), new Confirmation(Pending.DISBAND, city.id(),
                System.currentTimeMillis() + cities.config().confirmMillis));
        msg.send(player, "disband_confirm", "city", city.name(), "balance", Messages.coins(city.balance()),
                "seconds", cities.config().confirmMillis / 1000);
        return "confirmation de dissolution demandée pour **" + StaffLog.md(city.name()) + "**";
    }

    private void consumeConfirmation(Player player, Pending action, City city) throws CityException {
        Confirmation c = confirmations.remove(player.getUniqueId());
        if (c == null || c.action() != action || c.cityId() != city.id() || c.expiresAt() < System.currentTimeMillis()) {
            throw new CityException("confirm_expired");
        }
    }

    static UUID resolvePlayer(String raw) throws CityException {
        return Players.resolve(raw).orElseThrow(() -> new CityException("unknown_player", "player", raw));
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
                case "tp" -> {
                    if (args.length == 2) {
                        cities.citiesOf(me).stream()
                                .filter(c -> cities.config().enabled(CityExtension.TELEPORT) && c.hasExtension(CityExtension.TELEPORT))
                                .forEach(c -> options.add(c.name()));
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
