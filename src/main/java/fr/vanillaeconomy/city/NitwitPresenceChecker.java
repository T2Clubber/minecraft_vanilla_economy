package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.NitwitSighting;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Skin scan of a city, only on demand (purchase of Promotion en folie, "Vérifier les
 * skins" button): the chunks covering the territory are loaded asynchronously (without
 * generating new ones), kept loaded by a plugin ticket until their entities are loaded,
 * then the villagers are counted on the main thread, inside the exact limits of the city.
 */
public final class NitwitPresenceChecker {

    /** Waiting for the entities of freshly loaded chunks: 5 ticks between checks, 10 s at most. */
    private static final long RETRY_TICKS = 5;
    private static final int MAX_RETRIES = 40;

    private final Plugin plugin;
    private final NamespacedKey marketKey;

    public NitwitPresenceChecker(Plugin plugin) {
        this.plugin = plugin;
        // Same key as NitwitVillagerManager (namespace of this plugin).
        this.marketKey = new NamespacedKey(plugin, "isMarketNPC");
    }

    /** Villager types present in the city; completes on the main thread. */
    public CompletableFuture<Set<String>> scan(City city) {
        CompletableFuture<Set<String>> result = new CompletableFuture<>();
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
                    // Never generated chunks come back null: nothing can stand there.
                    List<Chunk> chunks = loads.stream().map(CompletableFuture::join).filter(Objects::nonNull).toList();
                    chunks.forEach(c -> c.addPluginChunkTicket(plugin));
                    awaitEntities(city, chunks, 0, result);
                }));
        return result;
    }

    private void awaitEntities(City city, List<Chunk> chunks, int attempt, CompletableFuture<Set<String>> result) {
        if (!chunks.stream().allMatch(Chunk::isEntitiesLoaded)) {
            if (attempt >= MAX_RETRIES) {
                release(chunks);
                result.completeExceptionally(new IllegalStateException("entités non chargées"));
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> awaitEntities(city, chunks, attempt + 1, result), RETRY_TICKS);
            return;
        }
        try {
            List<NitwitSighting> sightings = new ArrayList<>();
            for (Chunk chunk : chunks) {
                for (Entity entity : chunk.getEntities()) {
                    if (entity instanceof Villager v) {
                        sightings.add(new NitwitSighting(v.getVillagerType().key().value(), v.isValid() && !v.isDead(),
                                v.isAdult(), v.getProfession() == Villager.Profession.NITWIT,
                                v.getPersistentDataContainer().getOrDefault(marketKey, PersistentDataType.BOOLEAN, false),
                                v.getWorld().getName(), v.getLocation().getBlockX(), v.getLocation().getBlockZ()));
                    }
                }
            }
            result.complete(CityRules.presentTypes(sightings, city.territory()));
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        } finally {
            release(chunks);
        }
    }

    private void release(List<Chunk> chunks) {
        chunks.forEach(c -> c.removePluginChunkTicket(plugin));
    }
}
