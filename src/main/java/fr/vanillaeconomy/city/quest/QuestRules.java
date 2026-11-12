package fr.vanillaeconomy.city.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Pure rules of the city quests (unit-tested): drawing, resumed progress, reminder, deposit plan. */
public final class QuestRules {

    private QuestRules() {
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /**
     * Next quest of a city: first a difficulty, weighted by its weight among those having a
     * drawable quest, then a quest of that difficulty weighted by its own weight. Never the
     * previous quest twice in a row unless it is the only one; with {@code repeatCompleted}
     * false, the quests completed in the current cycle are left out while others remain
     * ({@code cycleReset} is then true when every quest was completed and the cycle restarts).
     */
    public static Optional<Draw> draw(QuestCatalog catalog, String previousQuestId, Set<String> completedInCycle,
                                      boolean repeatCompleted, Random random) {
        List<QuestDefinition> candidates = catalog.drawable();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<QuestDefinition> pool = candidates;
        boolean reset = false;
        if (!repeatCompleted) {
            List<QuestDefinition> notDone = candidates.stream().filter(q -> !completedInCycle.contains(q.id())).toList();
            if (notDone.isEmpty()) {
                reset = true;
            } else {
                pool = notDone;
            }
        }
        if (pool.size() > 1 && previousQuestId != null) {
            List<QuestDefinition> others = pool.stream().filter(q -> !q.id().equals(previousQuestId)).toList();
            if (!others.isEmpty()) {
                pool = others;
            }
        }
        Map<String, List<QuestDefinition>> byDifficulty = new LinkedHashMap<>();
        pool.forEach(q -> byDifficulty.computeIfAbsent(q.difficultyId(), k -> new ArrayList<>()).add(q));
        String difficulty = weighted(new ArrayList<>(byDifficulty.keySet()),
                id -> catalog.difficulties().get(id).weight(), random);
        QuestDefinition quest = weighted(byDifficulty.get(difficulty), QuestDefinition::weight, random);
        return Optional.of(new Draw(quest, reset));
    }

    public record Draw(QuestDefinition quest, boolean cycleReset) {
    }

    private static <T> T weighted(List<T> items, java.util.function.ToIntFunction<T> weight, Random random) {
        long total = items.stream().mapToLong(weight::applyAsInt).sum();
        long roll = (long) (random.nextDouble() * total);
        for (T item : items) {
            roll -= weight.applyAsInt(item);
            if (roll < 0) {
                return item;
            }
        }
        return items.getLast();
    }

    // ------------------------------------------------------------------
    // Progress kept between passages
    // ------------------------------------------------------------------

    /** Progress saved for a (city, quest) couple. */
    public record SavedProgress(String signature, long progress, long attemptId, int passages) {
    }

    /**
     * Where a drawn quest starts. {@code attemptId} null = new attempt; {@code discard} = the
     * saved progress no longer applies (the item asked for changed) and must be deleted.
     */
    public record Resume(long progress, Long attemptId, int passage, boolean discard) {
        public boolean resumed() {
            return attemptId != null;
        }
    }

    /**
     * Saved progress, if any, is resumed: reset when the item asked for changed (signature),
     * brought back to target - 1 when the amount was lowered below it (one deposit is still
     * needed), kept as is when the amount was raised.
     */
    public static Resume resume(SavedProgress saved, QuestDefinition quest) {
        if (saved == null) {
            return new Resume(0, null, 1, false);
        }
        if (!saved.signature().equals(quest.signature())) {
            return new Resume(0, null, 1, true);
        }
        long progress = Math.min(saved.progress(), quest.amount() - 1);
        return new Resume(Math.max(0, progress), saved.attemptId(), saved.passages() + 1, false);
    }

    // ------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------

    /** Reminder before the end: {@code hours} before, at most half the duration of the quest. */
    public static long reminderAt(long startsAt, long endsAt, long hours) {
        return endsAt - Math.min(hours * 3_600_000L, (endsAt - startsAt) / 2);
    }

    // ------------------------------------------------------------------
    // Deposit
    // ------------------------------------------------------------------

    /**
     * Why a player may not deposit (message key), empty when he may: he must be a member of
     * the city (any role), the city must have the extension and its quest be active.
     */
    public static Optional<String> depositRefusal(boolean member, boolean extension, CityQuest.Status status) {
        if (!member) {
            return Optional.of("not_member");
        }
        if (!extension) {
            return Optional.of("quest_no_extension");
        }
        if (status == null) {
            return Optional.of("quest_none");
        }
        if (status != CityQuest.Status.ACTIVE) {
            return Optional.of("quest_already_completed");
        }
        return Optional.empty();
    }

    /** One inventory slot as seen by a deposit; {@code pluginTagged}: carries a tag of this plugin (coin). */
    public record Slot(int index, String material, int amount, boolean pluginTagged) {
    }

    /** Amount to take from one slot. */
    public record Take(int index, int amount) {
    }

    /**
     * What a deposit consumes: accepted items only (any of {@code accepted}, added up; never
     * a tagged item of the plugin), at most {@code remaining} (what the quest still needs) and
     * at most {@code requested} (null = everything useful). The surplus stays with the player.
     */
    public static List<Take> planDeposit(Collection<Slot> slots, Set<String> accepted, long remaining, Long requested) {
        long budget = Math.min(remaining, requested == null ? Long.MAX_VALUE : requested);
        List<Take> takes = new ArrayList<>();
        for (Slot slot : slots) {
            if (budget <= 0) {
                break;
            }
            if (slot.pluginTagged() || slot.amount() <= 0 || !accepted.contains(slot.material())) {
                continue;
            }
            int n = (int) Math.min(slot.amount(), budget);
            takes.add(new Take(slot.index(), n));
            budget -= n;
        }
        return takes;
    }

    public static long total(Collection<Take> takes) {
        return takes.stream().mapToLong(Take::amount).sum();
    }

    /** "▮▮▮▮▯▯▯▯▯▯" on {@code width} characters. */
    public static String bar(long progress, long target, int width) {
        int filled = target <= 0 ? width : (int) Math.min(width, progress * width / target);
        return "▮".repeat(filled) + "▯".repeat(width - filled);
    }
}
