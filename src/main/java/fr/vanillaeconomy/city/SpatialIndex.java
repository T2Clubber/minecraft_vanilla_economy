package fr.vanillaeconomy.city;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * World -> chunk key -> cities overlapping that chunk. A lookup is a hash access plus a
 * bounds check on at most a couple of cities: cheap enough for move / block events.
 */
final class SpatialIndex {

    private final Map<String, Map<Long, List<City>>> worlds = new HashMap<>();

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    void add(City city) {
        Territory t = city.territory();
        Map<Long, List<City>> chunks = worlds.computeIfAbsent(t.world(), w -> new HashMap<>());
        for (int cx = t.minChunkX(); cx <= t.maxChunkX(); cx++) {
            for (int cz = t.minChunkZ(); cz <= t.maxChunkZ(); cz++) {
                chunks.computeIfAbsent(key(cx, cz), k -> new ArrayList<>(1)).add(city);
            }
        }
    }

    void remove(City city) {
        Map<Long, List<City>> chunks = worlds.get(city.world());
        if (chunks == null) {
            return;
        }
        chunks.values().forEach(list -> list.remove(city));
        chunks.values().removeIf(List::isEmpty);
    }

    City at(String world, int x, int z) {
        Map<Long, List<City>> chunks = worlds.get(world);
        if (chunks == null) {
            return null;
        }
        List<City> candidates = chunks.get(key(x >> 4, z >> 4));
        if (candidates == null) {
            return null;
        }
        for (City city : candidates) {
            if (city.territory().contains(world, x, z)) {
                return city;
            }
        }
        return null;
    }
}
