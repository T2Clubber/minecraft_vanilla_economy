package fr.vanillaeconomy.city;

import fr.vanillaeconomy.util.MessageConfig;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /border &lt;city&gt; on|off: particles drawn on the city edges, sent to that player only.
 * A single repeating task draws the segments within {@code radius} blocks horizontally
 * and {@code height} blocks vertically around each viewer; it reads the live territory,
 * so an expansion shows up immediately.
 */
public final class BorderManager implements Listener, TabExecutor {

    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(0x33, 0xCC, 0xFF), 1.2f);

    private final Plugin plugin;
    private final CityManager cities;
    private final MessageConfig msg;
    private final Map<UUID, Integer> viewers = new HashMap<>();
    private BukkitTask task;

    public BorderManager(Plugin plugin, CityManager cities, MessageConfig msg) {
        this.plugin = plugin;
        this.cities = cities;
        this.msg = msg;
        cities.onRemoval(city -> viewers.values().removeIf(id -> id == city.id()));
    }

    public void start() {
        long period = cities.config().borderRefreshTicks;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::draw, period, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        viewers.clear();
    }

    private void draw() {
        int radius = cities.config().borderRadius;
        int height = cities.config().borderHeight;
        Iterator<Map.Entry<UUID, Integer>> it = viewers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            City city = cities.byId(entry.getValue());
            if (player == null || city == null) {
                it.remove();
                continue;
            }
            Location loc = player.getLocation();
            if (!loc.getWorld().getName().equals(city.world())) {
                continue;
            }
            Territory t = city.territory();
            int px = loc.getBlockX();
            int pz = loc.getBlockZ();
            int py = loc.getBlockY();
            // Edges are the outer faces of the square: x = minX and x = maxX + 1 (same for z).
            int west = t.minX();
            int east = t.maxX() + 1;
            int north = t.minZ();
            int south = t.maxZ() + 1;
            for (int y = py - height; y <= py + height; y += 2) {
                for (int edgeX : new int[]{west, east}) {
                    if (Math.abs(edgeX - px) <= radius) {
                        for (int z = Math.max(north, pz - radius); z <= Math.min(south, pz + radius); z++) {
                            player.spawnParticle(Particle.DUST, edgeX, y + 0.5, z, 1, 0, 0, 0, 0, DUST);
                        }
                    }
                }
                for (int edgeZ : new int[]{north, south}) {
                    if (Math.abs(edgeZ - pz) <= radius) {
                        for (int x = Math.max(west, px - radius); x <= Math.min(east, px + radius); x++) {
                            player.spawnParticle(Particle.DUST, x, y + 0.5, edgeZ, 1, 0, 0, 0, 0, DUST);
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        viewers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        viewers.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // /border <cité> on|off
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players_only");
            return true;
        }
        if (args.length != 2 || !(args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            msg.send(player, "border_usage");
            return true;
        }
        if (args[1].equalsIgnoreCase("off")) {
            viewers.remove(player.getUniqueId());
            msg.send(player, "border_off");
            return true;
        }
        try {
            City city = cities.require(args[0]);
            if (!player.getWorld().getName().equals(city.world())) {
                msg.send(player, "border_other_world", "city", city.name());
                return true;
            }
            viewers.put(player.getUniqueId(), city.id());
            msg.send(player, "border_on", "city", city.name());
        } catch (CityException e) {
            msg.send(player, e.key(), e.vars());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            cities.all().forEach(c -> options.add(c.name()));
        } else if (args.length == 2) {
            options.addAll(List.of("on", "off"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
