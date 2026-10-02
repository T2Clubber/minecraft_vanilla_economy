package fr.vanillaeconomy.market;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/**
 * Pure rotation draw (no Bukkit dependency, unit-tested).
 *
 * <p>Each biome group (villager type) gets its own SELL list and BUY list, both laid
 * out with the same slots-per-category. Exclusive sides are enforced <b>server-wide</b>:
 * once an item is drawn on one side for any group during a cycle, it can only be drawn
 * on that same side for the other groups. Hence an item is never offered for sale
 * and for purchase at the same time anywhere on the server.
 *
 * <p>The BUY list (villager sells to the player) is drawn first for each group, with
 * weights favouring items currently in stock.
 */
public final class RotationEngine<T> {

    public enum Side { SELL, BUY }

    /** Slot lists in category order; a {@code null} entry is an empty slot. */
    public record Rotation<T>(List<T> sell, List<T> buy) {
    }

    private final LinkedHashMap<String, Integer> slotsPerCategory;
    private final ToDoubleFunction<T> buyWeight;

    /**
     * @param buyWeight weight of an item in the BUY draw (e.g. higher when in stock); must be &gt; 0
     */
    public RotationEngine(LinkedHashMap<String, Integer> slotsPerCategory, ToDoubleFunction<T> buyWeight) {
        this.slotsPerCategory = slotsPerCategory;
        this.buyWeight = buyWeight;
    }

    /**
     * @param pools group -> category -> items available to that group
     */
    public Map<String, Rotation<T>> draw(Map<String, Map<String, List<T>>> pools, Random random) {
        return draw(pools, Map.of(), random);
    }

    /**
     * @param existingClaims sides already taken this cycle (used to add a group to a running cycle)
     */
    public Map<String, Rotation<T>> draw(Map<String, Map<String, List<T>>> pools, Map<T, Side> existingClaims, Random random) {
        List<String> groups = new ArrayList<>(pools.keySet());
        Collections.shuffle(groups, random);
        Map<T, Side> claims = new HashMap<>(existingClaims);
        Map<String, Rotation<T>> result = new LinkedHashMap<>();
        for (String group : groups) {
            Map<String, List<T>> pool = pools.get(group);
            List<T> buy = new ArrayList<>();
            List<T> sell = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : slotsPerCategory.entrySet()) {
                List<T> candidates = pool.getOrDefault(entry.getKey(), List.of());
                int slots = entry.getValue();

                List<T> buyPicks = pick(candidates, slots, Side.BUY, claims, buyWeight, random);
                buyPicks.forEach(item -> claims.put(item, Side.BUY));
                List<T> sellPicks = pick(candidates, slots, Side.SELL, claims, item -> 1.0, random);
                sellPicks.forEach(item -> claims.put(item, Side.SELL));

                pad(buyPicks, slots);
                pad(sellPicks, slots);
                buy.addAll(buyPicks);
                sell.addAll(sellPicks);
            }
            result.put(group, new Rotation<>(Collections.unmodifiableList(sell), Collections.unmodifiableList(buy)));
        }
        return result;
    }

    /** Weighted draw without replacement among items not claimed by the other side. */
    private List<T> pick(List<T> candidates, int count, Side side, Map<T, Side> claims,
                         ToDoubleFunction<T> weight, Random random) {
        List<T> eligible = new ArrayList<>();
        for (T item : candidates) {
            Side claimed = claims.get(item);
            if (claimed == null || claimed == side) {
                eligible.add(item);
            }
        }
        List<T> picks = new ArrayList<>(count);
        while (picks.size() < count && !eligible.isEmpty()) {
            double total = 0;
            for (T item : eligible) {
                total += Math.max(1e-9, weight.applyAsDouble(item));
            }
            double r = random.nextDouble() * total;
            int index = eligible.size() - 1;
            for (int i = 0; i < eligible.size(); i++) {
                r -= Math.max(1e-9, weight.applyAsDouble(eligible.get(i)));
                if (r < 0) {
                    index = i;
                    break;
                }
            }
            picks.add(eligible.remove(index));
        }
        return picks;
    }

    /** Sides claimed by a set of rotations. */
    public static <T> Map<T, Side> claimsOf(Iterable<Rotation<T>> rotations) {
        Map<T, Side> claims = new HashMap<>();
        for (Rotation<T> rotation : rotations) {
            rotation.sell().stream().filter(java.util.Objects::nonNull).forEach(item -> claims.put(item, Side.SELL));
            rotation.buy().stream().filter(java.util.Objects::nonNull).forEach(item -> claims.put(item, Side.BUY));
        }
        return claims;
    }

    private static <T> void pad(List<T> list, int size) {
        while (list.size() < size) {
            list.add(null);
        }
    }
}
