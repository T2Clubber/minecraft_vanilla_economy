package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.BeaconExtension.BeaconEffects;
import fr.vanillaeconomy.city.BeaconExtension.BeaconSighting;
import fr.vanillaeconomy.city.BeaconExtension.Pos;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Valid beacons of each city with the Beacon extension, and the union of their effects
 * (recomputed only when the set changes). Memory only: the periodic task reads it without
 * any scan or database access.
 */
public final class BeaconEffectCache {

    private final Map<Integer, Map<Pos, BeaconEffects<PotionEffectType>>> beacons = new HashMap<>();
    private final Map<Integer, Map<PotionEffectType, Integer>> unions = new HashMap<>();

    /** The valid beacons of a city, as found by a full scan. */
    public void replace(int cityId, Collection<BeaconSighting> valid) {
        Map<Pos, BeaconEffects<PotionEffectType>> byPos = new HashMap<>();
        valid.forEach(b -> byPos.put(b.pos(), effects(b)));
        beacons.put(cityId, byPos);
        unions.put(cityId, BeaconExtension.union(byPos.values()));
    }

    /** A beacon found valid again (new one, other effect chosen). */
    public void put(int cityId, BeaconSighting valid) {
        BeaconEffects<PotionEffectType> effects = effects(valid);
        Map<Pos, BeaconEffects<PotionEffectType>> byPos = beacons.computeIfAbsent(cityId, k -> new HashMap<>());
        if (!effects.equals(byPos.put(valid.pos(), effects))) {
            unions.put(cityId, BeaconExtension.union(byPos.values()));
        }
    }

    /** A beacon no longer valid (destroyed, pyramid broken, obstructed, no effect). */
    public void remove(int cityId, Pos pos) {
        Map<Pos, BeaconEffects<PotionEffectType>> byPos = beacons.get(cityId);
        if (byPos != null && byPos.remove(pos) != null) {
            unions.put(cityId, BeaconExtension.union(byPos.values()));
        }
    }

    public void clear(int cityId) {
        beacons.remove(cityId);
        unions.remove(cityId);
    }

    public Set<Pos> positions(int cityId) {
        return Set.copyOf(beacons.getOrDefault(cityId, Map.of()).keySet());
    }

    /** Effect -> amplifier of the city, empty if it has no valid beacon. */
    public Map<PotionEffectType, Integer> effects(int cityId) {
        return unions.getOrDefault(cityId, Map.of());
    }

    private static BeaconEffects<PotionEffectType> effects(BeaconSighting b) {
        return new BeaconEffects<>(type(b.primary()), type(b.secondary()));
    }

    private static PotionEffectType type(String key) {
        NamespacedKey id = key == null ? null : NamespacedKey.fromString(key);
        return id == null ? null : Registry.MOB_EFFECT.get(id);
    }
}
