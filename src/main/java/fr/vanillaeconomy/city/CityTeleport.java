package fr.vanillaeconomy.city;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /city tp &lt;cité&gt; (extension Téléportation): a member is sent to the centre of the city, at
 * the height where it was founded, or the first free space above it if blocks have been
 * built there since. The fee is only taken once a free spot is found, and given back if
 * the teleport itself fails. Works from any dimension.
 */
public final class CityTeleport {

    private final Plugin plugin;
    private final CityManager cities;
    private final CurrencyManager currency;
    private final MessageConfig msg;
    private final StaffLog staffLog;
    /** Players whose teleport is waiting for the chunk or the teleport itself. */
    private final Set<UUID> pending = new HashSet<>();

    public CityTeleport(Plugin plugin, CityManager cities, CurrencyManager currency, MessageConfig msg, StaffLog staffLog) {
        this.plugin = plugin;
        this.cities = cities;
        this.currency = currency;
        this.msg = msg;
        this.staffLog = staffLog;
    }

    /** First checks, then loads the destination chunk asynchronously. */
    public void start(Player player, City city, Command command, String[] args) throws CityException {
        UUID id = player.getUniqueId();
        if (!cities.config().enabled(CityExtension.TELEPORT)) {
            throw new CityException("extension_disabled", "extension", CityExtension.TELEPORT.label());
        }
        if (!city.isMember(id)) {
            throw new CityException("not_member", "city", city.name());
        }
        if (!city.hasExtension(CityExtension.TELEPORT)) {
            throw new CityException("tp_locked", "city", city.name(),
                    "price", Messages.coins(cities.config().price(CityExtension.TELEPORT)));
        }
        long cost = cities.config().teleportUseCost;
        try {
            if (currency.getBalance(id) < cost) {
                throw new CityException("insufficient_funds", "amount", Messages.coins(cost));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Lecture du solde impossible", e);
            throw new CityException("internal_error");
        }
        World world = Bukkit.getWorld(city.world());
        if (world == null) {
            throw new CityException("tp_unavailable", "city", city.name());
        }
        if (!pending.add(id)) {
            throw new CityException("tp_in_progress");
        }
        int cityId = city.id();
        world.getChunkAtAsync(city.centerX() >> 4, city.centerZ() >> 4).whenComplete((chunk, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Chargement du centre de " + city.name() + " impossible", error);
                    }
                    arrive(player, cityId, world, error == null, command, args);
                }));
    }

    /** Main thread, destination chunk loaded: find the spot, charge, teleport. */
    private void arrive(Player player, int cityId, World world, boolean loaded, Command command, String[] args) {
        UUID id = player.getUniqueId();
        boolean teleporting = false;
        try {
            City city = cities.byId(cityId);
            if (!player.isOnline() || city == null) {
                return;
            }
            if (!loaded) {
                throw new CityException("tp_unavailable", "city", city.name());
            }
            Location target = target(city, world, player.getLocation());
            if (target == null) {
                throw new CityException("tp_no_space", "city", city.name());
            }
            long cost = cities.chargeTeleport(city, id);
            teleporting = true;
            String name = city.name();
            player.teleportAsync(target, TeleportCause.COMMAND).whenComplete((ok, error) ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        pending.remove(id);
                        if (Boolean.TRUE.equals(ok)) {
                            msg.send(player, "tp_done", "city", name, "cost", Messages.coins(cost));
                            staffLog.command(player, command, args, "téléporté au centre de **" + StaffLog.md(name)
                                    + "** (" + cost + " pièces détruites)");
                        } else {
                            cities.refundTeleport(id, cost);
                            if (player.isOnline()) {
                                msg.send(player, "tp_failed", "cost", Messages.coins(cost));
                            }
                        }
                    }));
        } catch (CityException e) {
            msg.send(player, e.key(), e.vars());
        } finally {
            if (!teleporting) {
                pending.remove(id);
            }
        }
    }

    /**
     * Centre of the city at the founding height (the surface for cities founded before it
     * was recorded), raised to the first two free blocks; null if the column is full.
     */
    static Location target(City city, World world, Location facing) {
        int x = city.centerX();
        int z = city.centerZ();
        int start = city.centerY() != null ? city.centerY() : world.getHighestBlockYAt(x, z) + 1;
        int y = CityRules.safeY(start, world.getMinHeight(), world.getMaxHeight(), h -> free(world.getBlockAt(x, h, z)));
        if (y == Integer.MIN_VALUE) {
            return null;
        }
        return new Location(world, x + 0.5, y, z + 0.5, facing.getYaw(), facing.getPitch());
    }

    /** Nothing to collide with and no liquid (a player is not dropped into water or lava). */
    private static boolean free(Block block) {
        return block.isPassable() && !block.isLiquid();
    }
}
