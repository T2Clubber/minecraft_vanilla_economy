package fr.vanillaeconomy.city;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Options a city buys once with its treasury (onglet Extensions du /cityboard). */
public enum CityExtension {
    /** Members may use /city tp &lt;cité&gt; to reach the centre, paying a fee each time. */
    TELEPORT("Téléportation");

    private final String label;

    CityExtension(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Stored in city_extension.extension. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CityExtension> byKey(String key) {
        return Arrays.stream(values()).filter(e -> e.key().equals(key)).findFirst();
    }
}
