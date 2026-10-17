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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    /** Common settings of an extension (cities.yml, extensions.&lt;key&gt;). */
    public record ExtensionSettings(boolean enabled, int minTier, long price) {
    }

    private final Map<CityExtension, ExtensionSettings> extensions = new EnumMap<>(CityExtension.class);
    public final long teleportUseCost;
    /** Promotion en folie: villager types (registry keys) that must all be present in the city. */
    public final List<String> frenzyTypes;
    public final int frenzyPromosPerDay;
    public final int frenzyMinPercent;
    public final int frenzyMaxPercent;
    public final boolean frenzyWarning;
    public final long frenzyWarningMillis;
    public final boolean frenzyStart;
    public final boolean frenzyJoinReminder;
    /** Beacon: valid beacons required, pyramid levels, skin-check cooldown, effect task. */
    public final int beaconRequired;
    public final int beaconPyramid;
    public final long beaconCheckCooldownMillis;
    public final long beaconApplyIntervalTicks;
    public final int beaconEffectDurationTicks;
    public final boolean beaconMembersOnly;
    public final int rankingSize;

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
        // cities.yml is never overwritten by an update: show what is really in use.
        logger.info("Paliers des cités : " + String.join(", ", tiers.stream()
                .map(tier -> tier.level() + " = " + tier.size() + "x" + tier.size()
                        + (tier.level() > 1 ? " (" + tier.price() + ")" : ""))
                .toList()));

        protectAnimals = yaml.getBoolean("protection.protect_animals", true);
        actionbarMessage = yaml.getBoolean("protection.actionbar_message", true);
        List<String> interactions = yaml.isList("protection.visitor_interactions")
                ? yaml.getStringList("protection.visitor_interactions") : List.of("#doors", "#trapdoors");
        visitorInteractions = parseMaterials(interactions, logger);
        // Personal storage: a visitor only ever sees his own items, nothing of the city.
        visitorInteractions.add(Material.ENDER_CHEST);
        presenceMessages = yaml.getBoolean("presence.messages", true);
        presenceOnJoin = yaml.getBoolean("presence.message_on_join", true);
        borderRadius = Math.max(4, yaml.getInt("border.radius", 24));
        borderHeight = Math.max(1, yaml.getInt("border.height", 8));
        borderRefreshTicks = Math.max(2, yaml.getLong("border.refresh_ticks", 10));
        confirmMillis = Math.max(5, yaml.getLong("confirm_seconds", 30)) * 1000L;
        for (CityExtension ext : CityExtension.values()) {
            String path = "extensions." + ext.key() + ".";
            extensions.put(ext, new ExtensionSettings(yaml.getBoolean(path + "enabled", true),
                    Math.max(1, yaml.getInt(path + "min_tier", ext.defaultMinTier())),
                    Math.max(0, yaml.getLong(path + "price", ext.defaultPrice()))));
        }
        teleportUseCost = Math.max(0, yaml.getLong("extensions.teleport.use_cost", 100));
        String frenzy = "extensions.promo_frenzy.";
        frenzyTypes = villagerTypes(yaml.isList(frenzy + "required_villager_types")
                ? yaml.getStringList(frenzy + "required_villager_types") : DEFAULT_FRENZY_TYPES, logger);
        frenzyPromosPerDay = Math.max(0, yaml.getInt(frenzy + "promos_per_day", 4));
        frenzyMinPercent = Math.max(1, Math.min(99, yaml.getInt(frenzy + "min_percent", 5)));
        frenzyMaxPercent = Math.max(frenzyMinPercent, Math.min(99, yaml.getInt(frenzy + "max_percent", 50)));
        frenzyWarning = yaml.getBoolean(frenzy + "announcements.warning", true);
        frenzyWarningMillis = Math.max(1, yaml.getLong(frenzy + "announcements.warning_minutes_before", 120)) * 60_000L;
        frenzyStart = yaml.getBoolean(frenzy + "announcements.start", true);
        frenzyJoinReminder = yaml.getBoolean(frenzy + "announcements.join_reminder", true);
        String beacon = "extensions.beacon.";
        beaconRequired = Math.max(1, yaml.getInt(beacon + "required_beacons", 5));
        beaconPyramid = Math.max(1, Math.min(4, yaml.getInt(beacon + "required_pyramid_levels", 4)));
        beaconCheckCooldownMillis = Math.max(0, yaml.getLong(beacon + "check_cooldown_seconds", 30)) * 1000L;
        beaconApplyIntervalTicks = Math.max(10, yaml.getLong(beacon + "apply_interval_ticks", 80));
        int duration = yaml.getInt(beacon + "effect_duration_ticks", 200);
        if (duration <= beaconApplyIntervalTicks) {
            logger.warning("cities.yml : extensions.beacon.effect_duration_ticks doit dépasser apply_interval_ticks "
                    + "(sinon les effets clignotent) : " + (beaconApplyIntervalTicks + 40) + " utilisé.");
            duration = (int) beaconApplyIntervalTicks + 40;
        }
        beaconEffectDurationTicks = duration;
        beaconMembersOnly = yaml.getBoolean(beacon + "members_only", false);
        rankingSize = Math.max(1, Math.min(15, yaml.getInt("ranking.size", 10)));
    }

    /** Disabled by the administration: not for sale and not usable, even if already bought. */
    public boolean enabled(CityExtension extension) {
        return extensions.get(extension).enabled();
    }

    /** Tier the city must have reached before buying the extension. */
    public int minTier(CityExtension extension) {
        return extensions.get(extension).minTier();
    }

    /** Paid once with the city's treasury. */
    public long price(CityExtension extension) {
        return extensions.get(extension).price();
    }

    private static final List<String> DEFAULT_FRENZY_TYPES =
            List.of("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga");

    /** Keys checked against the Villager.Type registry; unknown ones are reported and ignored. */
    private static List<String> villagerTypes(List<String> keys, Logger logger) {
        var registry = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.VILLAGER_TYPE);
        List<String> valid = new ArrayList<>();
        for (String key : keys) {
            String k = key.toLowerCase(java.util.Locale.ROOT).trim();
            NamespacedKey id = NamespacedKey.fromString(k);
            if (id == null || registry.get(id) == null) {
                logger.warning("cities.yml : type de villageois inconnu « " + key + " » (required_villager_types).");
            } else if (!valid.contains(id.value())) {
                valid.add(id.value());
            }
        }
        return List.copyOf(valid);
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
