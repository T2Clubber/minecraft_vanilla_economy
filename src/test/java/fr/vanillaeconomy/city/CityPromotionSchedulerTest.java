package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityPromotionScheduler.Notice;
import fr.vanillaeconomy.city.CityPromotionScheduler.NoticeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CityPromotionSchedulerTest {

    private static final long H = 3_600_000L;
    private static final long SLOT = 2 * H;
    private static final long WARN = 2 * H;

    /** Slot starts (hours of a day) -> discount. */
    private static NavigableMap<Long, Integer> plan(long... hoursAndPercents) {
        NavigableMap<Long, Integer> starts = new TreeMap<>();
        for (int i = 0; i < hoursAndPercents.length; i += 2) {
            starts.put(hoursAndPercents[i] * H, (int) hoursAndPercents[i + 1]);
        }
        return starts;
    }

    private static List<Notice> during(long fromHour, long toHour, NavigableMap<Long, Integer> starts) {
        return CityPromotionScheduler.notices(fromHour * H, toHour * H, starts, SLOT, WARN, true, true);
    }

    @Test
    void warningTwoHoursBeforeAPromotionSlotOnly() {
        NavigableMap<Long, Integer> starts = plan(10, 30);
        assertEquals(List.of(new Notice(NoticeKind.WARNING, 10 * H, 30)), during(7, 8, starts), "08h: 2 h before 10h");
        assertEquals(List.of(new Notice(NoticeKind.START, 10 * H, 30)), during(9, 10, starts));
        assertTrue(during(10, 12, starts).isEmpty(), "nothing when the next slot is not a promotion");
        assertTrue(during(0, 7, starts).isEmpty());
        // each notice once: consecutive checks never overlap
        assertEquals(1, during(7, 8, starts).size() + CityPromotionScheduler.notices(8 * H, 8 * H + 20_000, starts,
                SLOT, WARN, true, true).size());
    }

    @Test
    void consecutivePromotionsAnnounceTheSecondStartWithoutASecondWarning() {
        NavigableMap<Long, Integer> starts = plan(10, 30, 12, 15);
        assertEquals(List.of(new Notice(NoticeKind.WARNING, 10 * H, 30)), during(7, 8, starts));
        assertEquals(List.of(new Notice(NoticeKind.START, 10 * H, 30)), during(9, 10, starts),
                "10h: start of the first one, no warning for 12h (already in promotion)");
        assertEquals(List.of(new Notice(NoticeKind.START, 12 * H, 15)), during(11, 12, starts));
    }

    @Test
    void noMessageWithoutCityPromotion() {
        // a city without the extension has no plan: nothing is ever due
        assertTrue(during(0, 48, new TreeMap<>()).isEmpty());
        // moments switched off in cities.yml
        NavigableMap<Long, Integer> starts = plan(10, 30);
        assertTrue(CityPromotionScheduler.notices(0, 24 * H, starts, SLOT, WARN, false, false).isEmpty());
        assertEquals(List.of(new Notice(NoticeKind.START, 10 * H, 30)),
                CityPromotionScheduler.notices(0, 24 * H, starts, SLOT, WARN, false, true));
    }

    @Test
    void recipientsAreTheOnlineMembersOfTheCity() {
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        UUID otherCity = UUID.randomUUID();
        Set<UUID> connected = Set.of(online, otherCity);
        assertEquals(List.of(online), CityPromotionScheduler.recipients(List.of(online, offline), connected::contains));
    }

    @Test
    void joinReminderOnlyWhileAPromotionIsRunning() {
        NavigableMap<Long, Integer> starts = plan(10, 30);
        assertTrue(CityPromotionScheduler.active(9 * H, starts, SLOT).isEmpty(), "not started yet");
        assertEquals(30, CityPromotionScheduler.active(11 * H, starts, SLOT).orElseThrow().percent());
        assertEquals(10 * H, CityPromotionScheduler.active(11 * H, starts, SLOT).orElseThrow().slotStart());
        assertTrue(CityPromotionScheduler.active(12 * H, starts, SLOT).isEmpty(), "over at 12h");
        assertTrue(CityPromotionScheduler.active(11 * H, new TreeMap<>(), SLOT).isEmpty());
    }
}
