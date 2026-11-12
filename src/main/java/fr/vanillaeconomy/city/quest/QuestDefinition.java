package fr.vanillaeconomy.city.quest;

import java.util.SortedSet;

/**
 * A quest of city_quests.yml (valid, possibly disabled). {@code materials}: Material names
 * accepted (for a tag quest, the content of the tag when the file was loaded); {@code tag}:
 * the item tag key, null for material quests. {@code hint}: text shown for multi-item
 * quests ("N'importe quel tesson de poterie"), null when the quest asks for one item.
 */
public record QuestDefinition(String id, String name, String difficultyId, SortedSet<String> materials, String tag,
                              long amount, boolean enabled, long reward, long durationHours, int weight, String hint) {

    /**
     * Identity of what the quest asks for: the tag, or the sorted list of materials. Saved
     * progress is only resumed when it matches (an edited item resets it).
     */
    public String signature() {
        return signatureOf(tag, materials);
    }

    public static String signatureOf(String tag, java.util.Collection<String> materials) {
        return tag != null ? "tag:" + tag : "materials:" + String.join(",", new java.util.TreeSet<>(materials));
    }
}
