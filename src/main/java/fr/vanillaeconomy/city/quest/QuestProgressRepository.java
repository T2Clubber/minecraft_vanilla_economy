package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.quest.CityQuest.Status;
import fr.vanillaeconomy.city.quest.QuestRules.SavedProgress;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * SQL of the city quests (tables city_quest, city_quest_contribution, city_quest_progress,
 * city_quest_reward). Writes take the connection of the caller's transaction.
 */
public final class QuestProgressRepository {

    /** A player's total in a ranking. */
    public record Contributor(UUID player, long amount) {
    }

    private static final String COLUMNS = "id, city_id, quest_id, attempt_id, passage_number, status, target, progress, "
            + "start_progress, name, materials, tag, hint, signature, reward, difficulty_id, difficulty_name, "
            + "difficulty_color, duration_hours, starts_at, ends_at, completed_at, finished_at";

    // ------------------------------------------------------------------
    // Instances
    // ------------------------------------------------------------------

    /** Current instance of every city: active, or completed and waiting for its end. */
    public List<CityQuest> loadCurrent(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + COLUMNS + " FROM city_quest WHERE finished_at IS NULL ORDER BY id")) {
            List<CityQuest> list = new ArrayList<>();
            while (rs.next()) {
                list.add(read(rs));
            }
            return list;
        }
    }

    /** Inserts a drawn quest; a new attempt is identified by the id of its first passage. */
    public CityQuest insert(Connection c, int cityId, QuestDefinition quest, QuestDifficulty difficulty, Long attemptId,
                            int passage, long startProgress, long startsAt) throws SQLException {
        long endsAt = startsAt + quest.durationHours() * 3_600_000L;
        long id;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO city_quest(city_id, quest_id, attempt_id, passage_number, status, target, progress, start_progress,
                    name, materials, tag, hint, signature, reward, difficulty_id, difficulty_name, difficulty_color,
                    duration_hours, starts_at, ends_at)
                VALUES(?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, cityId);
            ps.setString(2, quest.id());
            if (attemptId == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setLong(3, attemptId);
            }
            ps.setInt(4, passage);
            ps.setLong(5, quest.amount());
            ps.setLong(6, startProgress);
            ps.setLong(7, startProgress);
            ps.setString(8, quest.name());
            ps.setString(9, String.join(",", quest.materials()));
            ps.setString(10, quest.tag());
            ps.setString(11, quest.hint());
            ps.setString(12, quest.signature());
            ps.setLong(13, quest.reward());
            ps.setString(14, difficulty.id());
            ps.setString(15, difficulty.name());
            ps.setString(16, difficulty.color());
            ps.setLong(17, quest.durationHours());
            ps.setLong(18, startsAt);
            ps.setLong(19, endsAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
        }
        long attempt = attemptId == null ? id : attemptId;
        if (attemptId == null) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE city_quest SET attempt_id = ? WHERE id = ?")) {
                ps.setLong(1, attempt);
                ps.setLong(2, id);
                ps.executeUpdate();
            }
        }
        return new CityQuest(id, cityId, quest.id(), attempt, passage, Status.ACTIVE, quest.amount(), startProgress,
                startProgress, quest.name(), new TreeSet<>(quest.materials()), quest.tag(), quest.hint(), quest.signature(),
                quest.reward(), difficulty.id(), difficulty.name(), difficulty.color(), quest.durationHours(), startsAt,
                endsAt, null, null);
    }

    /** Adds to the progress of an active instance, never beyond its target; false if refused. */
    public boolean addProgress(Connection c, long id, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE city_quest SET progress = progress + ? WHERE id = ? AND status = 'ACTIVE' AND progress + ? <= target")) {
            ps.setLong(1, amount);
            ps.setLong(2, id);
            ps.setLong(3, amount);
            return ps.executeUpdate() == 1;
        }
    }

    /** ACTIVE -> COMPLETED, only once (idempotent): true for the call that completed it. */
    public boolean markCompleted(Connection c, long id, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE city_quest SET status = 'COMPLETED', completed_at = ?, reward_paid_at = ?
                WHERE id = ? AND status = 'ACTIVE' AND progress >= target""")) {
            ps.setLong(1, now);
            ps.setLong(2, now);
            ps.setLong(3, id);
            return ps.executeUpdate() == 1;
        }
    }

    public void markExpired(Connection c, long id, long finishedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE city_quest SET status = 'EXPIRED', finished_at = ? WHERE id = ? AND status = 'ACTIVE'")) {
            ps.setLong(1, finishedAt);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void markFinished(Connection c, long id, long finishedAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE city_quest SET finished_at = ? WHERE id = ? AND finished_at IS NULL")) {
            ps.setLong(1, finishedAt);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    public void delete(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM city_quest WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Quest of the latest passage of a city (never drawn twice in a row), null if none. */
    public String lastQuestId(Connection c, int cityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT quest_id FROM city_quest WHERE city_id = ? ORDER BY starts_at DESC, id DESC LIMIT 1")) {
            ps.setInt(1, cityId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Quests completed by a city since {@code since} (cycle of repeat-completed: false). */
    public Set<String> completedSince(Connection c, int cityId, long since) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT quest_id FROM city_quest WHERE city_id = ? AND status = 'COMPLETED' AND completed_at >= ?")) {
            ps.setInt(1, cityId);
            ps.setLong(2, since);
            try (ResultSet rs = ps.executeQuery()) {
                Set<String> ids = new HashSet<>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        }
    }

    // ------------------------------------------------------------------
    // Contributions and rewards
    // ------------------------------------------------------------------

    public void addContribution(Connection c, long questId, UUID player, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO city_quest_contribution(city_quest_id, player_uuid, amount) VALUES(?, ?, ?)
                ON CONFLICT(city_quest_id, player_uuid) DO UPDATE SET amount = amount + excluded.amount""")) {
            ps.setLong(1, questId);
            ps.setString(2, player.toString());
            ps.setLong(3, amount);
            ps.executeUpdate();
        }
    }

    public long contributed(Connection c, long questId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(amount), 0) FROM city_quest_contribution WHERE city_quest_id = ?")) {
            ps.setLong(1, questId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The reward goes to the city's treasury; it is not a contribution (not in the treasury ranking). */
    public void payReward(Connection c, CityQuest quest, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE city SET balance = balance + ? WHERE id = ?")) {
            ps.setLong(1, quest.reward());
            ps.setInt(2, quest.cityId());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO city_quest_reward(city_id, city_quest_id, quest_id, amount, paid_at) VALUES(?, ?, ?, ?, ?)")) {
            ps.setInt(1, quest.cityId());
            ps.setLong(2, quest.id());
            ps.setString(3, quest.questId());
            ps.setLong(4, quest.reward());
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    /** Ranking of an attempt: every passage with the same attempt_id added up. */
    public List<Contributor> ranking(Connection c, int cityId, long attemptId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT k.player_uuid, SUM(k.amount) FROM city_quest_contribution k JOIN city_quest q ON q.id = k.city_quest_id
                WHERE q.city_id = ? AND q.attempt_id = ? GROUP BY k.player_uuid ORDER BY 2 DESC, 1""")) {
            ps.setInt(1, cityId);
            ps.setLong(2, attemptId);
            return contributors(ps);
        }
    }

    /** Contributors of one passage, best first. */
    public List<Contributor> contributors(Connection c, long questId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_uuid, amount FROM city_quest_contribution WHERE city_quest_id = ? ORDER BY amount DESC, player_uuid")) {
            ps.setLong(1, questId);
            return contributors(ps);
        }
    }

    private static List<Contributor> contributors(PreparedStatement ps) throws SQLException {
        List<Contributor> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Contributor(UUID.fromString(rs.getString(1)), rs.getLong(2)));
            }
        }
        return list;
    }

    // ------------------------------------------------------------------
    // Progress kept between passages
    // ------------------------------------------------------------------

    public SavedProgress progress(Connection c, int cityId, String questId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT signature, progress, attempt_id, passages FROM city_quest_progress WHERE city_id = ? AND quest_id = ?")) {
            ps.setInt(1, cityId);
            ps.setString(2, questId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new SavedProgress(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)) : null;
            }
        }
    }

    /** Kept progress of the (city, quest) couple of {@code quest}, at {@code progress}. */
    public void saveProgress(Connection c, CityQuest quest, long progress, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO city_quest_progress(city_id, quest_id, signature, progress, attempt_id, passages, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(city_id, quest_id) DO UPDATE SET signature = excluded.signature, progress = excluded.progress,
                    attempt_id = excluded.attempt_id, passages = excluded.passages, updated_at = excluded.updated_at""")) {
            ps.setInt(1, quest.cityId());
            ps.setString(2, quest.questId());
            ps.setString(3, quest.signature());
            ps.setLong(4, progress);
            ps.setLong(5, quest.attemptId());
            ps.setInt(6, quest.passage());
            ps.setLong(7, now);
            ps.executeUpdate();
        }
    }

    public void deleteProgress(Connection c, int cityId, String questId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM city_quest_progress WHERE city_id = ? AND quest_id = ?")) {
            ps.setInt(1, cityId);
            ps.setString(2, questId);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------

    /** Completed instances, the quest still running included as soon as it is completed, latest first. */
    public List<CityQuest> history(Connection c, int cityId, int offset, int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS
                + " FROM city_quest WHERE city_id = ? AND status = 'COMPLETED' ORDER BY completed_at DESC, id DESC LIMIT ? OFFSET ?")) {
            ps.setInt(1, cityId);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            List<CityQuest> list = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(read(rs));
                }
            }
            return list;
        }
    }

    public int historySize(Connection c, int cityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM city_quest WHERE city_id = ? AND status = 'COMPLETED'")) {
            ps.setInt(1, cityId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Ids of the quests the city completed at least once (still in the history). */
    public Set<String> completedQuestIds(Connection c, int cityId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT DISTINCT quest_id FROM city_quest WHERE city_id = ? AND status = 'COMPLETED'")) {
            ps.setInt(1, cityId);
            Set<String> ids = new HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        }
    }

    /** history-retention-days: archived instances finished before {@code before} are deleted. */
    public int purge(Connection c, long before) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM city_quest WHERE finished_at IS NOT NULL AND finished_at < ?")) {
            ps.setLong(1, before);
            return ps.executeUpdate();
        }
    }

    private static CityQuest read(ResultSet rs) throws SQLException {
        String materials = rs.getString(11);
        long completed = rs.getLong(22);
        Long completedAt = rs.wasNull() ? null : completed;
        long finished = rs.getLong(23);
        Long finishedAt = rs.wasNull() ? null : finished;
        return new CityQuest(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getLong(4), rs.getInt(5),
                Status.valueOf(rs.getString(6)), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getString(10),
                new TreeSet<>(materials.isEmpty() ? List.of() : Arrays.asList(materials.split(","))), rs.getString(12),
                rs.getString(13), rs.getString(14), rs.getLong(15), rs.getString(16), rs.getString(17), rs.getString(18),
                rs.getLong(19), rs.getLong(20), rs.getLong(21), completedAt, finishedAt);
    }
}
