package fr.vanillaeconomy.city.quest;

/**
 * A difficulty of city_quests.yml: display name and colour, drawing weight (0 = never
 * drawn), default reward and duration of its quests.
 */
public record QuestDifficulty(String id, String name, String color, int weight, long defaultReward, long durationHours) {
}
