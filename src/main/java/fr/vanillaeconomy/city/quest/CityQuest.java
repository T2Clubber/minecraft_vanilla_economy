package fr.vanillaeconomy.city.quest;

import java.util.SortedSet;

/**
 * One passage of a quest in a city (row of city_quest). Everything describing the quest is
 * frozen when it is drawn, so editing city_quests.yml never changes a quest already drawn
 * nor the history. Only progress and status change, through {@link CityQuestManager}.
 */
public final class CityQuest {

    public enum Status { ACTIVE, COMPLETED, EXPIRED }

    final long id;
    final int cityId;
    final String questId;
    final long attemptId;
    final int passage;
    Status status;
    final long target;
    long progress;
    /** Progress resumed from earlier passages of the same attempt. */
    final long startProgress;
    final String name;
    final SortedSet<String> materials;
    final String tag;
    final String hint;
    final String signature;
    final long reward;
    final String difficultyId;
    final String difficultyName;
    final String difficultyColor;
    final long durationHours;
    final long startsAt;
    final long endsAt;
    Long completedAt;
    Long finishedAt;

    CityQuest(long id, int cityId, String questId, long attemptId, int passage, Status status, long target, long progress,
              long startProgress, String name, SortedSet<String> materials, String tag, String hint, String signature,
              long reward, String difficultyId, String difficultyName, String difficultyColor, long durationHours,
              long startsAt, long endsAt, Long completedAt, Long finishedAt) {
        this.id = id;
        this.cityId = cityId;
        this.questId = questId;
        this.attemptId = attemptId;
        this.passage = passage;
        this.status = status;
        this.target = target;
        this.progress = progress;
        this.startProgress = startProgress;
        this.name = name;
        this.materials = materials;
        this.tag = tag;
        this.hint = hint;
        this.signature = signature;
        this.reward = reward;
        this.difficultyId = difficultyId;
        this.difficultyName = difficultyName;
        this.difficultyColor = difficultyColor;
        this.durationHours = durationHours;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.completedAt = completedAt;
        this.finishedAt = finishedAt;
    }

    public long id() { return id; }
    public int cityId() { return cityId; }
    public String questId() { return questId; }
    public long attemptId() { return attemptId; }
    public int passage() { return passage; }
    public Status status() { return status; }
    public long target() { return target; }
    public long progress() { return progress; }
    public long startProgress() { return startProgress; }
    public String name() { return name; }
    public SortedSet<String> materials() { return materials; }
    public String tag() { return tag; }
    public String hint() { return hint; }
    public String signature() { return signature; }
    public long reward() { return reward; }
    public String difficultyId() { return difficultyId; }
    public String difficultyName() { return difficultyName; }
    public String difficultyColor() { return difficultyColor; }
    public long durationHours() { return durationHours; }
    public long startsAt() { return startsAt; }
    public long endsAt() { return endsAt; }
    public Long completedAt() { return completedAt; }
    public Long finishedAt() { return finishedAt; }

    public long remaining() {
        return Math.max(0, target - progress);
    }

    /** Resumed from an earlier passage with progress ("Reprise : x/y"). */
    public boolean resumed() {
        return passage > 1 && startProgress > 0;
    }
}
