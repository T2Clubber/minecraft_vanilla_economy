package fr.vanillaeconomy.city;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;

/**
 * Single repeating task of the Beacon extension: every player standing in a city that has
 * it (presence map, memory only) receives the union of the effects of its cached beacons,
 * for a short duration so that they fade quickly after leaving the city. Ambient effects,
 * as from a beacon; a stronger or longer effect is never replaced.
 */
public final class BeaconEffectTask {

    private final Plugin plugin;
    private final CityManager cities;
    private final ExtensionManager extensions;
    private final BeaconEffectCache cache;
    private final CityPresenceListener presence;
    private BukkitTask task;

    public BeaconEffectTask(Plugin plugin, CityManager cities, ExtensionManager extensions, BeaconEffectCache cache,
                            CityPresenceListener presence) {
        this.plugin = plugin;
        this.cities = cities;
        this.extensions = extensions;
        this.cache = cache;
        this.presence = presence;
    }

    public void start() {
        long interval = extensions.config().beaconApplyIntervalTicks;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::apply, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void apply() {
        CityConfig config = extensions.config();
        int duration = config.beaconEffectDurationTicks;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Integer cityId = presence.cityOf(player.getUniqueId());
            City city = cityId == null ? null : cities.byId(cityId);
            boolean member = city != null && city.isMember(player.getUniqueId());
            if (!BeaconExtension.receives(cityId, extensions.active(city, CityExtension.BEACON), config.beaconMembersOnly,
                    member)) {
                continue;
            }
            for (Map.Entry<PotionEffectType, Integer> effect : cache.effects(cityId).entrySet()) {
                PotionEffect current = player.getPotionEffect(effect.getKey());
                if (BeaconExtension.shouldApply(current != null, current == null ? 0 : current.getAmplifier(),
                        current == null ? 0 : current.getDuration(), effect.getValue(), duration)) {
                    player.addPotionEffect(new PotionEffect(effect.getKey(), duration, effect.getValue(), true, true, true));
                }
            }
        }
    }
}
