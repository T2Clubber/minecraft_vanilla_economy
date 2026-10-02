package fr.vanillaeconomy.market;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromoSchedulerTest {

    /** Simulates {@code cycles} rotations and returns the promo flags. */
    private static List<Boolean> simulate(PromoScheduler scheduler, int cycles, Random random) {
        List<Boolean> flags = new ArrayList<>();
        String history = "";
        for (int i = 0; i < cycles; i++) {
            boolean promo = scheduler.decide(history, random);
            flags.add(promo);
            history = scheduler.push(history, promo);
        }
        return flags;
    }

    @Test
    void everyWindowOfTwelveHasAtLeastTwoPromos() {
        for (double chance : new double[]{0.0, 0.15, 0.5}) {
            List<Boolean> flags = simulate(new PromoScheduler(12, 2, chance), 20_000, new Random(7));
            for (int start = 0; start + 12 <= flags.size(); start++) {
                long promos = flags.subList(start, start + 12).stream().filter(b -> b).count();
                assertTrue(promos >= 2, "window at " + start + " has " + promos + " (chance " + chance + ")");
            }
        }
    }

    @Test
    void chanceAddsExtraPromos() {
        long base = simulate(new PromoScheduler(12, 2, 0.0), 12_000, new Random(1)).stream().filter(b -> b).count();
        long extra = simulate(new PromoScheduler(12, 2, 0.3), 12_000, new Random(1)).stream().filter(b -> b).count();
        assertEquals(2000, base); // exactly 2 per 12 with no randomness
        assertTrue(extra > base * 1.3);
    }

    @Test
    void historyKeepsWindowMinusOne() {
        PromoScheduler scheduler = new PromoScheduler(12, 2, 0);
        String h = "";
        for (int i = 0; i < 30; i++) {
            h = scheduler.push(h, i % 3 == 0);
        }
        assertEquals(11, h.length());
        assertFalse(h.isEmpty());
    }

    @Test
    void promoPriceNeverBreaksSellGteBuy() {
        PricingEngine pricing = new PricingEngine(1.0 / 64, 1.0, 64, 1.4);
        MarketItem dirt = new MarketItem(Material.DIRT, "blocks", "common", 1, 64);
        MarketItem diamond = new MarketItem(Material.DIAMOND, "minerals", "epic", 250, 16);
        for (MarketItem item : List.of(dirt, diamond)) {
            for (double circulation : new double[]{0, 1e3, 1e6}) {
                PricingEngine.Prices p = pricing.compute(item, circulation);
                for (double discount : new double[]{0.1, 0.25, 0.5, 0.9}) {
                    double promo = pricing.promoSellUnit(p, discount);
                    assertTrue(promo >= p.buyUnit() + 1.0 / 64 - 1e-12);
                    assertTrue(promo <= p.sellUnit());
                }
            }
        }
        PricingEngine.Prices d = pricing.compute(diamond, 0);
        assertEquals(d.sellUnit() * 0.75, pricing.promoSellUnit(d, 0.25), 1e-9);
    }
}
