package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.BeaconExtension.BeaconEffects;
import fr.vanillaeconomy.city.BeaconExtension.BeaconSighting;
import fr.vanillaeconomy.city.BeaconExtension.Invalid;
import fr.vanillaeconomy.city.BeaconExtension.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BeaconExtensionTest {

    /** Tier 4 city: 256x256 centred on 0 0, -128..127. */
    private static final Territory CITY = new Territory("world", 0, 0, 256);

    private static BeaconSighting beacon(int x, int pyramid, boolean lit, String primary, String secondary) {
        return new BeaconSighting(new Pos("world", x, 70, 0), pyramid, lit, primary, secondary);
    }

    private static BeaconSighting valid(int x) {
        return beacon(x, 4, true, "minecraft:speed", null);
    }

    private static List<BeaconSighting> valid(int count, int firstX) {
        List<BeaconSighting> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(valid(firstX + 10 * i));
        }
        return list;
    }

    private static BeaconExtension.Check check(int cityTier, List<BeaconSighting> beacons) {
        return BeaconExtension.check(cityTier, 4, 5, 4, beacons, CITY);
    }

    // ------------------------------------------------------------------
    // Purchase conditions
    // ------------------------------------------------------------------

    @Test
    void tierTooLow() {
        BeaconExtension.Check c = check(3, valid(5, 0));
        assertFalse(c.tierOk());
        assertEquals(5, c.valid().size());
        assertFalse(c.ok());
    }

    @Test
    void fourOfFiveRefusedFiveOfFiveAccepted() {
        assertFalse(check(4, valid(4, 0)).ok());
        assertEquals(4, check(4, valid(4, 0)).valid().size());
        assertTrue(check(4, valid(5, 0)).ok());
        assertTrue(check(5, valid(9, 0)).ok());
    }

    @Test
    void duplicatePositionsCountOnce() {
        List<BeaconSighting> beacons = valid(4, 0);
        beacons.add(valid(0));
        beacons.add(valid(0));
        BeaconExtension.Check c = check(4, beacons);
        assertEquals(4, c.valid().size());
        assertFalse(c.ok(), "4 distinct beacons, not 6");
    }

    @Test
    void invalidBeaconsAreCountedByReason() {
        List<BeaconSighting> beacons = valid(4, 0);
        beacons.add(beacon(100, 3, true, "minecraft:speed", null));     // 3-level pyramid
        beacons.add(beacon(110, 0, true, "minecraft:speed", null));     // no pyramid
        beacons.add(valid(128));                                          // just outside (city ends at 127)
        beacons.add(beacon(90, 4, true, null, null));                     // no effect chosen
        beacons.add(beacon(80, 4, false, "minecraft:haste", null));       // beam obstructed
        BeaconExtension.Check c = check(4, beacons);
        assertEquals(4, c.valid().size());
        assertFalse(c.ok());
        assertEquals(Map.of(Invalid.INCOMPLETE_PYRAMID, 2, Invalid.OUTSIDE, 1, Invalid.NO_EFFECT, 1, Invalid.UNLIT, 1),
                c.invalid());
        assertTrue(BeaconExtension.invalidReason(valid(127), CITY, 4).isEmpty(), "last block of the city");
        assertEquals(Invalid.OUTSIDE, BeaconExtension.invalidReason(
                new BeaconSighting(new Pos("world_nether", 0, 70, 0), 4, true, "minecraft:speed", null), CITY, 4).orElseThrow());
    }

    // ------------------------------------------------------------------
    // Union of effects
    // ------------------------------------------------------------------

    @Test
    void sameEffectOnSeveralBeaconsKeepsTheHighestLevelWithoutSum() {
        Map<String, Integer> union = BeaconExtension.union(List.of(
                new BeaconEffects<>("speed", null), new BeaconEffects<>("speed", null), new BeaconEffects<>("speed", null)));
        assertEquals(Map.of("speed", 0), union, "three Speed I beacons: Speed I, not III");
    }

    @Test
    void levelTwoWhenPrimaryEqualsSecondary() {
        Map<String, Integer> union = BeaconExtension.union(List.of(
                new BeaconEffects<>("speed", null), new BeaconEffects<>("speed", "speed")));
        assertEquals(Map.of("speed", 1), union);
    }

    @Test
    void levelTwoReportedAsAmplifierOneIsKept() {
        // Speed II read through the API: primary Speed with amplifier 1, no secondary
        String secondary = BeaconExtension.secondaryKey("minecraft:speed", 1, null);
        assertEquals("minecraft:speed", secondary);
        assertEquals(Map.of("minecraft:speed", 1),
                BeaconExtension.union(List.of(new BeaconEffects<>("minecraft:speed", secondary))));
        // level I with regeneration, or level I alone: unchanged
        assertEquals("minecraft:regeneration", BeaconExtension.secondaryKey("minecraft:speed", 0, "minecraft:regeneration"));
        assertEquals(null, BeaconExtension.secondaryKey("minecraft:speed", 0, null));
        assertEquals(null, BeaconExtension.secondaryKey(null, 0, null));
    }

    @Test
    void secondaryEffectIsRegenerationLevelOne() {
        Map<String, Integer> union = BeaconExtension.union(List.of(
                new BeaconEffects<>("haste", "regeneration"), new BeaconEffects<>("strength", null),
                new BeaconEffects<>(null, null)));
        assertEquals(Map.of("haste", 0, "regeneration", 0, "strength", 0), union);
    }

    @Test
    void neverOverwritesAStrongerOrLongerEffect() {
        assertTrue(BeaconExtension.shouldApply(false, 0, 0, 0, 200), "no current effect");
        assertTrue(BeaconExtension.shouldApply(true, 0, 6000, 1, 200), "level II over a level I potion");
        assertFalse(BeaconExtension.shouldApply(true, 1, 100, 0, 200), "a level II effect is kept");
        assertFalse(BeaconExtension.shouldApply(true, 0, 6000, 0, 200), "a longer potion of the same level is kept");
        assertFalse(BeaconExtension.shouldApply(true, 0, -1, 0, 200), "an infinite effect is kept");
        assertTrue(BeaconExtension.shouldApply(true, 0, 120, 0, 200), "own effect refreshed before it runs out");
    }

    // ------------------------------------------------------------------
    // Players concerned
    // ------------------------------------------------------------------

    @Test
    void playersReceivingTheEffects() {
        assertFalse(BeaconExtension.receives(null, false, false, false), "outside any city");
        assertFalse(BeaconExtension.receives(7, false, false, true), "in a city without the extension");
        assertTrue(BeaconExtension.receives(7, true, false, false), "visitor of a city with the extension");
        assertTrue(BeaconExtension.receives(7, true, false, true));
        assertFalse(BeaconExtension.receives(7, true, true, false), "members-only: visitors get nothing");
        assertTrue(BeaconExtension.receives(7, true, true, true), "members-only: members do");
    }
}
