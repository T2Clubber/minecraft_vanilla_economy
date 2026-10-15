package fr.vanillaeconomy.city;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpatialIndexTest {

    @Test
    void anUpgradedCityIsProtectedOnItsWholeNewTerritory() {
        SpatialIndex index = new SpatialIndex();
        City city = new City(1, "Test", UUID.randomUUID(), "world", 100, 64, -40, 1, 0, 0, 32);
        index.add(city);
        assertSame(city, index.at("world", 100 + 15, -40));
        assertNull(index.at("world", 100 + 20, -40), "tier 1: 32x32");

        // same steps as CityManager.upgrade
        index.remove(city);
        city.setTier(2, 64);
        index.add(city);
        for (int[] p : new int[][]{{100 + 20, -40}, {100 + 31, -40 + 31}, {100 - 32, -40 - 32}, {100 - 32, -40 + 31}}) {
            assertSame(city, index.at("world", p[0], p[1]), "tier 2 covers " + p[0] + " " + p[1]);
        }
        assertNull(index.at("world", 100 + 32, -40));
        assertNull(index.at("world", 100 - 33, -40));

        index.remove(city);
        city.setTier(5, 512);
        index.add(city);
        assertSame(city, index.at("world", 100 + 255, -40 - 256));
        assertNull(index.at("world", 100 + 256, -40));
    }
}
