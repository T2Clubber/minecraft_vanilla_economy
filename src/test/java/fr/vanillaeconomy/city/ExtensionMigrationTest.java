package fr.vanillaeconomy.city;

import fr.vanillaeconomy.storage.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionMigrationTest {

    @Test
    void teleportBoughtWithAnEarlierVersionSurvivesTheUpgrade(@TempDir Path dir) throws Exception {
        String url = "jdbc:sqlite:" + dir.resolve("economy.db");
        // Database of the version that introduced Téléportation (no center_y, no promotion schedule).
        try (Connection c = DriverManager.getConnection(url); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE city (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        owner_uuid TEXT NOT NULL UNIQUE, world TEXT NOT NULL, center_x INTEGER NOT NULL,
                        center_z INTEGER NOT NULL, tier INTEGER NOT NULL DEFAULT 1,
                        balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0), created_at INTEGER NOT NULL)""");
            st.execute("""
                    CREATE TABLE city_extension (city_id INTEGER NOT NULL REFERENCES city(id) ON DELETE CASCADE,
                        extension TEXT NOT NULL, bought_at INTEGER NOT NULL, PRIMARY KEY (city_id, extension))""");
            st.execute("INSERT INTO city VALUES (1, 'Paris', '00000000-0000-0000-0000-000000000001', 'world', 0, 0, 3, 4200, 0)");
            st.execute("INSERT INTO city VALUES (2, 'Lyon', '00000000-0000-0000-0000-000000000002', 'world', 900, 0, 1, 0, 0)");
            st.execute("INSERT INTO city_extension VALUES (1, 'teleport', 1700000000000)");
        }

        try (Database db = new Database(dir.resolve("economy.db").toFile())) {
            Map<Integer, Set<CityExtension>> loaded = ExtensionManager.loadAll(db.connection());
            assertEquals(Map.of(1, Set.of(CityExtension.TELEPORT)), loaded, "nothing lost, nothing added");
            try (Statement st = db.connection().createStatement()) {
                try (ResultSet rs = st.executeQuery("SELECT bought_at FROM city_extension WHERE city_id = 1")) {
                    assertTrue(rs.next());
                    assertEquals(1700000000000L, rs.getLong(1));
                }
                try (ResultSet rs = st.executeQuery("SELECT balance, tier FROM city WHERE id = 1")) {
                    assertTrue(rs.next());
                    assertEquals(4200, rs.getLong(1));
                    assertEquals(3, rs.getInt(2));
                }
                // new schema pieces are there and usable
                st.execute("INSERT INTO city_extension VALUES (1, 'promo_frenzy', 1)");
                st.execute("INSERT INTO city_promotion_schedule VALUES (1, '2026-10-17', 4, 25)");
                try (ResultSet rs = st.executeQuery("SELECT center_y FROM city WHERE id = 1")) {
                    assertTrue(rs.next());
                    rs.getObject(1);
                    assertTrue(rs.wasNull(), "height unknown for cities founded before it was recorded");
                }
            }
            assertEquals(Map.of(1, Set.of(CityExtension.TELEPORT, CityExtension.PROMO_FRENZY)),
                    ExtensionManager.loadAll(db.connection()));
            // the schedule follows its city
            try (Statement st = db.connection().createStatement()) {
                st.execute("DELETE FROM city WHERE id = 1");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM city_promotion_schedule")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
        // reopening an already migrated database changes nothing
        try (Database db = new Database(dir.resolve("economy.db").toFile())) {
            assertEquals(Map.of(), ExtensionManager.loadAll(db.connection()));
        }
    }
}
