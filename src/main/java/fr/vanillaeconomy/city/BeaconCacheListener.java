package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.BeaconExtension.BeaconSighting;
import fr.vanillaeconomy.city.BeaconExtension.Pos;
import io.papermc.paper.event.block.BeaconActivatedEvent;
import io.papermc.paper.event.block.BeaconDeactivatedEvent;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Keeps {@link BeaconEffectCache} up to date for the cities with the Beacon extension:
 * blocks placed or broken in the city (beacon, pyramid, beam), chunk loads, effect changes,
 * beam (de)activation, expansion of the city. Revalidations of a city are grouped and run
 * after the beacons' own update (every 80 ticks), so the pyramid level read is the new one.
 */
public final class BeaconCacheListener implements Listener {

    /** A beacon recomputes its pyramid every 80 ticks: read it after that. */
    private static final long AFTER_BEACON_UPDATE = 100;

    private final Plugin plugin;
    private final CityManager cities;
    private final ExtensionManager extensions;
    private final BeaconScanner scanner;
    private final BeaconEffectCache cache;
    /** City id -> positions to check at the next revalidation of that city. */
    private final Map<Integer, Set<Pos>> pending = new HashMap<>();

    public BeaconCacheListener(Plugin plugin, CityManager cities, ExtensionManager extensions, BeaconScanner scanner,
                               BeaconEffectCache cache) {
        this.plugin = plugin;
        this.cities = cities;
        this.extensions = extensions;
        this.scanner = scanner;
        this.cache = cache;
        cities.onRemoval(city -> {
            cache.clear(city.id());
            pending.remove(city.id());
        });
        cities.onTerritoryChange(city -> {
            if (active(city)) {
                refresh(city);
            }
        });
    }

    /** Startup: beacons of the chunks already loaded; the others come with their ChunkLoadEvent. */
    public void start() {
        for (City city : cities.all()) {
            if (!active(city)) {
                continue;
            }
            World world = Bukkit.getWorld(city.world());
            Territory t = city.territory();
            if (world == null) {
                continue;
            }
            Set<Pos> found = new HashSet<>();
            for (int cx = t.minChunkX(); cx <= t.maxChunkX(); cx++) {
                for (int cz = t.minChunkZ(); cz <= t.maxChunkZ(); cz++) {
                    if (world.isChunkLoaded(cx, cz)) {
                        BeaconScanner.beaconsIn(world.getChunkAt(cx, cz)).forEach(b -> found.add(b.pos()));
                    }
                }
            }
            revalidate(city, found);
        }
    }

    /** Full scan of the city (expansion): replaces its cache. */
    public void refresh(City city) {
        scanner.scan(city).whenComplete((beacons, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Scan des beacons de " + city.name() + " impossible", error);
                return;
            }
            if (cities.byId(city.id()) == city && active(city)) {
                cache.replace(city.id(), valid(city, beacons));
            }
        });
    }

    private List<BeaconSighting> valid(City city, List<BeaconSighting> beacons) {
        return BeaconExtension.check(city.tier(), 0, 0, extensions.config().beaconPyramid, beacons, city.territory()).valid();
    }

    private boolean active(City city) {
        return extensions.active(city, CityExtension.BEACON);
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        changed(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        changed(event.getBlock());
    }

    /**
     * A block changed in a city: the beacons whose pyramid may contain it (up to 4 blocks
     * below them, 4 blocks aside), plus every cached beacon of the city (its beam may now be
     * obstructed or the beacon itself broken).
     */
    private void changed(Block block) {
        City city = cities.at(block.getLocation());
        if (!active(city)) {
            return;
        }
        Set<Pos> candidates = new HashSet<>();
        if (block.getType() == Material.BEACON) {
            candidates.add(BeaconScanner.pos(block));
        }
        for (int dy = 1; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    Block above = block.getRelative(dx, dy, dz);
                    if (above.getType() == Material.BEACON) {
                        candidates.add(BeaconScanner.pos(above));
                    }
                }
            }
        }
        schedule(city, candidates, AFTER_BEACON_UPDATE);
    }

    /** Beacons of a (re)loaded chunk are checked again, the unloaded ones stayed in the cache. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        String world = chunk.getWorld().getName();
        for (City city : cities.all()) {
            Territory t = city.territory();
            if (t.world().equals(world) && chunk.getX() >= t.minChunkX() && chunk.getX() <= t.maxChunkX()
                    && chunk.getZ() >= t.minChunkZ() && chunk.getZ() <= t.maxChunkZ() && active(city)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (chunk.isLoaded()) {
                        Set<Pos> found = new HashSet<>();
                        BeaconScanner.beaconsIn(chunk).forEach(b -> found.add(b.pos()));
                        revalidate(city, found);
                    }
                });
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEffectChange(PlayerChangeBeaconEffectEvent event) {
        beaconEvent(event.getBeacon());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onActivated(BeaconActivatedEvent event) {
        beaconEvent(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeactivated(BeaconDeactivatedEvent event) {
        beaconEvent(event.getBlock());
    }

    private void beaconEvent(Block beacon) {
        City city = cities.at(beacon.getLocation());
        if (active(city)) {
            schedule(city, Set.of(BeaconScanner.pos(beacon)), 1);
        }
    }

    // ------------------------------------------------------------------
    // Revalidation (main thread)
    // ------------------------------------------------------------------

    private void schedule(City city, Set<Pos> candidates, long delay) {
        Set<Pos> waiting = pending.get(city.id());
        if (waiting != null) {
            waiting.addAll(candidates);
            return;
        }
        pending.put(city.id(), new HashSet<>(candidates));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Set<Pos> positions = pending.remove(city.id());
            if (positions != null && cities.byId(city.id()) == city) {
                revalidate(city, positions);
            }
        }, delay);
    }

    /**
     * Checks {@code candidates} and every cached beacon of the city whose chunk is loaded;
     * a beacon in an unloaded chunk keeps its cached state until the chunk loads again.
     */
    void revalidate(City city, Set<Pos> candidates) {
        if (!active(city)) {
            return;
        }
        Set<Pos> positions = new HashSet<>(candidates);
        positions.addAll(cache.positions(city.id()));
        int pyramid = extensions.config().beaconPyramid;
        for (Pos pos : positions) {
            World world = Bukkit.getWorld(pos.world());
            if (world == null || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                continue;
            }
            if (world.getBlockAt(pos.x(), pos.y(), pos.z()).getState(false) instanceof Beacon beacon) {
                BeaconSighting sighting = BeaconScanner.sighting(beacon);
                if (BeaconExtension.invalidReason(sighting, city.territory(), pyramid).isEmpty()) {
                    cache.put(city.id(), sighting);
                    continue;
                }
            }
            cache.remove(city.id(), pos);
        }
    }
}
