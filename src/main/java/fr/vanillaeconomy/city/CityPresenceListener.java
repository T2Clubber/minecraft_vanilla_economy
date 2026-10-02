package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.PresenceChange;
import fr.vanillaeconomy.util.MessageConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Chat messages when a player enters / leaves a city. The current city of each online
 * player is kept in memory and only recomputed when he changes block (spatial index
 * lookup, no database).
 */
public final class CityPresenceListener implements Listener {

    private final CityManager cities;
    private final MessageConfig msg;
    private final Map<UUID, Integer> current = new HashMap<>();

    public CityPresenceListener(CityManager cities, MessageConfig msg) {
        this.cities = cities;
        this.msg = msg;
        cities.onTerritoryChange(city -> refreshAll(true));
        cities.onRemoval(this::forget);
    }

    /** Initial state for players already online (plugin enabled while players are connected). */
    public void start() {
        Bukkit.getOnlinePlayers().forEach(p -> {
            City city = cities.at(p.getLocation());
            if (city != null) {
                current.put(p.getUniqueId(), city.id());
            }
        });
    }

    private void update(Player player, Location to, boolean announce) {
        City city = cities.at(to);
        Integer now = city == null ? null : city.id();
        Integer before = now == null ? current.remove(player.getUniqueId()) : current.put(player.getUniqueId(), now);
        if (!announce || !cities.config().presenceMessages) {
            return;
        }
        for (PresenceChange change : CityRules.transitions(before, now)) {
            City c = cities.byId(change.cityId());
            if (c == null) {
                continue;
            }
            switch (change.kind()) {
                case EXIT -> player.sendMessage(msg.get("exit", "city", c.name()));
                case ENTER -> player.sendMessage(msg.get("enter", "city", c.name(), "owner", CityManager.nameOf(c.owner())));
            }
        }
    }

    private void refreshAll(boolean announce) {
        Bukkit.getOnlinePlayers().forEach(p -> update(p, p.getLocation(), announce));
    }

    /** A dissolved city: silently forget it (no "exit" message for a city that no longer exists). */
    private void forget(City city) {
        current.values().removeIf(id -> id == city.id());
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getBlockX() == b.getBlockX() && a.getBlockZ() == b.getBlockZ() && a.getWorld() == b.getWorld();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!sameBlock(event.getFrom(), event.getTo())) {
            update(event.getPlayer(), event.getTo(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (sameBlock(event.getFrom(), event.getTo())) {
            return;
        }
        for (Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                update(player, event.getTo(), true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        update(event.getPlayer(), event.getTo(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        update(event.getPlayer(), event.getPlayer().getLocation(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        update(event.getPlayer(), event.getRespawnLocation(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        current.remove(event.getPlayer().getUniqueId());
        update(event.getPlayer(), event.getPlayer().getLocation(), cities.config().presenceOnJoin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        current.remove(event.getPlayer().getUniqueId());
    }
}
