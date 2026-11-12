package fr.vanillaeconomy.city.quest;

/**
 * Options of the city quests (cities.yml, section quests).
 *
 * @param historyRetentionDays days an archived quest is kept, -1 = forever
 * @param reminderHours        reminder before the end, capped at half the duration of the quest
 */
public record QuestOptions(boolean allowMarketItems, boolean repeatCompleted, boolean drawNextOnCompletion,
                           int historyRetentionDays, long reminderHours) {

    public static final QuestOptions DEFAULTS = new QuestOptions(false, true, false, -1, 6);
}
