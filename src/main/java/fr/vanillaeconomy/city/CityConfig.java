package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.Tier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/** Settings of cities.yml. */
public final class CityConfig {

    public final long creationCost;
    public final Set<String> allowedWorlds;
    public final int minGap;
    public final int nameMin;
    public final int nameMax;
    public final List<Tier> tiers;
    public final boolean protectAnimals;
    public final boolean actionbarMessage;
    public final Set<Material> visitorInteractions;
    public final boolean presenceMessages;
    public final boolean presenceOnJoin;
    public final int borderRadius;
    public final int borderHeight;
    public final long borderRefreshTicks;
    public final long confirmMillis;

    public CityConfig(YamlConfiguration yaml, Logger logger) {
        creationCost = Math.max(0, yaml.getLong("creation_cost", 2500));
        allowedWorlds = new HashSet<>(yaml.getStringList("allowed_worlds"));
        minGap = Math.max(0, yaml.getInt("min_gap_blocks", 16));
        nameMin = Math.max(1, yaml.getInt("name.min_length", 3));
        nameMax = Math.max(nameMin, yaml.getInt("name.max_length", 16));

        List<Tier> parsed = new ArrayList<>();
        ConfigurationSection t = yaml.getConfigurationSection("tiers");
        if (t != null) {
            for (String key : t.getKeys(false)) {
                try {
                    parsed.add(new Tier(Integer.parseInt(key), t.getInt(key + ".size"), t.getLong(key + ".price")));
                } catch (NumberFormatException e) {
                    logger.warning("cities.yml : palier '" + key + "' ignoré (numéro invalide).");
                }
            }
        }
        parsed.sort(Comparator.comparingInt(Tier::level));
        List<String> errors = CityRules.validateTiers(parsed);
        if (!errors.isEmpty()) {
            errors.forEach(e -> logger.warning("cities.yml : " + e));
            logger.warning("cities.yml : paliers invalides, utilisation des paliers par défaut.");
            parsed = List.of(new Tier(1, 32, 0), new Tier(2, 64, 5000), new Tier(3, 128, 20000),
                    new Tier(4, 256, 50000), new Tier(5, 512, 100000));
        }
        tiers = List.copyOf(parsed);

        protectAnimals = yaml.getBoolean("protection.protect_animals", true);
        actionbarMessage = yaml.getBoolean("protection.actionbar_message", true);
        List<String> interactions = yaml.isList("protection.visitor_interactions")
                ? yaml.getStringList("protection.visitor_interactions") : List.of("#doors", "#trapdoors");
        visitorInteractions = parseMaterials(interactions, logger);
        presenceMessages = yaml.getBoolean("presence.messages", true);
        presenceOnJoin = yaml.getBoolean("presence.message_on_join", true);
        borderRadius = Math.max(4, yaml.getInt("border.radius", 24));
        borderHeight = Math.max(1, yaml.getInt("border.height", 8));
        borderRefreshTicks = Math.max(2, yaml.getLong("border.refresh_ticks", 10));
        confirmMillis = Math.max(5, yaml.getLong("confirm_seconds", 30)) * 1000L;
    }

    public Tier tier(int level) {
        return tiers.stream().filter(t -> t.level() == level).findFirst().orElse(tiers.getLast());
    }

    public int maxTier() {
        return tiers.getLast().level();
    }

    /** "#doors" -> block tag minecraft:doors, otherwise a Material id. */
    private static Set<Material> parseMaterials(List<String> ids, Logger logger) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String id : ids) {
            if (id.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(id.substring(1).toLowerCase());
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    logger.warning("cities.yml : tag de blocs inconnu " + id);
                } else {
                    set.addAll(tag.getValues());
                }
            } else {
                Material mat = Material.matchMaterial(id);
                if (mat == null || !mat.isBlock()) {
                    logger.warning("cities.yml : bloc inconnu " + id);
                } else {
                    set.add(mat);
                }
            }
        }
        return set;
    }
}
