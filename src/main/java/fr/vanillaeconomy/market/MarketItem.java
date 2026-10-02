package fr.vanillaeconomy.market;

import org.bukkit.Material;

/**
 * One entry of items.yml. {@code basePrice} is the price of {@code baseNumber} units.
 */
public record MarketItem(Material material, String category, String tier, double basePrice, int baseNumber) {

    public double baseUnitPrice() {
        return basePrice / baseNumber;
    }
}
