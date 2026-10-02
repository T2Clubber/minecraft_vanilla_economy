package fr.vanillaeconomy.market;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Parses items.yml as provided (global settings, slots_per_category, categories).
 * Invalid entries are skipped with a warning instead of aborting the plugin.
 */
public final class ItemConfigLoader {

    public record GlobalSettings(double priceFloorPerUnit, boolean sellMustBeGteBuy, boolean noOverlap,
                                 double rotationIntervalHours, boolean favorInStock) {
    }

    /**
     * @param slotsPerCategory ordered category -> slot count (order = GUI slot order)
     * @param items            every valid item, keyed by material
     */
    public record ItemConfig(GlobalSettings global, LinkedHashMap<String, Integer> slotsPerCategory,
                             Map<Material, MarketItem> items) {

        public int totalSlots() {
            return slotsPerCategory.values().stream().mapToInt(Integer::intValue).sum();
        }

        public List<MarketItem> category(String category) {
            return items.values().stream().filter(i -> i.category().equals(category)).toList();
        }
    }

    private ItemConfigLoader() {
    }

    public static ItemConfig load(YamlConfiguration yaml, Logger logger) {
        ConfigurationSection g = yaml.getConfigurationSection("global");
        GlobalSettings global = new GlobalSettings(
                g == null ? 1.0 / 64 : g.getDouble("price_floor_per_unit", 1.0 / 64),
                g == null || g.getBoolean("sell_must_be_gte_buy", true),
                g == null || g.getBoolean("no_overlap_buy_sell", true),
                g == null ? 2.0 : g.getDouble("rotation_interval_hours", 2.0),
                g == null || g.getBoolean("favor_in_stock_items_for_buy_rotation", true));
        if (!global.sellMustBeGteBuy() || !global.noOverlap()) {
            logger.warning("items.yml : sell_must_be_gte_buy / no_overlap_buy_sell sont des contraintes dures, elles restent appliquées.");
        }

        LinkedHashMap<String, Integer> slots = new LinkedHashMap<>();
        ConfigurationSection s = yaml.getConfigurationSection("slots_per_category");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                int n = s.getInt(key);
                if (n > 0) {
                    slots.put(key, n);
                }
            }
        }

        Map<Material, MarketItem> items = new LinkedHashMap<>();
        ConfigurationSection cats = yaml.getConfigurationSection("categories");
        if (cats != null) {
            for (String category : cats.getKeys(false)) {
                if (!slots.containsKey(category)) {
                    logger.warning("items.yml : catégorie '" + category + "' absente de slots_per_category, ignorée dans les rotations.");
                }
                for (Map<?, ?> raw : cats.getMapList(category)) {
                    MarketItem item = parse(category, raw, logger);
                    if (item == null) {
                        continue;
                    }
                    if (items.putIfAbsent(item.material(), item) != null) {
                        logger.warning("items.yml : " + item.material() + " en double, seule la première entrée est gardée.");
                    }
                }
            }
        }
        for (String category : slots.keySet()) {
            long available = items.values().stream().filter(i -> i.category().equals(category)).count();
            if (available < 2L * slots.get(category)) {
                logger.warning("items.yml : catégorie '" + category + "' n'a que " + available
                        + " items pour " + slots.get(category) + " slot(s) VENTE + ACHAT : certains slots pourront rester vides.");
            }
        }
        return new ItemConfig(global, slots, Collections.unmodifiableMap(items));
    }

    private static MarketItem parse(String category, Map<?, ?> raw, Logger logger) {
        Object id = raw.get("id");
        Material material = id == null ? null : Material.matchMaterial(String.valueOf(id));
        if (material == null || !material.isItem() || material.isAir()) {
            logger.warning("items.yml : id inconnu '" + id + "' dans '" + category + "', ignoré (version du serveur trop ancienne ?).");
            return null;
        }
        double basePrice = toDouble(raw.get("base_price"));
        int baseNumber = (int) toDouble(raw.get("base_number"));
        if (basePrice <= 0 || baseNumber <= 0) {
            logger.warning("items.yml : base_price/base_number invalides pour " + material + ", ignoré.");
            return null;
        }
        if (baseNumber > material.getMaxStackSize()) {
            logger.warning("items.yml : base_number de " + material + " (" + baseNumber
                    + ") dépasse la stack vanilla (" + material.getMaxStackSize() + ").");
        }
        Object tier = raw.get("tier");
        return new MarketItem(material, category, tier == null ? "common" : String.valueOf(tier), basePrice, baseNumber);
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? -1 : Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
