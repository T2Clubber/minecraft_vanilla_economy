package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.quest.CityQuest.Status;
import fr.vanillaeconomy.city.quest.QuestProgressRepository.Contributor;
import fr.vanillaeconomy.city.quest.QuestProgressRepository.Totals;
import fr.vanillaeconomy.city.quest.QuestRules.Resume;
import fr.vanillaeconomy.city.quest.QuestRules.SavedProgress;
import fr.vanillaeconomy.storage.Database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * City quests engine, without Bukkit (tested on a real SQLite database): one quest at a
 * time per city, its own timer, drawing, kept progress, atomic deposits and a reward paid
 * once. The current quest of each city stays in memory; the database is only written by
 * the scheduled check and by deposits, never by event handlers. Main thread only.
 */
public final class CityQuestManager {

    /** What happened, for the chat messages and the staff journal. */
    public interface Events {
        void drawn(CityQuest quest);

        /** The reward is already credited in the database: update the city in memory. */
        void completed(CityQuest quest, List<Contributor> top);

        void expired(CityQuest quest, boolean progressKept);

        /** No drawable quest left in city_quests.yml (logged once per catalog). */
        void nothingToDraw(int cityId);
    }

    /** Result of a deposit; {@code accepted} = 0 when it was refused (nothing may be consumed). */
    public record Deposit(long accepted, CityQuest quest, boolean completed) {
    }

    private static final String META_CYCLE = "quest_cycle:";

    private final Database db;
    private final QuestProgressRepository repo = new QuestProgressRepository();
    private final LongSupplier clock;
    private final Random random;
    private final Events events;
    private QuestOptions options;
    private QuestCatalog catalog = QuestCatalog.EMPTY;
    private final Map<Integer, CityQuest> current = new HashMap<>();
    private boolean warnedEmpty;

    public CityQuestManager(Database db, QuestOptions options, LongSupplier clock, Random random, Events events) {
        this.db = db;
        this.options = options;
        this.clock = clock;
        this.random = random;
        this.events = events;
    }

    public QuestCatalog catalog() {
        return catalog;
    }

    /** New city_quests.yml: future draws only, the quests already drawn keep their frozen values. */
    public void setCatalog(QuestCatalog catalog) {
        this.catalog = catalog;
        this.warnedEmpty = false;
    }

    public QuestOptions options() {
        return options;
    }

    public void load() throws SQLException {
        current.clear();
        for (CityQuest quest : repo.loadCurrent(db.connection())) {
            current.put(quest.cityId(), quest);
        }
    }

    /** Current quest of a city (active, or completed and waiting for its end), if any. */
    public Optional<CityQuest> current(int cityId) {
        return Optional.ofNullable(current.get(cityId));
    }

    public Collection<CityQuest> currentQuests() {
        return List.copyOf(current.values());
    }

    /** Extension bought: the first quest starts right away. */
    public void start(int cityId) throws SQLException {
        if (!current.containsKey(cityId)) {
            draw(cityId, clock.getAsLong(), null);
        }
    }

    /** City disbanded: its rows are deleted by the database (cascade), forget it in memory. */
    public void forget(int cityId) {
        current.remove(cityId);
    }

    // ------------------------------------------------------------------
    // Timer
    // ------------------------------------------------------------------

    /**
     * Scheduled check of the cities with the extension. At the end of a quest, it is closed
     * and the next one drawn, starting at the previous end (no drift). {@code startup}: a
     * quest that ended while the server was off is closed and ONE new quest starts now (no
     * retroactive chain, no reward).
     */
    public void tick(Collection<Integer> citiesWithExtension, boolean startup) throws SQLException {
        long now = clock.getAsLong();
        for (int cityId : citiesWithExtension) {
            CityQuest quest = current.get(cityId);
            if (quest == null) {
                draw(cityId, now, null);
            } else if (now >= quest.endsAt()) {
                close(quest, quest.endsAt());
                draw(cityId, startup ? now : quest.endsAt(), quest.questId());
            }
        }
    }

    /** End of a quest: an active one expires (no reward), a completed one goes to the history. */
    private void close(CityQuest quest, long at) throws SQLException {
        boolean expired = quest.status == Status.ACTIVE;
        boolean[] kept = {false};
        db.transaction(c -> {
            if (expired) {
                if (repo.contributed(c, quest.id()) == 0) {
                    // Nothing given during this passage: not kept in the history.
                    repo.delete(c, quest.id());
                } else {
                    repo.markExpired(c, quest.id(), at);
                }
                kept[0] = repo.progress(c, quest.cityId(), quest.questId()) != null;
            } else {
                repo.markFinished(c, quest.id(), at);
            }
            return null;
        });
        current.remove(quest.cityId());
        if (expired) {
            quest.status = Status.EXPIRED;
            quest.finishedAt = at;
            events.expired(quest, kept[0]);
        } else {
            quest.finishedAt = at;
        }
    }

