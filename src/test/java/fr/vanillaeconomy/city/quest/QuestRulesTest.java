package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.quest.QuestRules.Resume;
import fr.vanillaeconomy.city.quest.QuestRules.SavedProgress;
import fr.vanillaeconomy.city.quest.QuestRules.Slot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestRulesTest {

    static QuestDefinition quest(String id, String difficulty, long amount, int weight, String... materials) {
        return new QuestDefinition(id, id, difficulty, new TreeSet<>(List.of(materials)), null, amount, true, 100, 24,
                weight, null);
    }

    static QuestCatalog catalog(Map<String, Integer> difficultyWeights, QuestDefinition... quests) {
        Map<String, QuestDifficulty> difficulties = new HashMap<>();
        difficultyWeights.forEach((id, w) -> difficulties.put(id, new QuestDifficulty(id, id, "white", w, 24)));
        Map<String, QuestDefinition> map = new LinkedHashMap<>();
        for (QuestDefinition q : quests) {
            map.put(q.id(), q);
        }
        return new QuestCatalog(difficulties, map, List.of());
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Test
    void neverTheSameQuestTwiceInARow() {
        QuestCatalog catalog = catalog(Map.of("EASY", 1), quest("a", "EASY", 5, 1, "SPONGE"),
                quest("b", "EASY", 5, 1, "RABBIT_FOOT"), quest("c", "EASY", 5, 1, "GLOW_INK_SAC"));
        Random random = new Random(3);
        String previous = null;
        for (int i = 0; i < 2000; i++) {
            String drawn = QuestRules.draw(catalog, previous, Set.of(), true, random).orElseThrow().quest().id();
            assertNotEquals(previous, drawn);
            previous = drawn;
        }
    }

    @Test
    void theOnlyEligibleQuestMayComeBack() {
        QuestCatalog catalog = catalog(Map.of("EASY", 1), quest("a", "EASY", 5, 1, "SPONGE"));
        assertEquals("a", QuestRules.draw(catalog, "a", Set.of(), true, new Random(1)).orElseThrow().quest().id());
    }

    @Test
    void repeatCompletedFalseLeavesCompletedQuestsOutThenResets() {
        QuestCatalog catalog = catalog(Map.of("EASY", 1), quest("a", "EASY", 5, 1, "SPONGE"),
                quest("b", "EASY", 5, 1, "RABBIT_FOOT"), quest("c", "EASY", 5, 1, "GLOW_INK_SAC"));
        Random random = new Random(5);
        for (int i = 0; i < 200; i++) {
            var draw = QuestRules.draw(catalog, null, Set.of("a", "b"), false, random).orElseThrow();
            assertEquals("c", draw.quest().id(), "only the quest not completed yet");
            assertFalse(draw.cycleReset());
        }
        var reset = QuestRules.draw(catalog, "c", Set.of("a", "b", "c"), false, random).orElseThrow();
        assertTrue(reset.cycleReset(), "all completed: the list starts again");
        assertNotEquals("c", reset.quest().id(), "still not twice in a row");
        // repeat-completed true: completed quests stay in the draw
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(QuestRules.draw(catalog, null, Set.of("a", "b"), true, random).orElseThrow().quest().id());
        }
        assertEquals(Set.of("a", "b", "c"), seen);
    }

    @Test
    void twoStepWeightedDraw() {
        QuestCatalog catalog = catalog(Map.of("EASY", 90, "HARD", 10),
                quest("e1", "EASY", 5, 3, "SPONGE"), quest("e2", "EASY", 5, 1, "RABBIT_FOOT"),
                quest("h1", "HARD", 5, 1, "HEAVY_CORE"), quest("never", "HARD", 5, 0, "TOTEM_OF_UNDYING"));
        Map<String, Integer> counts = new HashMap<>();
        Random random = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            counts.merge(QuestRules.draw(catalog, null, Set.of(), true, random).orElseThrow().quest().id(), 1, Integer::sum);
        }
        assertFalse(counts.containsKey("never"), "weight 0");
        // EASY 90 % then e1 3/4 of it: ~67.5 %, e2 ~22.5 %, h1 ~10 %
        assertEquals(0.675, counts.get("e1") / 20_000.0, 0.02);
        assertEquals(0.225, counts.get("e2") / 20_000.0, 0.02);
        assertEquals(0.10, counts.get("h1") / 20_000.0, 0.015);
    }

    @Test
    void nothingToDrawWithoutValidQuest() {
        assertTrue(QuestRules.draw(QuestCatalog.EMPTY, null, Set.of(), true, new Random()).isEmpty());
        QuestCatalog zero = catalog(Map.of("EASY", 0), quest("a", "EASY", 5, 1, "SPONGE"));
        assertTrue(QuestRules.draw(zero, null, Set.of(), true, new Random()).isEmpty(), "difficulty of weight 0");
    }

    // ------------------------------------------------------------------
    // Progress kept between passages
    // ------------------------------------------------------------------

    @Test
    void questWithoutSavedProgressStartsANewAttempt() {
        Resume r = QuestRules.resume(null, quest("a", "EASY", 10, 1, "SPONGE"));
        assertEquals(new Resume(0, null, 1, false), r);
        assertFalse(r.resumed());
    }

    @Test
    void savedProgressIsResumedOnTheSameAttempt() {
        QuestDefinition q = quest("a", "EASY", 10, 1, "SPONGE");
        Resume r = QuestRules.resume(new SavedProgress(q.signature(), 7, 42, 2), q);
        assertEquals(new Resume(7, 42L, 3, false), r, "7/10 resumed, attempt 42, third passage");
        assertTrue(r.resumed());
    }

    @Test
    void changedItemResetsTheProgress() {
        QuestDefinition q = quest("a", "EASY", 10, 1, "RABBIT_FOOT");
        Resume r = QuestRules.resume(new SavedProgress("materials:SPONGE", 7, 42, 2), q);
        assertEquals(new Resume(0, null, 1, true), r);
    }

    @Test
    void loweredAmountIsClampedToTargetMinusOneRaisedAmountKeepsTheProgress() {
        SavedProgress saved = new SavedProgress("materials:SPONGE", 8, 42, 1);
        assertEquals(4, QuestRules.resume(saved, quest("a", "EASY", 5, 1, "SPONGE")).progress(), "one deposit still needed");
        assertEquals(0, QuestRules.resume(saved, quest("a", "EASY", 1, 1, "SPONGE")).progress());
        assertEquals(8, QuestRules.resume(saved, quest("a", "EASY", 20, 1, "SPONGE")).progress());
    }

    // ------------------------------------------------------------------
    // Time and deposits
    // ------------------------------------------------------------------

    @Test
    void reminderIsCappedAtHalfTheDuration() {
        long h = 3_600_000L;
        assertEquals(42 * h, QuestRules.reminderAt(0, 48 * h, 6), "6 h before the end of a 48 h quest");
        assertEquals(4 * h, QuestRules.reminderAt(0, 8 * h, 6), "8 h quest: at most 4 h before");
    }

    @Test
    void depositNeverTakesMoreThanNeededNorACoin() {
        List<Slot> slots = List.of(new Slot(0, "SPONGE", 7, false), new Slot(1, "GOLD_NUGGET", 64, true),
                new Slot(2, "SPONGE", 7, false), new Slot(3, "DIRT", 64, false), new Slot(4, "SPONGE", 3, true));
        var takes = QuestRules.planDeposit(slots, Set.of("SPONGE"), 10, null);
        assertEquals(List.of(new QuestRules.Take(0, 7), new QuestRules.Take(2, 3)), takes, "surplus (4) stays");
        assertEquals(10, QuestRules.total(takes));
        assertEquals(5, QuestRules.total(QuestRules.planDeposit(slots, Set.of("SPONGE"), 10, 5L)), "quantity asked");
        assertEquals(0, QuestRules.total(QuestRules.planDeposit(slots, Set.of("GOLD_NUGGET"), 10, null)),
                "an item tagged by the plugin (coin) is never taken");
    }

    @Test
    void multiItemAndTagQuestsAddUpEveryAcceptedItem() {
        List<Slot> slots = List.of(new Slot(0, "ANGLER_POTTERY_SHERD", 2, false),
                new Slot(5, "ARCHER_POTTERY_SHERD", 4, false), new Slot(9, "ARMS_UP_POTTERY_SHERD", 1, false));
        Set<String> sherds = QuestDefinitionLoaderTest.SHERD_ITEMS;
        assertEquals(7, QuestRules.total(QuestRules.planDeposit(slots, sherds, 12, null)));
    }

    @Test
    void onlyMembersDepositOnAnActiveQuest() {
        assertEquals("not_member", QuestRules.depositRefusal(false, true, CityQuest.Status.ACTIVE).orElseThrow());
        assertEquals("quest_no_extension", QuestRules.depositRefusal(true, false, CityQuest.Status.ACTIVE).orElseThrow());
        assertEquals("quest_none", QuestRules.depositRefusal(true, true, null).orElseThrow());
        assertEquals("quest_already_completed",
                QuestRules.depositRefusal(true, true, CityQuest.Status.COMPLETED).orElseThrow());
        assertTrue(QuestRules.depositRefusal(true, true, CityQuest.Status.ACTIVE).isEmpty());
    }

    @Test
    void progressBar() {
        assertEquals("▮▮▮▮▮▯▯▯▯▯", QuestRules.bar(5, 10, 10));
        assertEquals("▮▮▮▮▮▮▮▮▮▮", QuestRules.bar(12, 10, 10));
    }
}
