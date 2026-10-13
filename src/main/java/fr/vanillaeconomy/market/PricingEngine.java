package fr.vanillaeconomy.market;

import java.util.Map;

/**
 * Pure pricing formulas (no Bukkit dependency, unit-tested).
 *
 * <pre>
 * buy  = ce que l'idiot PAIE au joueur (interface VENTE)
 *      = max(floor(item), baseUnit * exp(-k * circulation / (baseNumber * scaleLots)))
 *        floor(item) = max(1/64, relativeFloor(catégorie) * baseUnit)
 * sell = ce que l'idiot FACTURE au joueur (interface ACHAT), FIXE : il ne dépend que de la
 *        config, pas de la circulation (seules les promotions le modifient)
 *      = max(ref * markup, ref + floor), ref = max(floor, baseUnit)
 *        => toujours sell >= ref + floor > buy
 * </pre>
 */
public final class PricingEngine {

    public record Prices(double buyUnit, double sellUnit) {
    }

    private final double floorPerUnit;
    private final double k;
    private final double scaleLots;
    private final double markup;
    /** Category -> fraction of the base unit price below which the buying price never falls. */
    private final Map<String, Double> relativeFloors;

    public PricingEngine(double floorPerUnit, double k, double scaleLots, double markup) {
        this(floorPerUnit, k, scaleLots, markup, Map.of());
    }

    public PricingEngine(double floorPerUnit, double k, double scaleLots, double markup,
                         Map<String, Double> relativeFloors) {
        if (floorPerUnit <= 0 || k < 0 || scaleLots <= 0 || markup < 1
                || relativeFloors.values().stream().anyMatch(f -> !(f >= 0 && f <= 1))) {
            throw new IllegalArgumentException("invalid pricing parameters");
        }
        this.floorPerUnit = floorPerUnit;
        this.k = k;
        this.scaleLots = scaleLots;
        this.markup = markup;
        this.relativeFloors = Map.copyOf(relativeFloors);
    }

    public double floorPerUnit() {
        return floorPerUnit;
    }

    /**
     * Lowest buying price of an item: the global 1/64 floor, raised to a share of the base
     * price for the categories listed in {@code market.relative_floor} (a diamond never
     * falls below 20 % of its value however many are sold).
     */
    public double floorFor(MarketItem item) {
        return Math.max(floorPerUnit, relativeFloors.getOrDefault(item.category(), 0.0) * item.baseUnitPrice());
    }

    public Prices compute(MarketItem item, double circulation) {
        double scale = item.baseNumber() * scaleLots;
        double buy = Math.max(floorFor(item), item.baseUnitPrice() * Math.exp(-k * Math.max(0, circulation) / scale));
        double sell = regularSell(item);
        Prices prices = new Prices(buy, sell);
        check(item, prices);
        return prices;
    }

    /**
     * Fixed selling price of an item: derived from its configured base price only, so that
     * players selling a lot of an item lower what the villagers pay, never what they charge.
     */
    public double regularSell(MarketItem item) {
        double reference = Math.max(floorPerUnit, item.baseUnitPrice());
        return Math.max(reference * markup, reference + floorPerUnit);
    }

    /**
     * BUY-interface promotion (villager sells cheaper):
     * {@code sell_promo = max(buy, sell * (1 - percent))}, so sell &gt;= buy still holds.
     */
    public Prices promoSell(Prices regular, int percent) {
        return new Prices(regular.buyUnit(), Math.max(regular.buyUnit(), regular.sellUnit() * (1 - percent / 100.0)));
    }

