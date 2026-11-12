package fr.vanillaeconomy.city.quest;

import java.util.List;
import java.util.Map;

/**
 * Content of city_quests.yml after validation: valid difficulties and quests (disabled
 * ones included, they keep their dormant progress), and one warning per ignored entry.
 */
public record QuestCatalog(Map<String, QuestDifficulty> difficulties, Map<String, QuestDefinition> quests,
                           List<String> warnings) {

    public static final QuestCatalog EMPTY = new QuestCatalog(Map.of(), Map.of(), List.of());

    /** Quests that can be drawn: enabled, weight > 0, in a difficulty of weight > 0. */
    public List<QuestDefinition> drawable() {
        return quests.values().stream()
                .filter(q -> q.enabled() && q.weight() > 0)
                .filter(q -> difficulties.containsKey(q.difficultyId()) && difficulties.get(q.difficultyId()).weight() > 0)
                .toList();
    }
}
