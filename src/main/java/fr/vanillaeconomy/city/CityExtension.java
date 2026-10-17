package fr.vanillaeconomy.city;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Registry of the options a city buys once with its treasury (onglet Extensions du
 * /cityboard). Stored by {@link #key()} in city_extension; settings in cities.yml under
 * extensions.&lt;key&gt; (enabled, min_tier, price), with the defaults below.
 */
public enum CityExtension {
    /** Members may use /city tp &lt;cité&gt; to reach the centre, paying a fee each time. */
    TELEPORT("Téléportation", 2, 10_000),
    /**
     * Market nitwits standing in the city get extra promotion rotations every day. Requires
     * the 7 nitwit skins inside the city when it is bought.
     */
    PROMO_FRENZY("Promotion en folie", 3, 10_000);

    private final String label;
    private final int defaultMinTier;
    private final long defaultPrice;

    CityExtension(String label, int defaultMinTier, long defaultPrice) {
        this.label = label;
        this.defaultMinTier = defaultMinTier;
        this.defaultPrice = defaultPrice;
    }

    public String label() {
        return label;
    }

    public int defaultMinTier() {
        return defaultMinTier;
    }

    public long defaultPrice() {
        return defaultPrice;
    }

    /** Stored in city_extension.extension and used as the cities.yml section name. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CityExtension> byKey(String key) {
        return Arrays.stream(values()).filter(e -> e.key().equals(key)).findFirst();
    }
}