    /**
     * SELL-interface promotion (villager pays more): the reference lot shrinks by exactly
     * {@code percent} ({@link PromotionManager#effectiveBaseNumber}, not rounded so that the
     * real discount equals the announced one), which raises the base unit price; the
     * circulation decay keeps its normal scale. The selling price is raised if needed so
     * that sell &gt;= buy still holds (the item is not on the BUY side during this cycle).
     */
    public Prices promoBuy(MarketItem item, double circulation, Prices regular, int percent) {
        double effective = PromotionManager.effectiveBaseNumber(item.baseNumber(), percent);
        if (effective == item.baseNumber()) {
            return regular;
        }
        double scale = item.baseNumber() * scaleLots;
        double buy = Math.max(floorFor(item),
                item.basePrice() / effective * Math.exp(-k * Math.max(0, circulation) / scale));
        return new Prices(buy, Math.max(regular.sellUnit(), buy));
    }

    /** Hard constraints, verified after every recalculation. */
    public void check(MarketItem item, Prices prices) {
        if (!(prices.buyUnit() >= floorFor(item) - 1e-12) || !(prices.sellUnit() >= prices.buyUnit())
                || Double.isNaN(prices.sellUnit()) || Double.isInfinite(prices.sellUnit())) {
            throw new IllegalStateException("Contrainte de prix violée pour " + item.material() + " : " + prices);
        }
    }

    /**
     * Coins charged by a villager for {@code quantity} units: never less than their value
     * (rounded up), so at least 1 coin for a non-empty purchase.
     */
    public static long charge(double unitPrice, long quantity) {
        return quantity <= 0 ? 0 : (long) Math.ceil(unitPrice * quantity - 1e-9);
    }

    /**
     * Units bought with one click: what one coin buys (21 canes at 0.047 each, not 22 which
     * would cost 2 coins), or a single unit when one unit costs more than a coin.
     */
    public static int buyLot(double unitPrice) {
        return unitPrice >= 1 ? 1 : (int) Math.max(1, Math.floor(1.0 / unitPrice + 1e-9));
    }

    /**
     * Largest quantity up to {@code requested} that is worth a whole number of coins (64 at
     * 0.05 = 3.2 coins: 60 are bought for 3 coins instead of paying 4), or {@code requested}
     * itself when it is worth less than one coin.
     */
    public static long buyableQuantity(double unitPrice, long requested) {
        if (requested <= 0) {
            return 0;
        }
        long coins = (long) Math.floor(unitPrice * requested + 1e-9);
        if (coins < 1) {
            return requested;
        }
        return Math.max(1, Math.min(requested, (long) Math.floor(coins / unitPrice + 1e-9)));
    }

    /**
     * Coins paid by a villager for {@code quantity} units: whole coins only, never rounded
     * up (48 sugar canes at 1/32 = 1.5 coins pay 1 coin).
     */
    public static long payout(double unitPrice, long quantity) {
        return quantity <= 0 ? 0 : (long) Math.floor(unitPrice * quantity + 1e-9);
    }

    /**
     * Units actually taken from the player for {@code offered} units: only those covered by
     * the whole coins paid (48 sugar canes at 1/32: 32 are sold for 1 coin, 16 are kept).
     */
    public static long sellableQuantity(double unitPrice, long offered) {
        long coins = payout(unitPrice, offered);
        return coins <= 0 ? 0 : Math.min(offered, (long) Math.ceil(coins / unitPrice - 1e-9));
    }

    /**
     * Smallest quantity worth at least one coin. Sales to the villager are made in
     * multiples of at least this lot so the "minimum 1 coin" rule can never pay a
     * player 1 coin for a single dirt block (which would be a 64x money exploit).
     */
    public static int lotSize(double unitPrice) {
        return (int) Math.max(1, Math.ceil(1.0 / unitPrice - 1e-9));
    }

    /**
     * The circulation counter (recent sales still weighing on the price) never exceeds the
     * stock: what players have bought back no longer lowers the price, and an item out of
     * stock is bought at its full base price again.
     */
    public static double boundedCirculation(double circulation, long stock) {
        return Math.max(0, Math.min(circulation, Math.max(0, stock)));
    }

    /** Circulation decay applied once per rotation cycle. */
    public static double decay(double circulation, double decayRate) {
        return Math.max(0, circulation * (1 - decayRate));
    }
}
