package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.quest.CityQuest.Status;
import fr.vanillaeconomy.city.quest.QuestProgressRepository.Contributor;
import fr.vanillaeconomy.storage.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quest engine on a real SQLite database, with a controlled clock. */
class CityQuestManagerTest {

    static final long H = 3_600_000L;
    static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    Database db;
    final AtomicLong clock = new AtomicLong(1_000 * H);
    final List<String> events = new ArrayList<>();
    CityQuestManager manager;
    int paris;
    int lyon;

    final CityQuestManager.Events recorder = new CityQuestManager.Events() {
        @Override
        public void drawn(CityQuest quest) {
            events.add("drawn " + quest.questId() + " " + quest.cityId() + " p" + quest.passage() + " " + quest.progress());
        }

        @Override
        public void completed(CityQuest quest, List<Contributor> top) {
            events.add("completed " + quest.questId() + " " + quest.cityId());
        }

        @Override
        public void expired(CityQuest quest, boolean progressKept) {
            events.add("expired " + quest.questId() + " " + quest.cityId() + (progressKept ? " kept" : ""));
        }

        @Override
        public void nothingToDraw(int cityId) {
            events.add("nothing " + cityId);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(dir.resolve("economy.db").toFile());
        paris = city("Paris");
        lyon = city("Lyon");
        manager = manager(QuestOptions.DEFAULTS);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    CityQuestManager manager(QuestOptions options) {
        CityQuestManager m = new CityQuestManager(db, options, clock::get, new Random(7), recorder);
        m.setCatalog(single(10, "SPONGE"));
        return m;
    }

    int city(String name) throws Exception {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO city(name, owner_uuid, world, center_x, center_z, tier, balance, created_at) VALUES(?, ?, 'world', 0, 0, 1, 0, 0)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, UUID.randomUUID().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    static QuestCatalog single(long amount, String material) {
        return catalog(QuestRulesTest.quest("sponge", "EASY", amount, 1, material));
    }

    static QuestCatalog catalog(QuestDefinition... quests) {
        return QuestRulesTest.catalog(Map.of("EASY", 1), quests);
    }

    long balance(int cityId) throws Exception {
        return scalar("SELECT balance FROM city WHERE id = " + cityId);
    }

    long scalar(String sql) throws Exception {
        try (Statement st = db.connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    CityQuest current(int cityId) {
        return manager.current(cityId).orElseThrow();
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------

    @Test
    void firstQuestStartsAtPurchaseWithItsEndFrozen() throws Exception {
        manager.start(paris);
        CityQuest q = current(paris);
        assertEquals(clock.get(), q.startsAt());
        assertEquals(clock.get() + 24 * H, q.endsAt(), "duration of the quest, frozen at the draw");
        assertEquals(Status.ACTIVE, q.status());
        manager.start(paris);
        assertEquals(q.id(), current(paris).id(), "a second purchase notification draws nothing");
    }

    @Test
    void chainWithoutDrift() throws Exception {
        manager.start(paris);
        long firstEnd = current(paris).endsAt();
        clock.set(firstEnd + 25_000);          // the check runs a little after the end
        manager.tick(List.of(paris), false);
        CityQuest next = current(paris);
        assertEquals(firstEnd, next.startsAt(), "starts at the previous end, not at the check");
        assertEquals(firstEnd + 24 * H, next.endsAt());
    }

    @Test
    void catchUpAfterAStopDrawsOneQuestNowWithoutReward() throws Exception {
        manager.start(paris);
        manager.deposit(paris, ALICE, 3);
        clock.addAndGet(100 * H);              // server off for 4 days
        CityQuestManager restarted = new CityQuestManager(db, QuestOptions.DEFAULTS, clock::get, new Random(1), recorder);
        restarted.setCatalog(single(10, "SPONGE"));
        restarted.load();
        events.clear();
        restarted.tick(List.of(paris), true);
        assertEquals(List.of("expired sponge " + paris + " kept", "drawn sponge " + paris + " p2 3"), events,
                "one expiry, one new quest, no chain");
        assertEquals(clock.get(), restarted.current(paris).orElseThrow().startsAt());
        assertEquals(0, balance(paris), "no reward");
    }

    @Test
    void questCompletedEarlyWaitsForItsEnd() throws Exception {
        manager.start(paris);
        CityQuest q = current(paris);
        manager.deposit(paris, ALICE, 10);
        assertEquals(Status.COMPLETED, current(paris).status());
        clock.addAndGet(H);
        manager.tick(List.of(paris), false);
        assertEquals(q.id(), current(paris).id(), "still shown until ends_at");
        clock.set(q.endsAt());
        manager.tick(List.of(paris), false);
        assertFalse(current(paris).id() == q.id());
        assertEquals(q.endsAt(), current(paris).startsAt());
    }

    @Test
    void drawNextOnCompletion() throws Exception {
        manager = manager(new QuestOptions(false, true, true, -1, 6));
        manager.start(paris);
        long first = current(paris).id();
        clock.addAndGet(2 * H);
        manager.deposit(paris, ALICE, 10);
        assertFalse(current(paris).id() == first, "next quest drawn right away");
        assertEquals(clock.get(), current(paris).startsAt());
    }

    // ------------------------------------------------------------------
    // Deposits and reward
    // ------------------------------------------------------------------

    @Test
    void rewardIsPaidOnceToTheTreasuryAndIsNotAContribution() throws Exception {
        manager.start(paris);
        assertEquals(4, manager.deposit(paris, ALICE, 4).accepted());
        var last = manager.deposit(paris, BOB, 6);
        assertTrue(last.completed());
        assertEquals(100, balance(paris), "reward of the quest");
        assertEquals(0, manager.deposit(paris, ALICE, 1).accepted(), "completed: nothing more is accepted");
        assertEquals(100, balance(paris), "paid once");
        assertEquals(1, scalar("SELECT COUNT(*) FROM city_quest_reward"), "journal of the reward");
        assertEquals(0, scalar("SELECT COUNT(*) FROM city_contribution"), "not in the treasury contributions");
        assertEquals(0, scalar("SELECT COUNT(*) FROM city_quest_progress"), "progress row removed at completion");
    }

    @Test
    void neverBeyondTheTarget() throws Exception {
        manager.start(paris);
        manager.deposit(paris, ALICE, 8);
        assertEquals(0, manager.deposit(paris, BOB, 3).accepted(), "3 > 2 remaining: refused, nothing consumed");
        // two quick clicks both planned on the same stale remaining (2): the second one is refused
        assertEquals(2, manager.deposit(paris, ALICE, 2).accepted());
        assertEquals(0, manager.deposit(paris, BOB, 2).accepted());
        assertEquals(10, scalar("SELECT progress FROM city_quest"));
        assertEquals(100, balance(paris));
    }

    @Test
    void eachCityHasItsOwnQuestAndProgress() throws Exception {
        manager.start(paris);
        manager.start(lyon);
        manager.deposit(paris, ALICE, 3);
        manager.deposit(lyon, ALICE, 5);
        assertEquals(3, current(paris).progress());
        assertEquals(5, current(lyon).progress(), "a member of two cities contributes to each one separately");
    }

    // ------------------------------------------------------------------
    // Progress kept between passages
    // ------------------------------------------------------------------

    @Test
    void progressKeptAfterExpiryAndResumedOnTheSameAttempt() throws Exception {
        manager.start(paris);
        CityQuest first = current(paris);
        manager.deposit(paris, ALICE, 4);
        clock.set(first.endsAt());
        manager.tick(List.of(paris), false);   // only quest: drawn again
        CityQuest second = current(paris);
        assertEquals(4, second.progress(), "resumed 4/10");
        assertEquals(2, second.passage());
        assertEquals(first.attemptId(), second.attemptId());
        assertTrue(second.resumed());
        manager.deposit(paris, BOB, 2);
        List<Contributor> ranking = manager.ranking(second);
        assertEquals(List.of(new Contributor(ALICE, 4), new Contributor(BOB, 2)), ranking, "added up over the attempt");
        manager.deposit(paris, BOB, 4);
        assertEquals(Status.COMPLETED, current(paris).status());
        clock.set(second.endsAt());
        manager.tick(List.of(paris), false);
        CityQuest third = current(paris);
        assertEquals(0, third.progress(), "a completed quest starts again from zero");
        assertEquals(1, third.passage());
        assertFalse(third.attemptId() == first.attemptId(), "new attempt");
    }

    @Test
    void expiredQuestWithoutContributionLeavesNoHistory() throws Exception {
        manager.start(paris);
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals(0, manager.historySize(paris));
        assertEquals(1, scalar("SELECT COUNT(*) FROM city_quest"), "only the new quest");
        assertEquals(0, current(paris).progress());
        assertEquals(1, current(paris).passage());
    }

    @Test
    void changedItemResetsTheSavedProgress() throws Exception {
        manager.start(paris);
        manager.deposit(paris, ALICE, 6);
        manager.setCatalog(single(10, "RABBIT_FOOT"));
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals(0, current(paris).progress());
        assertEquals(1, current(paris).passage());
        assertEquals(Set.of("RABBIT_FOOT"), current(paris).materials());
    }

    @Test
    void amountLoweredOrRaisedBetweenPassages() throws Exception {
        manager.start(paris);
        manager.deposit(paris, ALICE, 8);
        manager.setCatalog(single(5, "SPONGE"));
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals(4, current(paris).progress(), "clamped to target - 1");
        assertEquals(5, current(paris).target());
        manager.setCatalog(single(50, "SPONGE"));
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals(4, current(paris).progress(), "raised amount: progress kept");
        assertEquals(50, current(paris).target());
    }

    @Test
    void disabledQuestKeepsItsDormantProgressUntilReactivated() throws Exception {
        QuestDefinition sponge = QuestRulesTest.quest("sponge", "EASY", 10, 1, "SPONGE");
        QuestDefinition foot = QuestRulesTest.quest("foot", "EASY", 10, 1, "RABBIT_FOOT");
        QuestDefinition spongeOff = new QuestDefinition("sponge", "sponge", "EASY", sponge.materials(), null, 10, false,
                100, 24, 1, null);
        manager.start(paris);
        manager.deposit(paris, ALICE, 6);
        manager.setCatalog(catalog(spongeOff, foot));
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals("foot", current(paris).questId(), "disabled quest never drawn");
        assertEquals(1, scalar("SELECT COUNT(*) FROM city_quest_progress WHERE quest_id = 'sponge'"), "dormant");
        manager.setCatalog(catalog(sponge));
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals("sponge", current(paris).questId());
        assertEquals(6, current(paris).progress(), "resumed after reactivation");
    }

    @Test
    void questsAlreadyDrawnKeepTheirFrozenValues() throws Exception {
        manager.start(paris);
        manager.setCatalog(catalog(new QuestDefinition("sponge", "Autre nom", "EASY", new java.util.TreeSet<>(Set.of("SPONGE")),
                null, 99, true, 5000, 2, 1, null)));
        CityQuestManager reloaded = new CityQuestManager(db, QuestOptions.DEFAULTS, clock::get, new Random(1), recorder);
        reloaded.load();
        CityQuest q = reloaded.current(paris).orElseThrow();
        assertEquals("sponge", q.name());
        assertEquals(10, q.target());
        assertEquals(100, q.reward());
        assertEquals(clock.get() + 24 * H, q.endsAt());
    }

    // ------------------------------------------------------------------
    // History, purge, dissolution
    // ------------------------------------------------------------------

    @Test
    void historyTotalsAndRetention() throws Exception {
        manager = manager(new QuestOptions(false, true, false, 30, 6));
        manager.start(paris);
        manager.deposit(paris, ALICE, 10);                       // completed
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        manager.deposit(paris, BOB, 3);                          // expired with a contribution
        clock.set(current(paris).endsAt());
        manager.tick(List.of(paris), false);
        assertEquals(2, manager.historySize(paris));
        var totals = manager.totals(paris);
        assertEquals(1, totals.completed());
        assertEquals(1, totals.expired());
        assertEquals(13, totals.items());
        assertEquals(Map.of(ALICE, 10L, BOB, 3L), totals.byPlayer());
        assertEquals(Status.EXPIRED, manager.history(paris, 0, 10).getFirst().status(), "latest first");
        assertEquals(0, manager.purge(), "younger than 30 days");
        clock.addAndGet(31 * 24 * H);
        assertEquals(2, manager.purge());
        assertEquals(0, manager.historySize(paris));
    }

    @Test
    void dissolutionDeletesTheQuestsOfTheCity() throws Exception {
        manager.start(paris);
        manager.deposit(paris, ALICE, 3);
        manager.start(lyon);
        try (Statement st = db.connection().createStatement()) {
            st.execute("DELETE FROM city WHERE id = " + paris);
        }
        manager.forget(paris);
        assertEquals(0, scalar("SELECT COUNT(*) FROM city_quest WHERE city_id = " + paris));
        assertEquals(0, scalar("SELECT COUNT(*) FROM city_quest_progress WHERE city_id = " + paris));
        assertEquals(0, scalar("SELECT COUNT(*) FROM city_quest_contribution"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM city_quest WHERE city_id = " + lyon), "other cities untouched");
        assertTrue(manager.current(paris).isEmpty());
    }

    @Test
    void repeatCompletedFalseCyclesThroughEveryQuest() throws Exception {
        manager = manager(new QuestOptions(false, false, true, -1, 6));
        manager.setCatalog(catalog(QuestRulesTest.quest("a", "EASY", 1, 1, "SPONGE"),
                QuestRulesTest.quest("b", "EASY", 1, 1, "RABBIT_FOOT"), QuestRulesTest.quest("c", "EASY", 1, 1, "GLOW_INK_SAC")));
        manager.start(paris);
        Set<String> firstCycle = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            firstCycle.add(current(paris).questId());
            clock.addAndGet(1000);
            manager.deposit(paris, ALICE, 1);                    // completed, next drawn at once
        }
        assertEquals(Set.of("a", "b", "c"), firstCycle, "each quest once before any repeat");
    }
}
