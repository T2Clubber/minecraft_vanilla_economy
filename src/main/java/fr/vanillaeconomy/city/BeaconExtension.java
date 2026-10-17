package fr.vanillaeconomy.city;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extension Beacon (pure, unit-tested): purchase conditions, validity of one beacon, union
 * of the effects of the valid beacons of a city, who receives them.
 */
public final class BeaconExtension {

    private BeaconExtension() {
    }

    /** Position of a block. */
    public record Pos(String world, int x, int y, int z) {
    }

    /**
     * A beacon seen in the chunks of a city. {@code primary} / {@code secondary}: effect keys
     * (e.g. "minecraft:speed"), null when none was chosen; {@code lit}: beam not obstructed.
     */
    public record BeaconSighting(Pos pos, int pyramid, boolean lit, String primary, String secondary) {
    }

    /** Why a beacon does not count, in the order they are checked. */
    public enum Invalid { OUTSIDE, INCOMPLETE_PYRAMID, UNLIT, NO_EFFECT }

    /** Empty when the beacon is valid: inside the city, full pyramid, lit, primary effect chosen. */
    public static Optional<Invalid> invalidReason(BeaconSighting beacon, Territory territory, int requiredPyramid) {
        if (!territory.contains(beacon.pos().world(), beacon.pos().x(), beacon.pos().z())) {
            return Optional.of(Invalid.OUTSIDE);
        }
        if (beacon.pyramid() < requiredPyramid) {
            return Optional.of(Invalid.INCOMPLETE_PYRAMID);
        }
        if (!beacon.lit()) {
            return Optional.of(Invalid.UNLIT);
        }
        if (beacon.primary() == null) {
            return Optional.of(Invalid.NO_EFFECT);
        }
        return Optional.empty();
    }

    /** Result of the purchase conditions. */
    public record Check(boolean tierOk, List<BeaconSighting> valid, int required, Map<Invalid, Integer> invalid) {
        public boolean ok() {
            return tierOk && valid.size() >= required;
        }
    }

    /** Conditions: city tier, and {@code required} valid beacons at distinct positions. */
    public static Check check(int cityTier, int minTier, int required, int requiredPyramid,
                              Collection<BeaconSighting> sightings, Territory territory) {
        Map<Pos, BeaconSighting> distinct = new LinkedHashMap<>();
        sightings.forEach(b -> distinct.putIfAbsent(b.pos(), b));
        List<BeaconSighting> valid = new ArrayList<>();
        Map<Invalid, Integer> invalid = new EnumMap<>(Invalid.class);
        for (BeaconSighting b : distinct.values()) {
            invalidReason(b, territory, requiredPyramid).ifPresentOrElse(
                    reason -> invalid.merge(reason, 1, Integer::sum), () -> valid.add(b));
        }
        return new Check(cityTier >= minTier, valid, required, invalid);
    }

    /** The two effects of one beacon. */
    public record BeaconEffects<T>(T primary, T secondary) {
    }

    /**
     * Effect -> amplifier given by a set of beacons, as in vanilla: level II (amplifier 1)
     * when the secondary effect is the primary one, otherwise level I for the primary and
     * level I for the secondary (regeneration). Several beacons with the same effect give
     * the highest level, never a sum.
     */
    public static <T> Map<T, Integer> union(Collection<BeaconEffects<T>> beacons) {
        Map<T, Integer> effects = new HashMap<>();
        for (BeaconEffects<T> b : beacons) {
            if (b.primary() == null) {
                continue;
            }
            boolean doubled = b.primary().equals(b.secondary());
            effects.merge(b.primary(), doubled ? 1 : 0, Math::max);
            if (b.secondary() != null && !doubled) {
                effects.merge(b.secondary(), 0, Math::max);
            }
        }
        return effects;
    }

    /**
     * Whether to (re)apply an effect of {@code amplifier} lasting {@code duration} ticks to a
     * player who has it with {@code currentAmplifier} / {@code currentDuration} ({@code
     * hasCurrent} false when he has none, duration -1 = infinite): never replaces a stronger
     * or longer effect.
     */
    public static boolean shouldApply(boolean hasCurrent, int currentAmplifier, int currentDuration,
                                      int amplifier, int duration) {
        if (!hasCurrent || currentAmplifier < amplifier) {
            return true;
        }
        if (currentAmplifier > amplifier || currentDuration < 0) {
            return false;
        }
        return currentDuration < duration;
    }

    /**
     * Whether a player standing in a city ({@code cityId}, null outside) receives its beacon
     * effects: the city has the extension and, with members-only, the player is a member.
     */
    public static boolean receives(Integer cityId, boolean cityHasExtension, boolean membersOnly, boolean member) {
        return cityId != null && cityHasExtension && (!membersOnly || member);
    }
}
