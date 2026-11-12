package fr.vanillaeconomy.city.quest;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestDefinitionLoaderTest {

    static final String SHERDS = "minecraft:decorated_pot_sherds";
    static final Set<String> SHERD_ITEMS = Set.of("ANGLER_POTTERY_SHERD", "ARCHER_POTTERY_SHERD", "ARMS_UP_POTTERY_SHERD");

    /** The server's items: every Material name of the API, minus {@code missing}. */
    static ItemResolver resolver(String... missing) {
        Set<String> names = Arrays.stream(Material.values()).map(Material::name)
                .filter(n -> !n.startsWith("LEGACY_")).collect(Collectors.toCollection(HashSet::new));
        Arrays.asList(missing).forEach(names::remove);
        return new ItemResolver() {
            @Override
            public Optional<String> material(String name) {
                String n = name.trim().toUpperCase(java.util.Locale.ROOT);
                return names.contains(n) ? Optional.of(n) : Optional.empty();
            }

            @Override
            public Optional<Set<String>> tag(String key) {
                return key.equals(SHERDS) ? Optional.of(SHERD_ITEMS) : Optional.empty();
            }
        };
    }

    /** Material names of the market pool, from the items.yml shipped with the plugin. */
    static Set<String> marketItems() {
        YamlConfiguration items = YamlConfiguration.loadConfiguration(new InputStreamReader(
                QuestDefinitionLoaderTest.class.getResourceAsStream("/items.yml"), StandardCharsets.UTF_8));
        Set<String> names = new HashSet<>();
        var categories = items.getConfigurationSection("categories");
        for (String category : categories.getKeys(false)) {
            for (Map<?, ?> entry : categories.getMapList(category)) {
                names.add(String.valueOf(entry.get("id")));
            }
        }
        return names;
    }

    static YamlConfiguration shipped() {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                QuestDefinitionLoaderTest.class.getResourceAsStream("/city_quests.yml"), StandardCharsets.UTF_8));
    }

    static YamlConfiguration yaml(String text) {
        return YamlConfiguration.loadConfiguration(new StringReader(text));
    }

    static final String DIFFICULTIES = """
            difficulties:
              EASY:   { name: "Facile", color: GREEN, weight: 40, default_reward: 400, duration_hours: 24 }
              HARD:   { name: "Difficile", color: RED, weight: 20, default_reward: 1500, duration_hours: 48 }
            """;

    static QuestCatalog load(String quests) {
        return QuestDefinitionLoader.load(yaml(DIFFICULTIES + "quests:\n" + quests), resolver(), marketItems(),
                "GOLD_NUGGET", false);
    }

    // ------------------------------------------------------------------
    // The shipped file
    // ------------------------------------------------------------------

    @Test
    void shippedFileLoadsEveryQuestWithoutWarning() {
        QuestCatalog catalog = QuestDefinitionLoader.load(shipped(), resolver(), marketItems(), "GOLD_NUGGET", false);
        assertEquals(List.of(), catalog.warnings());
        assertEquals(32, catalog.quests().size());
        assertEquals(Set.of("EASY", "MEDIUM", "HARD", "EPIC"), catalog.difficulties().keySet());
        assertEquals(Set.of("ARMADILLO_SCUTE"), catalog.quests().get("armadillo_scute").materials(), "écailles de tatou");
        assertEquals("EASY", catalog.quests().get("armadillo_scute").difficultyId());
        assertEquals(SHERD_ITEMS, catalog.quests().get("pottery_sherds").materials());
        assertEquals("N'importe quel tesson de poterie", catalog.quests().get("pottery_sherds").hint());
        assertEquals(30, catalog.quests().get("pottery_sherds_hard").amount());
    }

    @Test
    void noShippedQuestOverlapsTheMarketPool() {
        QuestCatalog catalog = QuestDefinitionLoader.load(shipped(), resolver(), Set.of(), "GOLD_NUGGET", false);
        Set<String> market = marketItems();
        for (QuestDefinition quest : catalog.quests().values()) {
            for (String material : quest.materials()) {
                assertFalse(market.contains(material), quest.id() + " asks for " + material + ", sold by the villagers");
            }
        }
    }

    @Test
    void sulfurBucketMissingFromTheServerIsIgnoredWithAWarning() {
        QuestCatalog catalog = QuestDefinitionLoader.load(shipped(), resolver("SULFUR_CUBE_BUCKET"), marketItems(),
                "GOLD_NUGGET", false);
        assertEquals(31, catalog.quests().size(), "every other quest is kept");
        assertFalse(catalog.quests().containsKey("sulfur_cube_bucket"));
        assertEquals(1, catalog.warnings().size());
        assertTrue(catalog.warnings().getFirst().contains("SULFUR_CUBE_BUCKET"));
    }

    // ------------------------------------------------------------------
    // Validation, entry by entry
    // ------------------------------------------------------------------

    @Test
    void invalidEntriesAreIgnoredAndValidOnesKept() {
        QuestCatalog catalog = load("""
                  ok:            { difficulty: EASY, material: SPONGE, amount: 10 }
                  OK:            { difficulty: EASY, material: RABBIT_FOOT, amount: 10 }
                  unknown_item:  { difficulty: EASY, material: NOT_AN_ITEM, amount: 10 }
                  unknown_tag:   { difficulty: EASY, tag: "minecraft:nope", amount: 10 }
                  zero:          { difficulty: EASY, material: SPONGE, amount: 0 }
                  negative_rew:  { difficulty: EASY, material: SPONGE, amount: 5, reward: -1 }
                  bad_duration:  { difficulty: EASY, material: SPONGE, amount: 5, duration_hours: 0 }
                  no_difficulty: { difficulty: LEGEND, material: SPONGE, amount: 5 }
                  negative_w:    { difficulty: EASY, material: SPONGE, amount: 5, weight: -2 }
                  coin:          { difficulty: EASY, material: GOLD_NUGGET, amount: 5 }
                  two_forms:     { difficulty: EASY, material: SPONGE, tag: "minecraft:decorated_pot_sherds", amount: 5 }
                  market:        { difficulty: EASY, material: DIAMOND, amount: 5 }
                """);
        assertEquals(Set.of("ok"), catalog.quests().keySet());
        List<String> questWarnings = catalog.warnings().stream().filter(w -> w.startsWith("quête")).toList();
        assertEquals(11, questWarnings.size(), String.join("\n", catalog.warnings()));
        String all = String.join("\n", catalog.warnings());
        for (String expected : List.of("id dupliqué", "Material inconnu « NOT_AN_ITEM »", "tag d'items inconnu",
                "amount doit être > 0", "reward négatif", "duration_hours doit être > 0", "difficulté inconnue « LEGEND »",
                "weight négatif", "pièce du plugin", "exactement un de", "item du marché")) {
            assertTrue(all.contains(expected), "missing warning: " + expected);
        }
    }

    @Test
    void marketItemsAcceptedWithAllowMarketItems() {
        QuestCatalog catalog = QuestDefinitionLoader.load(yaml(DIFFICULTIES + """
                quests:
                  diamonds: { difficulty: HARD, material: DIAMOND, amount: 5 }
                """), resolver(), marketItems(), "GOLD_NUGGET", true);
        assertEquals(Set.of("diamonds"), catalog.quests().keySet());
        assertTrue(catalog.warnings().stream().noneMatch(w -> w.startsWith("quête")), String.join("\n", catalog.warnings()));
    }

    @Test
    void multiMaterialQuestAddsUpItsItems() {
        QuestDefinition quest = load("""
                  heads: { difficulty: HARD, materials: [PIGLIN_HEAD, DRAGON_HEAD], amount: 3 }
                """).quests().get("heads");
        assertEquals(Set.of("PIGLIN_HEAD", "DRAGON_HEAD"), quest.materials());
        assertEquals("materials:DRAGON_HEAD,PIGLIN_HEAD", quest.signature(), "normalised list");
        assertTrue(quest.hint().contains("N'importe lequel"));
    }

    // ------------------------------------------------------------------
    // Difficulties
    // ------------------------------------------------------------------

    @Test
    void difficultiesCanBeAddedRemovedAndRenamed() {
        QuestCatalog catalog = QuestDefinitionLoader.load(yaml("""
                difficulties:
                  EASY:   { name: "Tranquille", color: GREEN, weight: 40, default_reward: 400, duration_hours: 24 }
                  LEGEND: { name: "Légendaire", color: GOLD, weight: 5, default_reward: 9000, duration_hours: 72 }
                quests:
                  a: { difficulty: EASY, material: SPONGE, amount: 10 }
                  b: { difficulty: LEGEND, material: DRAGON_HEAD, amount: 1 }
                  c: { difficulty: MEDIUM, material: ECHO_SHARD, amount: 8 }
                """), resolver(), marketItems(), "GOLD_NUGGET", false);
        assertEquals("Tranquille", catalog.difficulties().get("EASY").name(), "renamed");
        assertEquals("gold", catalog.difficulties().get("LEGEND").color(), "added");
        assertEquals(Set.of("a", "b"), catalog.quests().keySet(), "MEDIUM removed: its quest is ignored");
        assertEquals(9000, catalog.quests().get("b").reward());
        assertEquals(72, catalog.quests().get("b").durationHours());
    }

    @Test
    void defaultRewardAndDurationComeFromTheDifficultyUnlessOverridden() {
        QuestCatalog catalog = load("""
                  plain:  { difficulty: HARD, material: HEAVY_CORE, amount: 3 }
                  custom: { difficulty: HARD, material: TOTEM_OF_UNDYING, amount: 4, reward: 2222, duration_hours: 12, weight: 5 }
                """);
        assertEquals(1500, catalog.quests().get("plain").reward());
        assertEquals(48, catalog.quests().get("plain").durationHours());
        assertEquals(1, catalog.quests().get("plain").weight(), "default weight");
        assertEquals(2222, catalog.quests().get("custom").reward());
        assertEquals(12, catalog.quests().get("custom").durationHours());
        assertEquals(5, catalog.quests().get("custom").weight());
    }

    @Test
    void zeroWeightAndEmptyDifficultiesAreLeftOutOfTheDraw() {
        QuestCatalog catalog = QuestDefinitionLoader.load(yaml("""
                difficulties:
                  EASY:  { name: "Facile", color: GREEN, weight: 0, default_reward: 400, duration_hours: 24 }
                  HARD:  { name: "Difficile", color: RED, weight: 20, default_reward: 1500, duration_hours: 48 }
                  EMPTY: { name: "Vide", color: PINK_SPARKLES, weight: 10, default_reward: 1, duration_hours: 1 }
                quests:
                  easy:     { difficulty: EASY, material: SPONGE, amount: 10 }
                  hard:     { difficulty: HARD, material: HEAVY_CORE, amount: 3 }
                  hard_off: { difficulty: HARD, material: TOTEM_OF_UNDYING, amount: 3, weight: 0 }
                  disabled: { difficulty: HARD, material: SNIFFER_EGG, amount: 3, enabled: false }
                """), resolver(), marketItems(), "GOLD_NUGGET", false);
        assertEquals(List.of("hard"), catalog.drawable().stream().map(QuestDefinition::id).toList(),
                "difficulty of weight 0, quest of weight 0 and disabled quest never drawn");
        assertTrue(catalog.quests().containsKey("disabled"), "kept (dormant progress)");
        assertEquals("white", catalog.difficulties().get("EMPTY").color(), "unknown colour falls back on WHITE");
        String all = String.join("\n", catalog.warnings());
        assertTrue(all.contains("couleur inconnue"));
        assertTrue(all.contains("« EMPTY » sans quête valide"));
    }
}
