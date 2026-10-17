package fr.vanillaeconomy.market;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Promotion planning (pure, unit-tested).
 *
 * <p>Rotations are aligned on the clock: with 2 h rotations, a day has 12 slots starting
 * at 00:00, 02:00, ... 22:00 (server time zone). Each day, {@code promosPerDay} distinct
 * slots are drawn at random, each with a random discount between {@code minPercent} and
 * {@code maxPercent}. Plans are generated in advance (today and tomorrow) so the next
 * promotion is known before it starts.
 */
public final class PromotionManager {

    private final int slotsPerDay;
    private final int promosPerDay;
    private final int minPercent;
    private final int maxPercent;

    public PromotionManager(int slotsPerDay, int promosPerDay, int minPercent, int maxPercent) {
        if (slotsPerDay < 1 || promosPerDay < 0 || promosPerDay > slotsPerDay
                || minPercent < 1 || maxPercent > 99 || minPercent > maxPercent) {
            throw new IllegalArgumentException("invalid promotion parameters");
        }
        this.slotsPerDay = slotsPerDay;
        this.promosPerDay = promosPerDay;
        this.minPercent = minPercent;
        this.maxPercent = maxPercent;
    }

    /** Draws a day plan: slot index -> discount percent, {@code promosPerDay} distinct slots. */
    public Map<Integer, Integer> planDay(Random random) {
        return planDay(random, Set.of());
    }

    /**
     * Same, among the slots not in {@code taken} (the global promotions of the day, for the
     * extra promotions of a city): never two promotions on one slot. Fewer slots are drawn
     * when not enough are free.
     */
    public Map<Integer, Integer> planDay(Random random, Set<Integer> taken) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < slotsPerDay; i++) {
            if (!taken.contains(i)) {
                slots.add(i);
            }
        }
        Collections.shuffle(slots, random);
        Map<Integer, Integer> plan = new TreeMap<>();
        for (int i = 0; i < promosPerDay && i < slots.size(); i++) {
            plan.put(slots.get(i), minPercent + random.nextInt(maxPercent - minPercent + 1));
        }
        return plan;
    }

    // ------------------------------------------------------------------
    // Promotion applicable to one villager
    // ------------------------------------------------------------------

    /** Discount shown by a villager; {@code city} = it comes from a Promotion en folie plan. */
    public record Applicable(int percent, boolean city) {
        public static final Applicable NONE = new Applicable(0, false);
    }

    /**
     * Promotion of a villager: the global one, or the one of the city it stands in when that
     * city has Promotion en folie ({@code cityPercent} is then its discount of the current
     * slot, 0 if none). City slots never overlap global ones; if an admin forces a global
     * promotion on a city slot, the global one wins.
     */
    public static Applicable applicable(int globalPercent, boolean inCityWithExtension, int cityPercent) {
        if (globalPercent > 0) {
            return new Applicable(globalPercent, false);
        }
        if (inCityWithExtension && cityPercent > 0) {
            return new Applicable(cityPercent, true);
        }
        return Applicable.NONE;
    }

    public int randomPercent(Random random) {
        return minPercent + random.nextInt(maxPercent - minPercent + 1);
    }

    // ------------------------------------------------------------------
    // Clock-aligned slots
    // ------------------------------------------------------------------

    /** Start (epoch millis) of the slot containing {@code nowMillis}. */
    public static long slotStart(long nowMillis, ZoneId zone, long intervalMillis) {
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        long midnight = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
        return midnight + ((nowMillis - midnight) / intervalMillis) * intervalMillis;
    }

    /** Index of the slot starting at {@code slotStartMillis} within its day. */
    public static int slotIndex(long slotStartMillis, ZoneId zone, long intervalMillis) {
        LocalDate day = Instant.ofEpochMilli(slotStartMillis).atZone(zone).toLocalDate();
        long midnight = day.atStartOfDay(zone).toInstant().toEpochMilli();
        return (int) ((slotStartMillis - midnight) / intervalMillis);
    }

    /** Start (epoch millis) of slot {@code index} of {@code day}. */
    public static long slotStartOf(LocalDate day, int index, ZoneId zone, long intervalMillis) {
        return day.atStartOfDay(zone).toInstant().toEpochMilli() + index * intervalMillis;
    }

    public static LocalDate dayOf(long millis, ZoneId zone) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    // ------------------------------------------------------------------
    // Plan persistence format: "3:27,9:12" (slot:percent)
    // ------------------------------------------------------------------

    public static String encode(Map<Integer, Integer> plan) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(plan).forEach((slot, pct) -> sb.append(sb.isEmpty() ? "" : ",").append(slot).append(':').append(pct));
        return sb.toString();
    }

    public static Map<Integer, Integer> decode(String raw) {
        Map<Integer, Integer> plan = new TreeMap<>();
        if (raw == null || raw.isBlank()) {
            return plan;
        }
        for (String entry : raw.split(",")) {
            String[] parts = entry.split(":");
            if (parts.length == 2) {
                try {
                    plan.put(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // corrupted entry: skipped
                }
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------
    // Promotional quantities
    // ------------------------------------------------------------------

    /**
     * SELL interface promotion: the reference lot shrinks, so the villager pays the base
     * price for fewer units. Only for items whose base_number is greater than 1. The lot is
     * kept fractional (no rounding) so that the real discount is exactly {@code percent};
     * it never goes below 1.
     */
    public static double effectiveBaseNumber(int baseNumber, int percent) {
        if (baseNumber <= 1 || percent <= 0) {
            return baseNumber;
        }
        return Math.max(1, baseNumber * (1 - percent / 100.0));
    }
}
