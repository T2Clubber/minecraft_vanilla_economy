package fr.vanillaeconomy.market;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromotionManagerTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final long TWO_HOURS = 2 * 3_600_000L;
    private final PromotionManager promotions = new PromotionManager(12, 2, 5, 50);

    @Test
    void exactlyTwoDistinctSlotsPerDayWithDiscountInRange() {
        Random random = new Random(3);
        boolean[] seenSlots = new boolean[12];
        for (int day = 0; day < 2000; day++) {
            Map<Integer, Integer> plan = promotions.planDay(random);
            assertEquals(2, plan.size());
            plan.forEach((slot, pct) -> {
                assertTrue(slot >= 0 && slot < 12);
                assertTrue(pct >= 5 && pct <= 50, "pct " + pct);
                seenSlots[slot] = true;
            });
        }
        for (boolean seen : seenSlots) {
            assertTrue(seen, "every slot must be reachable (slots differ from day to day)");
        }
    }

    @Test
    void planEncodingRoundTrip() {
        Map<Integer, Integer> plan = Map.of(9, 12, 3, 27);
        assertEquals("3:27,9:12", PromotionManager.encode(plan));
        assertEquals(plan, PromotionManager.decode("3:27,9:12"));
        assertTrue(PromotionManager.decode("").isEmpty());
        assertEquals(Map.of(4, 10), PromotionManager.decode("4:10,abc,7:x"));
    }

    @Test
    void slotsAreAlignedOnTheClock() {
        long t = LocalDateTime.of(2026, 10, 2, 13, 47).atZone(PARIS).toInstant().toEpochMilli();
        long start = PromotionManager.slotStart(t, PARIS, TWO_HOURS);
        assertEquals(LocalDateTime.of(2026, 10, 2, 12, 0).atZone(PARIS).toInstant().toEpochMilli(), start);
        assertEquals(6, PromotionManager.slotIndex(start, PARIS, TWO_HOURS));
        assertEquals(LocalDate.of(2026, 10, 2), PromotionManager.dayOf(start, PARIS));

        long midnight = LocalDateTime.of(2026, 10, 3, 0, 0).atZone(PARIS).toInstant().toEpochMilli();
        assertEquals(0, PromotionManager.slotIndex(PromotionManager.slotStart(midnight + 1, PARIS, TWO_HOURS), PARIS, TWO_HOURS));
        long lastSlot = LocalDateTime.of(2026, 10, 2, 23, 59).atZone(PARIS).toInstant().toEpochMilli();
        assertEquals(11, PromotionManager.slotIndex(PromotionManager.slotStart(lastSlot, PARIS, TWO_HOURS), PARIS, TWO_HOURS));
    }

    @Test
    void effectiveBaseNumber() {
        assertEquals(32, PromotionManager.effectiveBaseNumber(64, 50), 1e-9);
        assertEquals(48, PromotionManager.effectiveBaseNumber(64, 25), 1e-9);
        assertEquals(20.8, PromotionManager.effectiveBaseNumber(32, 35), 1e-9, "not rounded: exactly -35 %");
        assertEquals(1, PromotionManager.effectiveBaseNumber(1, 50), 1e-9, "base_number 1: no change");
        assertEquals(1, PromotionManager.effectiveBaseNumber(2, 50), 1e-9);
        assertEquals(1, PromotionManager.effectiveBaseNumber(2, 99), 1e-9, "never below 1");
        assertEquals(16, PromotionManager.effectiveBaseNumber(16, 0), 1e-9);
    }

    @Test
    void sellBadgeMatchesTheAnnouncedDiscount() {
        PricingEngine pricing = new PricingEngine(1.0 / 64, 1.0, 64, 1.4);
        MarketItem item = new MarketItem(Material.GOLD_INGOT, "minerals", "rare", 20, 32);
        PricingEngine.Prices regular = pricing.compute(item, 0);
        for (int pct = 5; pct <= 50; pct++) {
            PricingEngine.Prices promo = pricing.promoBuy(item, 0, regular, pct);
            assertEquals(pct, Math.round((1 - regular.buyUnit() / promo.buyUnit()) * 100), "badge for -" + pct + " %");
        }
    }

    @Test
    void promoPricesKeepSellGteBuy() {
        PricingEngine pricing = new PricingEngine(1.0 / 64, 1.0, 64, 1.4);
        List<MarketItem> items = List.of(
                new MarketItem(Material.DIRT, "blocks", "common", 1, 64),
                new MarketItem(Material.DIAMOND, "minerals", "epic", 250, 16),
                new MarketItem(Material.NAME_TAG, "other", "rare", 15, 1));
        for (MarketItem item : items) {
            for (double circulation : new double[]{0, 1e3, 1e6}) {
                PricingEngine.Prices regular = pricing.compute(item, circulation);
                for (int pct = 5; pct <= 50; pct++) {
                    PricingEngine.Prices sell = pricing.promoSell(regular, pct);
                    pricing.check(item, sell);
                    assertTrue(sell.sellUnit() <= regular.sellUnit());
                    assertEquals(regular.buyUnit(), sell.buyUnit());

                    PricingEngine.Prices buy = pricing.promoBuy(item, circulation, regular, pct);
                    pricing.check(item, buy);
                    assertTrue(buy.buyUnit() >= regular.buyUnit());
                    if (item.baseNumber() == 1) {
                        assertEquals(regular, buy, "base_number 1 items are not promoted on the SELL side");
                    }
                }
            }
        }
        // DIAMOND 250 for 16, -50 % => lot of 8: the villager pays twice the unit price
        MarketItem diamond = items.get(1);
        PricingEngine.Prices r = pricing.compute(diamond, 0);
        assertEquals(250.0 / 8, pricing.promoBuy(diamond, 0, r, 50).buyUnit(), 1e-9);
        // BUY side: 1.75 * 0.5 = 0.875 < buy 1.25 => clamped to buy
        MarketItem emerald = new MarketItem(Material.EMERALD, "minerals", "rare", 40, 32);
        PricingEngine.Prices e = pricing.compute(emerald, 0);
        assertEquals(e.buyUnit(), pricing.promoSell(e, 50).sellUnit(), 1e-9);
        assertEquals(e.sellUnit() * 0.8, pricing.promoSell(e, 20).sellUnit(), 1e-9);
    }
}