    /**
     * Draws the next quest of a city, resuming its saved progress if any. {@code previous}:
     * quest that just ended (never drawn twice in a row), null = the latest in the database.
     */
    private void draw(int cityId, long startsAt, String previousQuest) throws SQLException {
        Connection c = db.connection();
        String previous = previousQuest != null ? previousQuest : repo.lastQuestId(c, cityId);
        long cycleStart = db.getMeta(META_CYCLE + cityId).map(Long::parseLong).orElse(0L);
        Set<String> completed = options.repeatCompleted() ? Set.of() : repo.completedSince(c, cityId, cycleStart);
        Optional<QuestRules.Draw> draw = QuestRules.draw(catalog, previous, completed, options.repeatCompleted(), random);
        if (draw.isEmpty()) {
            if (!warnedEmpty) {
                warnedEmpty = true;
                events.nothingToDraw(cityId);
            }
            return;
        }
        QuestDefinition quest = draw.get().quest();
        QuestDifficulty difficulty = catalog.difficulties().get(quest.difficultyId());
        CityQuest drawn = db.transaction(tx -> {
            if (draw.get().cycleReset()) {
                Database.setMeta(tx, META_CYCLE + cityId, Long.toString(clock.getAsLong()));
            }
            SavedProgress saved = repo.progress(tx, cityId, quest.id());
            Resume resume = QuestRules.resume(saved, quest);
            if (resume.discard()) {
                repo.deleteProgress(tx, cityId, quest.id());
            }
            CityQuest q = repo.insert(tx, cityId, quest, difficulty, resume.attemptId(), resume.passage(), resume.progress(),
                    startsAt);
            if (resume.resumed()) {
                // passage number and clamped progress (amount lowered) kept for the next passages
                repo.saveProgress(tx, q, q.progress(), clock.getAsLong());
            }
            return q;
        });
        current.put(cityId, drawn);
        events.drawn(drawn);
    }

    // ------------------------------------------------------------------
    // Deposit and reward
    // ------------------------------------------------------------------

    /**
     * Records {@code amount} items given by a member (already limited to what the quest
     * still needs) in one transaction: progress, contribution, kept progress, and the
     * reward when the target is reached (once). Refused (accepted 0) when the quest is not
     * active or the amount would go beyond the target: the caller then consumes nothing.
     */
    public Deposit deposit(int cityId, UUID player, long amount) throws SQLException {
        CityQuest quest = current.get(cityId);
        if (quest == null || quest.status != Status.ACTIVE || amount <= 0 || amount > quest.remaining()) {
            return new Deposit(0, quest, false);
        }
        long now = clock.getAsLong();
        boolean[] completed = {false};
        boolean ok = db.transaction(c -> {
            if (!repo.addProgress(c, quest.id(), amount)) {
                return false;
            }
            repo.addContribution(c, quest.id(), player, amount);
            long progress = quest.progress + amount;
            if (progress >= quest.target() && repo.markCompleted(c, quest.id(), now)) {
                repo.payReward(c, quest, now);
                repo.deleteProgress(c, cityId, quest.questId());
                completed[0] = true;
            } else {
                repo.saveProgress(c, quest, progress, now);
            }
            return true;
        });
        if (!ok) {
            return new Deposit(0, quest, false);
        }
        quest.progress += amount;
        if (completed[0]) {
            quest.status = Status.COMPLETED;
            quest.completedAt = now;
            events.completed(quest, ranking(quest));
            if (options.drawNextOnCompletion()) {
                close(quest, now);
                draw(cityId, now, quest.questId());
            }
        }
        return new Deposit(amount, quest, completed[0]);
    }

    // ------------------------------------------------------------------
    // Ranking and history
    // ------------------------------------------------------------------

    /** Ranking of the current attempt (every passage of the same attempt added up). */
    public List<Contributor> ranking(CityQuest quest) throws SQLException {
        return repo.ranking(db.connection(), quest.cityId(), quest.attemptId());
    }

    public List<CityQuest> history(int cityId, int page, int pageSize) throws SQLException {
        return repo.history(db.connection(), cityId, page * pageSize, pageSize);
    }

    public int historySize(int cityId) throws SQLException {
        return repo.historySize(db.connection(), cityId);
    }

    public List<Contributor> contributors(CityQuest quest) throws SQLException {
        return repo.contributors(db.connection(), quest.id());
    }

    public Totals totals(int cityId) throws SQLException {
        return repo.totals(db.connection(), cityId);
    }

    /** Daily purge of the archived quests older than history-retention-days (-1 = never). */
    public int purge() throws SQLException {
        if (options.historyRetentionDays() < 0) {
            return 0;
        }
        long before = clock.getAsLong() - options.historyRetentionDays() * 86_400_000L;
        return db.transaction(c -> repo.purge(c, before));
    }
}
