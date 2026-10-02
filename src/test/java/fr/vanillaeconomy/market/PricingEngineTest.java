package fr.vanillaeconomy.market;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingEngineTest {

    private static final double FLOOR = 1.0 / 64;
    private final PricingEngine pricing = new PricingEngine(FLOOR, 1.0, 64, 1.4);

    private static final List<MarketItem> SAMPLE = List.of(
            new MarketItem(Material.DIRT, "blocks", "common", 1, 64),
            new MarketItem(Material.DIAMOND, "minerals", "epic", 250, 16),
            new MarketItem(Material.NETHERITE_INGOT, "minerals", "legendary", 1500, 8),
            new MarketItem(Material.NAME_TAG, "other", "rare", 15, 1),
            new MarketItem(Material.ENDER_PEARL, "hostile_mob", "rare", 12, 16));

    @Test
    void baseUnitPriceUsesBaseNumber() {
        PricingEngine.Prices dirt = pricing.compute(SAMPLE.get(0), 0);
        assertEquals(1.0 / 64, dirt.buyUnit(), 1e-12);
        PricingEngine.Prices diamond = pricing.compute(SAMPLE.get(1), 0);
        assertEquals(250.0 / 16, diamond.buyUnit(), 1e-12);
    }

    @Test
    void floorAndSellGteBuyHoldForAnyCirculation() {
        for (MarketItem item : SAMPLE) {
            for (double circulation : new double[]{0, 1, 10, 1e3, 1e5, 1e7, 1e12}) {
                PricingEngine.Prices p = pricing.compute(item, circulation);
                assertTrue(p.buyUnit() >= FLOOR, item + " buy below floor");
                assertTrue(p.sellUnit() >= p.buyUnit() + FLOOR - 1e-12, item + " sell < buy + floor");
                assertTrue(p.sellUnit() >= p.buyUnit() * 1.4 - 1e-12);
            }
        }
    }

    @Test
    void buyPriceDecreasesWithCirculation() {
        MarketItem diamond = SAMPLE.get(1);
        double previous = Double.MAX_VALUE;
        for (double c = 0; c < 100_000; c += 500) {
            double buy = pricing.compute(diamond, c).buyUnit();
            assertTrue(buy <= previous);
            previous = buy;
        }
        assertEquals(FLOOR, pricing.compute(diamond, 1e9).buyUnit(), 1e-12);
    }

    @Test
    void transactionTotals() {
        assertEquals(0, PricingEngine.total(0.5, 0));
        assertEquals(1, PricingEngine.total(1.0 / 64, 1));    // minimum 1 coin
        assertEquals(1, PricingEngine.total(1.0 / 64, 64));
        assertEquals(2, PricingEngine.total(1.0 / 64, 100));  // round(1.5625)
        assertEquals(47, PricingEngine.total(250.0 / 16, 3)); // round(46.875)
    }

    @Test
    void lotSizeIsSmallestQuantityWorthOneCoin() {
        assertEquals(64, PricingEngine.lotSize(1.0 / 64));
        assertEquals(32, PricingEngine.lotSize(1.0 / 32));
        assertEquals(1, PricingEngine.lotSize(15.625));
        assertEquals(3, PricingEngine.lotSize(0.4));
        for (double unit : new double[]{1.0 / 64, 0.02, 0.1, 0.33, 0.7, 1.0, 3.3}) {
            int lot = PricingEngine.lotSize(unit);
            assertTrue(unit * lot >= 1 - 1e-9);
            assertTrue(lot == 1 || unit * (lot - 1) < 1);
        }
    }

    @Test
    void decay() {
        assertEquals(98, PricingEngine.decay(100, 0.02), 1e-9);
        assertEquals(0, PricingEngine.decay(0, 0.02));
    }
}
