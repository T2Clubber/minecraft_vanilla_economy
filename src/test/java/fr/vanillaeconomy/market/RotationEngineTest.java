package fr.vanillaeconomy.market;

import fr.vanillaeconomy.market.RotationEngine.Rotation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationEngineTest {

    private static final LinkedHashMap<String, Integer> SLOTS = new LinkedHashMap<>();

    static {
        SLOTS.put("minerals", 1);
        SLOTS.put("food_nature", 3);
        SLOTS.put("hostile_mob", 1);
        SLOTS.put("blocks", 2);
        SLOTS.put("other", 1);
    }

    private static List<String> items(String prefix, int n) {
        return IntStream.range(0, n).mapToObj(i -> prefix + i).toList();
    }

    private static Map<String, List<String>> fullPool() {
        Map<String, List<String>> pool = new LinkedHashMap<>();
        pool.put("minerals", items("min", 9));
        pool.put("food_nature", items("food", 28));
        pool.put("hostile_mob", items("mob", 12));
        pool.put("blocks", items("block", 39));
        pool.put("other", items("other", 30));
        return pool;
    }

    private static Map<String, Map<String, List<String>>> sevenGroups() {
        Map<String, Map<String, List<String>>> pools = new LinkedHashMap<>();
        for (String g : List.of("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga")) {
            pools.put(g, fullPool());
        }
        return pools;
    }

    @Test
    void sellAndBuyNeverOverlapServerWide() {
        RotationEngine<String> engine = new RotationEngine<>(SLOTS, item -> 1.0);
        for (int seed = 0; seed < 500; seed++) {
            Map<String, Rotation<String>> drawn = engine.draw(sevenGroups(), new Random(seed));
            Set<String> sell = new HashSet<>();
            Set<String> buy = new HashSet<>();
            for (Rotation<String> r : drawn.values()) {
                assertEquals(8, r.sell().size());
                assertEquals(8, r.buy().size());
                r.sell().stream().filter(Objects::nonNull).forEach(sell::add);
                r.buy().stream().filter(Objects::nonNull).forEach(buy::add);
                // full pools: no empty slot, no duplicate inside one list
                assertTrue(r.sell().stream().allMatch(Objects::nonNull));
                assertTrue(r.buy().stream().allMatch(Objects::nonNull));
                assertEquals(8, new HashSet<>(r.sell()).size());
                assertEquals(8, new HashSet<>(r.buy()).size());
            }
            sell.retainAll(buy);
            assertTrue(sell.isEmpty(), "overlap " + sell + " seed " + seed);
        }
    }

    @Test
    void slotsFollowCategoryLayout() {
        RotationEngine<String> engine = new RotationEngine<>(SLOTS, item -> 1.0);
        Rotation<String> r = engine.draw(Map.of("plains", fullPool()), new Random(1)).get("plains");
        List<String> expectedPrefixes = List.of("min", "food", "food", "food", "mob", "block", "block", "other");
        for (int i = 0; i < 8; i++) {
            assertTrue(r.sell().get(i).startsWith(expectedPrefixes.get(i)));
            assertTrue(r.buy().get(i).startsWith(expectedPrefixes.get(i)));
        }
    }

    @Test
    void buyDrawFavoursInStockItems() {
        Set<String> inStock = Set.of("min0", "min1");
        RotationEngine<String> engine = new RotationEngine<>(SLOTS, item -> inStock.contains(item) ? 4.0 : 1.0);
        int hits = 0;
        int runs = 5000;
        Random random = new Random(42);
        for (int run = 0; run < runs; run++) {
            String mineral = engine.draw(Map.of("plains", fullPool()), random).get("plains").buy().get(0);
            if (inStock.contains(mineral)) {
                hits++;
            }
        }
        // uniform would be 2/9 ≈ 22%, weighted 8/15 ≈ 53%
        assertTrue(hits > runs * 0.45, "in-stock share too low: " + hits);
    }

    @Test
    void existingClaimsAreRespected() {
        RotationEngine<String> engine = new RotationEngine<>(SLOTS, item -> 1.0);
        Map<String, RotationEngine.Side> claims = new java.util.HashMap<>();
        items("min", 8).forEach(m -> claims.put(m, RotationEngine.Side.SELL));
        for (int seed = 0; seed < 50; seed++) {
            Rotation<String> r = engine.draw(Map.of("new", fullPool()), claims, new Random(seed)).get("new");
            assertEquals("min8", r.buy().get(0));
        }
    }

    @Test
    void tinyPoolLeavesEmptySlotsInsteadOfOverlapping() {
        Map<String, List<String>> pool = new LinkedHashMap<>(fullPool());
        pool.put("minerals", List.of("onlyOne"));
        RotationEngine<String> engine = new RotationEngine<>(SLOTS, item -> 1.0);
        Rotation<String> r = engine.draw(Map.of("plains", pool), new Random(3)).get("plains");
        assertEquals("onlyOne", r.buy().get(0));
        assertEquals(null, r.sell().get(0));
    }
}
