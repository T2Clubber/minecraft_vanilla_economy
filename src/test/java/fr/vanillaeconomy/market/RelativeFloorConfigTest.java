package fr.vanillaeconomy.market;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelativeFloorConfigTest {

    private static final Set<String> CATEGORIES = Set.of("minerals", "food_nature", "hostile_mob", "blocks", "other");
    private static final Logger LOG = Logger.getLogger("test");

    private static YamlConfiguration server(String yaml) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(
                RelativeFloorConfigTest.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8)));
        return cfg;
    }

    @Test
    void serverConfigWithoutTheSectionUsesTheBundledDefaults() {
        // config.yml of a server installed before the setting existed
        var floors = MarketManager.relativeFloors(server("market:\n  markup: 1.4\n  circulation_decay: 0.06\n"), CATEGORIES, LOG);
        assertEquals(Map.of("minerals", 0.2, "food_nature", 0.2, "hostile_mob", 0.2, "blocks", 0.2, "other", 0.2), floors,
                "\"*\" covers every category of items.yml");
    }

    @Test
    void serverValuesOverrideTheDefaults() {
        var floors = MarketManager.relativeFloors(server(
                "market:\n  relative_floor:\n    percent: 35\n    categories: [minerals, unknown]\n"), CATEGORIES, LOG);
        assertEquals(Map.of("minerals", 0.35), floors);
        assertEquals(Map.of("minerals", 0.0, "food_nature", 0.0, "hostile_mob", 0.0, "blocks", 0.0, "other", 0.0),
                MarketManager.relativeFloors(server("market:\n  relative_floor:\n    percent: 0\n"), CATEGORIES, LOG),
                "0 % disables the floor");
        // a server that listed its categories keeps its own list
        assertEquals(Map.of("minerals", 0.2, "other", 0.2), MarketManager.relativeFloors(server(
                "market:\n  relative_floor:\n    percent: 20\n    categories: [minerals, other]\n"), CATEGORIES, LOG));
    }
}
