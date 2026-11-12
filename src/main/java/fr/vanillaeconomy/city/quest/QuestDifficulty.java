package fr.vanillaeconomy.city.quest;

/**
 * A difficulty of city_quests.yml: display name and colour, drawing weight (0 = never
 * drawn) and default duration of its quests. The reward is set by each quest, never by its
 * difficulty.
 */
public record QuestDifficulty(String id, String name, String color, int weight, long durationHours) {
}
