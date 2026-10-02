package fr.vanillaeconomy.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Single SQLite connection used from the server main thread only.
 * Every multi-step write goes through {@link #transaction(SqlWork)} so that
 * balance / stock / serial updates are atomic.
 */
public final class Database implements AutoCloseable {

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Connection connection;

    public Database(File file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        createSchema();
    }

    private void createSchema() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS player_balance (
                        uuid       TEXT PRIMARY KEY,
                        name       TEXT,
                        balance    INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0),
                        updated_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS coin_serial (
                        serial     TEXT PRIMARY KEY,
                        issued     INTEGER NOT NULL,
                        redeemed   INTEGER NOT NULL DEFAULT 0,
                        issuer     TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS market_item_state (
                        material    TEXT PRIMARY KEY,
                        circulation REAL NOT NULL DEFAULT 0,
                        stock       INTEGER NOT NULL DEFAULT 0 CHECK (stock >= 0),
                        price_buy   REAL NOT NULL,
                        price_sell  REAL NOT NULL,
                        updated_at  INTEGER NOT NULL
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS market_rotation (
                        group_key TEXT NOT NULL,
                        side      TEXT NOT NULL,
                        slot      INTEGER NOT NULL,
                        material  TEXT,
                        PRIMARY KEY (group_key, side, slot)
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS villager_instance (
                        uuid          TEXT PRIMARY KEY,
                        villager_type TEXT NOT NULL,
                        origin_biome  TEXT NOT NULL,
                        world         TEXT NOT NULL,
                        tagged_at     INTEGER NOT NULL,
                        status        TEXT NOT NULL DEFAULT 'alive',
                        ended_at      INTEGER
                    )""");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )""");
        }
    }

    public Connection connection() {
        return connection;
    }

    /** Runs {@code work} inside a transaction; rolls back on any exception. */
    public <T> T transaction(SqlWork<T> work) throws SQLException {
        boolean previous = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.run(connection);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previous);
        }
    }

    public Optional<String> getMeta(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public static void setMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() throws SQLException {
        if (!connection.isClosed()) {
            connection.close();
        }
    }
}
