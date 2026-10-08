package fr.vanillaeconomy.market;

/**
 * Pure pricing formulas (no Bukkit dependency, unit-tested).
 *
 * <pre>
 * buy  = ce que l'idiot PAIE au joueur (interface VENTE)
 *      = max(floor, baseUnit * exp(-k * circulation / (baseNumber * scaleLots)))
 * sell = ce que l'idiot FACTURE au joueur (interface ACHAT)
 *      = max(buy * markup, buy + floor)          => toujours sell >= buy + floor > buy
 * total(N) = max(1, round(unit * N)) pour N > 0
 * </pre>
 */
public final class PricingEngine {

    public record Prices(double buyUnit, double sellUnit) {
    }

    private final double floorPerUnit;
    private final double k;
    private final double scaleLots;
    private final double markup;

    public PricingEngine(double floorPerUnit, double k, double scaleLots, double markup) {
        if (floorPerUnit <= 0 || k < 0 || scaleLots <= 0 || markup < 1) {
            throw new IllegalArgumentException("invalid pricing parameters");
        }
        this.floorPerUnit = floorPerUnit;
        this.k = k;
        this.scaleLots = scaleLots;
        this.markup = markup;
    }

    public double floorPerUnit() {
        return floorPerUnit;
    }

    public Prices compute(MarketItem item, double circulation) {
        double scale = item.baseNumber() * scaleLots;
        double buy = Math.max(floorPerUnit, item.baseUnitPrice() * Math.exp(-k * Math.max(0, circulation) / scale));
        double sell = Math.max(buy * markup, buy + floorPerUnit);
        Prices prices = new Prices(buy, sell);
        check(item, prices);
        return prices;
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
        double buy = Math.max(floorPerUnit,
                item.basePrice() / effective * Math.exp(-k * Math.max(0, circulation) / scale));
        return new Prices(buy, Math.max(regular.sellUnit(), buy));
    }

    /** Hard constraints, verified after every recalculation. */
    public void check(MarketItem item, Prices prices) {
        if (!(prices.buyUnit() >= floorPerUnit) || !(prices.sellUnit() >= prices.buyUnit())
                || Double.isNaN(prices.sellUnit()) || Double.isInfinite(prices.sellUnit())) {
            throw new IllegalStateException("Contrainte de prix violée pour " + item.material() + " : " + prices);
        }
    }

    /** Coins for {@code quantity} units: round(unit * N), at least 1 for a non-empty transaction. */
    public static long total(double unitPrice, long quantity) {
        if (quantity <= 0) {
            return 0;
        }
        return Math.max(1L, Math.round(unitPrice * quantity));
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

    /** Circulation decay applied once per rotation cycle. */
    public static double decay(double circulation, double decayRate) {
        return Math.max(0, circulation * (1 - decayRate));
    }
}
