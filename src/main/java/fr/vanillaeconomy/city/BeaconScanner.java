package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.BeaconExtension.BeaconSighting;
import fr.vanillaeconomy.city.BeaconExtension.Pos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Beacon scan of a city, only on demand (purchase, "Vérifier les beacons", city expansion):
 * the chunks covering the territory are loaded asynchronously without generating new ones,
 * then the beacons are read on the main thread from the chunks' tile entities. Beacons of
 * the overhanging parts of the chunks are returned too (reported "hors territoire").
 */
public final class BeaconScanner {

    private final Plugin plugin;

    public BeaconScanner(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Every beacon of the chunks covering the city; completes on the main thread. */
    public CompletableFuture<List<BeaconSighting>> scan(City city) {
        CompletableFuture<List<BeaconSighting>> result = new CompletableFuture<>();
        World world = Bukkit.getWorld(city.world());
        if (world == null) {
            result.completeExceptionally(new IllegalStateException("monde introuvable : " + city.world()));
            return result;
        }
        Territory t = city.territory();
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int cx = t.minChunkX(); cx <= t.maxChunkX(); cx++) {
            for (int cz = t.minChunkZ(); cz <= t.maxChunkZ(); cz++) {
                loads.add(world.getChunkAtAsync(cx, cz, false));
            }
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).whenComplete((done, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                        return;
                    }
                    try {
                        List<BeaconSighting> beacons = new ArrayList<>();
                        loads.stream().map(CompletableFuture::join).filter(Objects::nonNull)
                                .forEach(chunk -> beacons.addAll(beaconsIn(chunk)));
                        result.complete(beacons);
                    } catch (RuntimeException e) {
                        result.completeExceptionally(e);
                    }
                }));
        return result;
    }

    /** Beacons of a loaded chunk (main thread). */
    public static List<BeaconSighting> beaconsIn(Chunk chunk) {
        List<BeaconSighting> beacons = new ArrayList<>();
        for (BlockState state : chunk.getTileEntities(b -> b.getType() == Material.BEACON, false)) {
            if (state instanceof Beacon beacon) {
                beacons.add(sighting(beacon));
            }
        }
        return beacons;
    }

    /** One beacon as read now (main thread, chunk loaded). */
    public static BeaconSighting sighting(Beacon beacon) {
        Block block = beacon.getBlock();
        return new BeaconSighting(pos(block), beacon.getTier(), beamClear(block),
                key(beacon.getPrimaryEffect()), key(beacon.getSecondaryEffect()));
    }

    public static Pos pos(Block block) {
        return new Pos(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String key(PotionEffect effect) {
        return effect == null ? null : effect.getType().getKey().toString();
    }

    /**
     * The beam reaches the sky, as in vanilla: it goes through every block that lets light
     * through (glass, leaves, slabs...) and through bedrock, and stops at an opaque block.
     */
    static boolean beamClear(Block beacon) {
        World world = beacon.getWorld();
        for (int y = beacon.getY() + 1; y < world.getMaxHeight(); y++) {
            Material type = world.getBlockAt(beacon.getX(), y, beacon.getZ()).getType();
            if (type.isOccluding() && type != Material.BEDROCK) {
                return false;
            }
        }
        return true;
    }
}
