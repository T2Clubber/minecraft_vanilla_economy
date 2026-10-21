package fr.vanillaeconomy.market;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void sellPriceIsFixedByConfigWhateverTheCirculation() {
        MarketItem diamond = new MarketItem(Material.DIAMOND, "minerals", "epic", 30, 1);
        for (double circulation : new double[]{0, 64, 1e3, 1e6, 1e12}) {
            PricingEngine.Prices p = pricing.compute(diamond, circulation);
            assertEquals(42, p.sellUnit(), 1e-9, "30 x 1.4, even after massive sales");
            assertEquals(42, PricingEngine.charge(p.sellUnit(), PricingEngine.buyLot(p.sellUnit())));
        }
        MarketItem dirt = SAMPLE.get(0);
        assertEquals(2.0 / 64, pricing.compute(dirt, 1e9).sellUnit(), 1e-12, "base + floor on cheap items");
        // promotions still lower it, never below what the villager pays
        PricingEngine.Prices regular = pricing.compute(diamond, 0);
        assertEquals(30, pricing.promoSell(regular, 50).sellUnit(), 1e-9);
        PricingEngine.Prices crashed = pricing.compute(diamond, 1e6);
        assertEquals(21, pricing.promoSell(crashed, 50).sellUnit(), 1e-9);
    }

    @Test
    void relativeFloorOnlyForListedCategories() {
        PricingEngine floored = new PricingEngine(FLOOR, 1.0, 64, 1.4, Map.of("minerals", 0.2, "other", 0.2));
        MarketItem diamond = new MarketItem(Material.DIAMOND, "minerals", "epic", 30, 1);
        MarketItem disc = new MarketItem(Material.MUSIC_DISC_CAT, "other", "legendary", 150, 1);
        MarketItem pearl = SAMPLE.get(4);
        for (double circulation : new double[]{0, 64, 1e3, 1e9}) {
            PricingEngine.Prices d = floored.compute(diamond, circulation);
            assertTrue(d.buyUnit() >= 6 - 1e-9, "diamond never below 20 % of 30");
            assertTrue(d.sellUnit() >= d.buyUnit());
            assertTrue(floored.compute(disc, circulation).buyUnit() >= 30 - 1e-9);
            assertEquals(pricing.compute(pearl, circulation).buyUnit(), floored.compute(pearl, circulation).buyUnit(),
                    1e-12, "other categories keep the 1/64 floor");
        }
        assertEquals(6, floored.compute(diamond, 1e9).buyUnit(), 1e-9);
        assertEquals(30, floored.compute(diamond, 0).buyUnit(), 1e-9);
        assertEquals(FLOOR, floored.compute(pearl, 1e9).buyUnit(), 1e-12);
        PricingEngine.Prices promo = floored.promoBuy(diamond, 1e9, floored.compute(diamond, 1e9), 50);
        assertTrue(promo.buyUnit() >= 6 - 1e-9);
    }

    @Test
    void purchasesAreNeverRoundedInThePlayersFavour() {
        double cane = 0.046875; // 1.5 x 1/32
        assertEquals(21, PricingEngine.buyLot(cane), "what one coin buys: 21 (22 would cost 2 coins)");
        assertEquals(1, PricingEngine.charge(cane, 21));
        assertEquals(1, PricingEngine.buyLot(21.875), "one unit when it costs more than a coin");
        assertEquals(22, PricingEngine.charge(21.875, 1), "21.875 is charged 22, never 21");
        assertEquals(60, PricingEngine.buyableQuantity(0.05, 64), "64 x 0.05 = 3.2: 60 for 3 coins");
        assertEquals(3, PricingEngine.charge(0.05, 60));
        assertEquals(64, PricingEngine.buyableQuantity(cane, 64), "64 x 0.046875 = 3 exactly");
        assertEquals(5, PricingEngine.buyableQuantity(cane, 5), "less than one coin: kept as asked");
        assertEquals(1, PricingEngine.charge(cane, 5), "minimum 1 coin");
        for (double unit : new double[]{1.0 / 64, 0.0287, 0.05, 0.1, 0.33, 0.7, 1.0, 1.05, 2.4, 21.875}) {
            for (long n = 1; n <= 640; n++) {
                long q = PricingEngine.buyableQuantity(unit, n);
                long coins = PricingEngine.charge(unit, q);
                assertTrue(q >= 1 && q <= n);
                assertTrue(coins >= unit * q - 1e-9, "undercharged " + unit + " x " + q);
                assertTrue(coins >= 1);
            }
        }
    }

    @Test
    void salesPayWholeCoinsOnlyAndKeepTheRest() {
        double cane = 1.0 / 32;
        assertEquals(1, PricingEngine.payout(cane, 48), "1.5 coins pay 1 coin, never 2");
        assertEquals(32, PricingEngine.sellableQuantity(cane, 48), "only the 32 paid canes are taken");
        assertEquals(64, PricingEngine.sellableQuantity(cane, 64));
        assertEquals(2, PricingEngine.payout(cane, 64));
        assertEquals(0, PricingEngine.sellableQuantity(cane, 31), "not even one coin: nothing sold");
        assertEquals(5, PricingEngine.sellableQuantity(0.4, 7), "7 x 0.4 = 2.8 -> 2 coins for 5 units");
        assertEquals(2, PricingEngine.payout(0.4, 5));
        // never pays more than the value of the units taken, never takes units it does not pay
        for (double unit : new double[]{1.0 / 64, 0.0287, 0.1, 0.33, 0.7, 1.0, 2.4, 15.625}) {
            for (long n = 1; n <= 640; n++) {
                long q = PricingEngine.sellableQuantity(unit, n);
                long coins = PricingEngine.payout(unit, q);
                assertTrue(q <= n);
                assertTrue(coins <= unit * q + 1e-9, "overpaid " + unit + " x " + q);
                assertEquals(PricingEngine.payout(unit, n), coins, "same coins as for the whole offer");
                assertTrue(q == 0 || PricingEngine.payout(unit, q - 1) < coins, "no unit taken for free");
            }
        }
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
    void circulationIsBoundedByTheStockAndTheFloor() {
        PricingEngine floored = new PricingEngine(FLOOR, 1.0, 64, 1.4, Map.of("minerals", 0.2));
        MarketItem diamond = new MarketItem(Material.DIAMOND, "minerals", "epic", 30, 1);
        double max = floored.maxCirculation(diamond);
        assertEquals(64 * Math.log(5), max, 1e-9, "where 30 * e^(-c/64) reaches the 6-coin floor");
        assertEquals(0, floored.bound(diamond, 180, 0), "out of stock: full price again");
        assertEquals(40, floored.bound(diamond, 180, 40));
        assertEquals(max, floored.bound(diamond, 500, 1000), 1e-9, "sales at the floor do not dig deeper");
        assertEquals(12.5, floored.bound(diamond, 12.5, 300), "otherwise unchanged");
        assertEquals(0, floored.bound(diamond, -3, 10));
        assertEquals(30, floored.compute(diamond, floored.bound(diamond, 180, 0)).buyUnit(), 1e-9);
    }

    @Test
    void priceRecoversBySixPercentPerRotation() {
        PricingEngine floored = new PricingEngine(FLOOR, 1.0, 64, 1.4, Map.of("minerals", 0.2));
        MarketItem diamond = new MarketItem(Material.DIAMOND, "minerals", "epic", 30, 1);
        double c = floored.bound(diamond, 1e6, 1_000_000);
        double price = floored.compute(diamond, c).buyUnit();
        assertEquals(6, price, 1e-9);
        int rotations = 0;
        while (price < 30 - 1e-9) {
            c = floored.recover(diamond, c, 0.06);
            double next = floored.compute(diamond, c).buyUnit();
            assertTrue(Math.abs(next - Math.min(30, price * 1.06)) < 1e-9, "+6 % per rotation, capped at the base price");
            price = next;
            rotations++;
        }
        assertEquals(28, rotations, "6 -> 30 in 28 rotations (~2.3 days)");
        assertEquals(0, c);
        // +10 % per rotation (config.yml default): 17 rotations
        c = floored.bound(diamond, 1e6, 1_000_000);
        rotations = 0;
        while (floored.compute(diamond, c).buyUnit() < 30 - 1e-9) {
            c = floored.recover(diamond, c, 0.1);
            rotations++;
        }
        assertEquals(17, rotations, "6 -> 30 in 17 rotations (~1.4 days)");
    }
}
