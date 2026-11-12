package fr.vanillaeconomy.city.quest;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Reads the difficulties and quests of city_quests.yml. Every entry is validated on its
 * own: an invalid one is ignored with a clear warning, the others are kept (never an
 * exception). Pure apart from {@link ItemResolver}, unit-tested.
 */
public final class QuestDefinitionLoader {

    private QuestDefinitionLoader() {
    }

    /**
     * @param marketItems  Material names of the market pool (items.yml): refused as quest items
     * @param coinMaterial Material of the plugin's coin, always refused
     */
    public static QuestCatalog load(ConfigurationSection root, ItemResolver items, Set<String> marketItems,
                                    String coinMaterial, boolean allowMarketItems) {
        List<String> warnings = new ArrayList<>();
        Map<String, QuestDifficulty> difficulties = difficulties(root.getConfigurationSection("difficulties"), warnings);
        Map<String, QuestDefinition> quests = new LinkedHashMap<>();
        ConfigurationSection section = root.getConfigurationSection("quests");
        if (section == null) {
            warnings.add("section « quests » absente : aucune quête");
        } else {
            for (String key : section.getKeys(false)) {
                String id = key.toLowerCase(Locale.ROOT);
                if (quests.containsKey(id)) {
                    warnings.add("quête « " + key + " » ignorée : id dupliqué");
                    continue;
                }
                ConfigurationSection q = section.getConfigurationSection(key);
                if (q == null) {
                    warnings.add("quête « " + key + " » ignorée : ce n'est pas une section");
                    continue;
                }
                Parsed parsed = quest(id, q, difficulties, items, marketItems, coinMaterial, allowMarketItems);
                if (parsed.quest() != null) {
                    quests.put(id, parsed.quest());
                } else {
                    warnings.add("quête « " + key + " » ignorée : " + parsed.error());
                }
            }
        }
        for (QuestDifficulty d : difficulties.values()) {
            if (d.weight() > 0 && quests.values().stream().noneMatch(q -> q.enabled() && q.difficultyId().equals(d.id()))) {
                warnings.add("difficulté « " + d.id() + " » sans quête valide : ignorée du tirage");
            }
        }
        return new QuestCatalog(Map.copyOf(difficulties), java.util.Collections.unmodifiableMap(quests), List.copyOf(warnings));
    }

    /** A quest read from the file, or why it is ignored. */
    private record Parsed(QuestDefinition quest, String error) {
    }

    private static Map<String, QuestDifficulty> difficulties(ConfigurationSection section, List<String> warnings) {
        Map<String, QuestDifficulty> result = new LinkedHashMap<>();
        if (section == null) {
            warnings.add("section « difficulties » absente : aucune difficulté");
            return result;
        }
        for (String key : section.getKeys(false)) {
            String id = key.toUpperCase(Locale.ROOT);
            ConfigurationSection d = section.getConfigurationSection(key);
            if (d == null) {
                warnings.add("difficulté « " + key + " » ignorée : ce n'est pas une section");
                continue;
            }
            if (result.containsKey(id)) {
                warnings.add("difficulté « " + key + " » ignorée : id dupliqué");
                continue;
            }
            int weight = d.getInt("weight", 1);
            long hours = d.getLong("duration_hours", 0);
            if (weight < 0) {
                warnings.add("difficulté « " + key + " » ignorée : weight négatif");
                continue;
            }
            if (hours <= 0) {
                warnings.add("difficulté « " + key + " » ignorée : duration_hours doit être > 0");
                continue;
            }
            String color = d.getString("color", "WHITE").toLowerCase(Locale.ROOT);
            if (NamedTextColor.NAMES.value(color) == null) {
                warnings.add("difficulté « " + key + " » : couleur inconnue « " + d.getString("color") + " », WHITE utilisée");
                color = "white";
            }
            if (d.contains("default_reward")) {
                warnings.add("difficulté « " + key + " » : default_reward n'est plus utilisé, la récompense se règle "
                        + "dans chaque quête (reward)");
            }
            result.put(id, new QuestDifficulty(id, d.getString("name", key), color, weight, hours));
        }
        return result;
    }

    private static Parsed quest(String id, ConfigurationSection q, Map<String, QuestDifficulty> difficulties,
                                                   ItemResolver items, Set<String> marketItems, String coinMaterial,
                                                   boolean allowMarketItems) {
        String difficultyId = q.getString("difficulty", "").toUpperCase(Locale.ROOT);
        QuestDifficulty difficulty = difficulties.get(difficultyId);
        if (difficulty == null) {
            return fail("difficulté inconnue « " + q.getString("difficulty") + " »");
        }
        long amount = q.getLong("amount", 0);
        if (amount <= 0) {
            return fail("amount doit être > 0");
        }
        // the reward belongs to the quest (rarity of its item), never to its difficulty
        if (!q.isInt("reward") && !q.isLong("reward")) {
            return fail("reward manquant ou invalide (récompense en pièces, obligatoire pour chaque quête)");
        }
        long reward = q.getLong("reward");
        if (reward < 0) {
            return fail("reward négatif");
        }
        long hours = q.getLong("duration_hours", difficulty.durationHours());
        if (hours <= 0) {
            return fail("duration_hours doit être > 0");
        }
        int weight = q.getInt("weight", 1);
        if (weight < 0) {
            return fail("weight négatif");
        }
        int forms = (q.isSet("material") ? 1 : 0) + (q.isSet("materials") ? 1 : 0) + (q.isSet("tag") ? 1 : 0);
        if (forms != 1) {
            return fail("indiquer exactement un de material, materials ou tag");
        }
        SortedSet<String> materials = new TreeSet<>();
        String tag = null;
        if (q.isSet("tag")) {
            tag = q.getString("tag", "").toLowerCase(Locale.ROOT);
            Optional<Set<String>> content = items.tag(tag);
            if (content.isEmpty() || content.get().isEmpty()) {
                return fail("tag d'items inconnu « " + q.getString("tag") + " »");
            }
            materials.addAll(content.get());
        } else {
            List<String> names = q.isSet("material") ? List.of(q.getString("material", "")) : q.getStringList("materials");
            if (names.isEmpty()) {
                return fail("materials vide");
            }
            for (String name : names) {
                Optional<String> material = items.material(name);
                if (material.isEmpty()) {
                    return fail("Material inconnu « " + name + " » dans cette version du serveur");
                }
                materials.add(material.get());
            }
        }
        if (materials.contains(coinMaterial)) {
            return fail("l'item de la pièce du plugin (" + coinMaterial + ") ne peut pas être demandé");
        }
        if (!allowMarketItems) {
            List<String> market = materials.stream().filter(marketItems::contains).toList();
            if (!market.isEmpty()) {
                return fail("item du marché des idiots (" + String.join(", ", market)
                        + ") : arbitrage possible, voir quests.allow-market-items");
            }
        }
        String hint = q.getString("hint", null);
        if (hint == null && (tag != null || materials.size() > 1)) {
            hint = tag != null ? "N'importe quel item du tag " + tag : "N'importe lequel : " + String.join(", ", materials);
        }
        return new Parsed(new QuestDefinition(id, q.getString("name", id), difficultyId, materials, tag, amount,
                q.getBoolean("enabled", true), reward, hours, weight, hint), null);
    }

    private static Parsed fail(String error) {
        return new Parsed(null, error);
    }
}
