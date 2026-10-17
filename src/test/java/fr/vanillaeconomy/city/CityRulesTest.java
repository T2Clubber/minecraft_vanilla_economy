package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.PresenceChange;
import fr.vanillaeconomy.city.CityRules.PresenceKind;
import fr.vanillaeconomy.city.CityRules.Tier;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static fr.vanillaeconomy.city.CityRole.CO_OWNER;
import static fr.vanillaeconomy.city.CityRole.MEMBER;
import static fr.vanillaeconomy.city.CityRole.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityRulesTest {

    // --- territory / overlap ------------------------------------------------

    @Test
    void initialTerritoryIs32x32AroundTheCenter() {
        Territory t = new Territory("world", 100, -50, 32);
        assertEquals(84, t.minX());
        assertEquals(115, t.maxX());
        assertEquals(-66, t.minZ());
        assertEquals(-35, t.maxZ());
        assertTrue(t.contains("world", 84, -66));
        assertTrue(t.contains("world", 115, -35));
        assertFalse(t.contains("world", 116, -50));
        assertFalse(t.contains("world", 83, -50));
        assertFalse(t.contains("world_nether", 100, -50));
    }

    @Test
    void expansionIsSymmetricAroundTheOriginalCenter() {
        Territory t = new Territory("world", 0, 0, 32).withSize(48);
        assertEquals(-24, t.minX());
        assertEquals(23, t.maxX());
        assertEquals(0, t.centerX());
    }

    @Test
    void overlapAndMinimumGap() {
        Territory a = new Territory("world", 0, 0, 32);           // -16..15
        assertTrue(a.conflicts(new Territory("world", 20, 0, 32), 0));   // 4..35 overlaps
        assertFalse(a.conflicts(new Territory("world", 32, 0, 32), 0));  // 16..47 touches, no overlap
        assertTrue(a.conflicts(new Territory("world", 32, 0, 32), 1));   // 0 free block < gap 1
        assertFalse(a.conflicts(new Territory("world", 48, 0, 32), 16)); // 32..63 : 16 free blocks
        assertTrue(a.conflicts(new Territory("world", 47, 0, 32), 16));  // 31..62 : 15 free blocks
        assertFalse(a.conflicts(new Territory("world", 20, 100, 32), 16), "far in z");
        assertTrue(a.conflicts(new Territory("world", 20, 20, 32), 0), "diagonal overlap");
        assertFalse(a.conflicts(new Territory("world_nether", 0, 0, 32), 16), "other world");
        assertTrue(a.conflicts(a, 0));
    }

    @Test
    void chunkRangeCoversNegativeCoordinates() {
        Territory t = new Territory("world", 0, 0, 32); // -16..15
        assertEquals(-1, t.minChunkX());
        assertEquals(0, t.maxChunkX());
    }

    // --- roles ----------------------------------------------------------------

    @Test
    void rolePermissions() {
        assertTrue(OWNER.canAddMembers());
        assertTrue(CO_OWNER.canAddMembers());
        assertFalse(MEMBER.canAddMembers());

        assertTrue(OWNER.canKick(MEMBER));
        assertTrue(OWNER.canKick(CO_OWNER));
        assertFalse(OWNER.canKick(OWNER));
        assertTrue(CO_OWNER.canKick(MEMBER));
        assertFalse(CO_OWNER.canKick(CO_OWNER), "no action on another co-owner");
        assertFalse(CO_OWNER.canKick(OWNER), "no action on the owner");
        assertFalse(MEMBER.canKick(MEMBER));

        assertTrue(OWNER.canSetCoOwner(MEMBER));
        assertTrue(OWNER.canSetCoOwner(CO_OWNER));
        assertFalse(OWNER.canSetCoOwner(OWNER));
        assertFalse(CO_OWNER.canSetCoOwner(MEMBER));

        assertTrue(OWNER.canBuyTier());
        assertFalse(CO_OWNER.canBuyTier());
        assertFalse(MEMBER.canBuyTier());
        assertTrue(OWNER.canDisband());
        assertFalse(CO_OWNER.canDisband());

        assertFalse(OWNER.canLeave(), "the owner must disband");
        assertTrue(CO_OWNER.canLeave());
        assertTrue(MEMBER.canLeave());
    }

    // --- refunds --------------------------------------------------------------

    @Test
    void refundIsProrataAndExact() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3);
        Map<UUID, Long> contributions = new LinkedHashMap<>();
        contributions.put(a, 600L);
        contributions.put(b, 300L);
        contributions.put(c, 100L);
        assertEquals(Map.of(a, 300L, b, 150L, c, 50L), CityRules.refunds(contributions, 500, a));

        Map<UUID, Long> r = CityRules.refunds(Map.of(a, 1L, b, 1L, c, 1L), 100, a);
        assertEquals(100, r.values().stream().mapToLong(Long::longValue).sum(), "no coin lost or created");
        assertTrue(r.values().stream().allMatch(v -> v == 33 || v == 34));
    }

    @Test
    void refundAlwaysSumsToTheBalance() {
        Random random = new Random(11);
        for (int run = 0; run < 2000; run++) {
            Map<UUID, Long> contributions = new LinkedHashMap<>();
            int players = 1 + random.nextInt(8);
            for (int i = 0; i < players; i++) {
                contributions.put(new UUID(1, i), (long) random.nextInt(5000));
            }
            long balance = random.nextInt(20000);
            long sum = CityRules.refunds(contributions, balance, new UUID(9, 9)).values().stream().mapToLong(Long::longValue).sum();
            assertEquals(balance, sum);
        }
    }

    @Test
    void refundEdgeCases() {
        UUID owner = new UUID(0, 7);
        assertTrue(CityRules.refunds(Map.of(owner, 500L), 0, owner).isEmpty(), "empty balance");
        assertEquals(Map.of(owner, 42L), CityRules.refunds(Map.of(), 42, owner), "no contribution: owner gets it");
    }

    // --- tiers ----------------------------------------------------------------

    @Test
    void tierValidation() {
        List<Tier> ok = List.of(new Tier(1, 32, 0), new Tier(2, 48, 2000), new Tier(3, 64, 6000),
                new Tier(4, 96, 18000), new Tier(5, 128, 50000));
        assertTrue(CityRules.validateTiers(ok).isEmpty());
        assertEquals(48, CityRules.nextTier(ok, 1).orElseThrow().size());
        assertTrue(CityRules.nextTier(ok, 5).isEmpty(), "max tier");

        assertFalse(CityRules.validateTiers(List.of()).isEmpty());
        assertFalse(CityRules.validateTiers(List.of(new Tier(1, 32, 0), new Tier(2, 32, 10))).isEmpty(), "same size");
        assertFalse(CityRules.validateTiers(List.of(new Tier(1, 32, 0), new Tier(3, 48, 10))).isEmpty(), "gap in levels");
        assertFalse(CityRules.validateTiers(List.of(new Tier(1, 32, 0), new Tier(2, 48, -1))).isEmpty(), "negative price");
    }

    // --- presence -------------------------------------------------------------

    @Test
    void presenceTransitions() {
        assertEquals(List.of(new PresenceChange(PresenceKind.ENTER, 1)), CityRules.transitions(null, 1), "none -> city");
        assertEquals(List.of(new PresenceChange(PresenceKind.EXIT, 1)), CityRules.transitions(1, null), "city -> none");
        assertEquals(List.of(new PresenceChange(PresenceKind.EXIT, 1), new PresenceChange(PresenceKind.ENTER, 2)),
                CityRules.transitions(1, 2), "A -> B: exit then enter");
        assertTrue(CityRules.transitions(1, 1).isEmpty(), "same city: no message");
        assertTrue(CityRules.transitions(null, null).isEmpty());
    }

    // --- visitors -------------------------------------------------------------

    @Test
    void visitorsMayOnlyUseDoorsAndTrapdoors() {
        Set<Material> allowed = Set.of(Material.OAK_DOOR, Material.SPRUCE_DOOR, Material.OAK_TRAPDOOR, Material.IRON_TRAPDOOR);
        assertTrue(CityRules.visitorMayInteract(Material.OAK_DOOR, allowed));
        assertTrue(CityRules.visitorMayInteract(Material.OAK_TRAPDOOR, allowed));
        Set<Material> withEnderChest = new java.util.HashSet<>(allowed);
        withEnderChest.add(Material.ENDER_CHEST);
        assertTrue(CityRules.visitorMayInteract(Material.ENDER_CHEST, withEnderChest), "personal storage");
        assertFalse(CityRules.visitorMayInteract(Material.CHEST, withEnderChest));
        for (Material denied : List.of(Material.CHEST, Material.BARREL, Material.FURNACE, Material.HOPPER,
                Material.SHULKER_BOX, Material.STONE_BUTTON, Material.LEVER, Material.OAK_PRESSURE_PLATE,
                Material.OAK_FENCE_GATE, Material.CRAFTING_TABLE, Material.ANVIL, Material.LECTERN, Material.OAK_SIGN)) {
            assertFalse(CityRules.visitorMayInteract(denied, allowed), denied.name());
        }
        assertFalse(CityRules.visitorMayInteract(null, allowed));
    }

    // --- names ----------------------------------------------------------------

    @Test
    void cityNames() {
        assertTrue(CityRules.validName("Bourg-Palette_2", 3, 16));
        assertFalse(CityRules.validName("ab", 3, 16));
        assertFalse(CityRules.validName("un nom avec espaces", 3, 32));
        assertFalse(CityRules.validName("Cité", 3, 16), "accents refused (names are typed in commands)");
        assertFalse(CityRules.validName("abcdefghijklmnopq", 3, 16));
    }

    @Test
    void teleportGoesToTheFirstFreeSpaceAtOrAboveTheFoundingHeight() {
        Set<Integer> built = Set.of(70, 71, 72, 75);   // blocks placed on the centre column
        assertEquals(64, CityRules.safeY(64, -64, 320, y -> !built.contains(y)), "free: founding height kept");
        assertEquals(73, CityRules.safeY(70, -64, 320, y -> !built.contains(y)), "never inside the blocks built since");
        assertEquals(76, CityRules.safeY(74, -64, 320, y -> !built.contains(y)), "two free blocks are needed (feet + head)");
        assertEquals(Integer.MIN_VALUE, CityRules.safeY(300, -64, 320, y -> y < 300), "full column: no teleport");
        assertEquals(318, CityRules.safeY(318, -64, 320, y -> true), "head at the last block of the world");
        assertEquals(Integer.MIN_VALUE, CityRules.safeY(319, -64, 320, y -> true));
        assertEquals(-64, CityRules.safeY(-100, -64, 320, y -> true), "clamped to the bottom of the world");
    }

    @Test
    void onlyTheOwnerBuysExtensions() {
        assertTrue(OWNER.canBuyExtension());
        assertFalse(CO_OWNER.canBuyExtension());
        assertFalse(MEMBER.canBuyExtension());
    }

    @Test
    void onlyTheOwnerRenamesHisCity() {
        assertTrue(OWNER.canRename());
        assertFalse(CO_OWNER.canRename());
        assertFalse(MEMBER.canRename());
    }

    @Test
    void ranking_tierThenExtensionsThenTreasury() {
        List<CityRules.Standing> ranked = CityRules.rank(List.of(
                new CityRules.Standing("Riche", 2, 0, 90_000),
                new CityRules.Standing("Grande", 3, 0, 10),
                new CityRules.Standing("Equipee", 2, 1, 0),
                new CityRules.Standing("beta", 1, 0, 500),
                new CityRules.Standing("Alpha", 1, 0, 500)));
        assertEquals(List.of("Grande", "Equipee", "Riche", "Alpha", "beta"),
                ranked.stream().map(CityRules.Standing::name).toList(),
                "tier first, then extensions, then treasury, then name");
    }

    // ------------------------------------------------------------------
    // Promotion en folie: purchase conditions
    // ------------------------------------------------------------------

    private static final List<String> SEVEN = List.of("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga");
    private static final Territory CITY = new Territory("world", 0, 0, 128);

    private static CityRules.NitwitSighting nitwit(String type) {
        return new CityRules.NitwitSighting(type, true, true, true, true, "world", 10, -10);
    }

    @Test
    void frenzy_tierTooLow() {
        List<CityRules.NitwitSighting> all = SEVEN.stream().map(CityRulesTest::nitwit).toList();
        CityRules.FrenzyCheck check = CityRules.frenzyCheck(2, 3, SEVEN, all, CITY);
        assertFalse(check.tierOk());
        assertTrue(check.missing().isEmpty());
        assertFalse(check.ok(), "tier 3 is required even with the 7 skins");
    }

    @Test
    void frenzy_missingSkinsAreListedInConfigOrder() {
        List<CityRules.NitwitSighting> some = List.of(nitwit("jungle"), nitwit("plains"), nitwit("snow"),
                nitwit("swamp"), nitwit("taiga"));
        CityRules.FrenzyCheck check = CityRules.frenzyCheck(3, 3, SEVEN, some, CITY);
        assertEquals(List.of("desert", "savanna"), check.missing());
        assertFalse(check.ok());
    }

    @Test
    void frenzy_sevenOfSevenValid() {
        List<CityRules.NitwitSighting> all = SEVEN.stream().map(CityRulesTest::nitwit).toList();
        assertTrue(CityRules.frenzyCheck(3, 3, SEVEN, all, CITY).ok());
        assertTrue(CityRules.frenzyCheck(5, 3, SEVEN, all, CITY).ok());
    }

    @Test
    void frenzy_invalidNitwitsAreIgnored() {
        List<CityRules.NitwitSighting> sightings = new java.util.ArrayList<>(
                SEVEN.stream().filter(t -> !t.equals("desert")).map(CityRulesTest::nitwit).toList());
        sightings.add(new CityRules.NitwitSighting("desert", true, false, true, true, "world", 0, 0));   // baby
        sightings.add(new CityRules.NitwitSighting("desert", true, true, true, false, "world", 0, 0));   // not tagged
        sightings.add(new CityRules.NitwitSighting("desert", true, true, false, true, "world", 0, 0));   // farmer
        sightings.add(new CityRules.NitwitSighting("desert", false, true, true, true, "world", 0, 0));   // dead
        sightings.add(new CityRules.NitwitSighting("desert", true, true, true, true, "world", 64, 0));   // just outside
        sightings.add(new CityRules.NitwitSighting("desert", true, true, true, true, "world_nether", 0, 0));
        assertEquals(List.of("desert"), CityRules.frenzyCheck(3, 3, SEVEN, sightings, CITY).missing());
        // a 128x128 city centred on 0 0 covers -64..63: the chunks scanned overhang it
        sightings.add(new CityRules.NitwitSighting("desert", true, true, true, true, "world", 63, -64));
        assertTrue(CityRules.frenzyCheck(3, 3, SEVEN, sightings, CITY).ok());
    }

    @Test
    void frenzy_duplicatesOfOneTypeCountOnce() {
        List<CityRules.NitwitSighting> plainsOnly = List.of(nitwit("plains"), nitwit("plains"), nitwit("plains"),
                nitwit("plains"), nitwit("plains"), nitwit("plains"), nitwit("plains"));
        assertEquals(Set.of("plains"), CityRules.presentTypes(plainsOnly, CITY));
        assertEquals(6, CityRules.frenzyCheck(3, 3, SEVEN, plainsOnly, CITY).missing().size());
    }
}
