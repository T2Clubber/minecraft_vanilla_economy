package fr.vanillaeconomy.villager;

import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.storage.Database;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Turns adult nitwits into market NPCs. The plugin never spawns, freezes, protects or
 * despawns anything: villagers keep their vanilla AI and lifecycle.
 *
 * <p>Paper has no "baby grew up" event, so every baby villager seen (spawn, breeding,
 * chunk load) is kept in a lightweight UUID set and checked every few seconds; the
 * tag is applied the moment it is an adult nitwit. Adults that already are nitwits
 * (natural village generation, cured zombie villager...) are tagged as soon as they
 * spawn or their chunk loads.
 */
public final class NitwitVillagerManager implements Listener {

    private final Plugin plugin;
    private final Database db;
    private final NamespacedKey marketKey;
    private final NamespacedKey originBiomeKey;
    private final NamespacedKey birthBiomeKey;
    private final Set<UUID> trackedBabies = new HashSet<>();
    private final long growthCheckTicks;
    private final double breedingNitwitChance;
    private BukkitTask growthTask;

    /** GUI openers: right-click = BUY interface, sneak + right-click = SELL interface. */
    private BiConsumer<Player, Villager> openBuy = (p, v) -> { };
    private BiConsumer<Player, Villager> openSell = (p, v) -> { };
    /** Called when a market NPC stops being one (death, zombification). */
    private Consumer<UUID> onLost = uuid -> { };

    public NitwitVillagerManager(Plugin plugin, Database db, long growthCheckTicks, double breedingNitwitChance) {
        this.plugin = plugin;
        this.db = db;
        this.marketKey = new NamespacedKey(plugin, "isMarketNPC");
        this.originBiomeKey = new NamespacedKey(plugin, "origin_biome");
        this.birthBiomeKey = new NamespacedKey(plugin, "birth_biome");
        this.growthCheckTicks = growthCheckTicks;
        this.breedingNitwitChance = breedingNitwitChance;
    }

    public void bindGuis(BiConsumer<Player, Villager> openBuy, BiConsumer<Player, Villager> openSell,
                         Consumer<UUID> onLost) {
        this.openBuy = openBuy;
        this.openSell = openSell;
        this.onLost = onLost;
    }

    public void start() {
        // Villagers already loaded (e.g. /reload or plugin enabled after worlds).
        Bukkit.getWorlds().forEach(world -> world.getEntitiesByClass(Villager.class).forEach(this::inspect));
        growthTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkGrowth, growthCheckTicks, growthCheckTicks);
    }

    public void stop() {
        if (growthTask != null) {
            growthTask.cancel();
        }
    }

    // ------------------------------------------------------------------
    // Tagging
    // ------------------------------------------------------------------

    public boolean isMarketNpc(Entity entity) {
        return entity instanceof Villager v
                && v.getPersistentDataContainer().getOrDefault(marketKey, PersistentDataType.BOOLEAN, false);
    }

    private static boolean isAdultNitwit(Villager v) {
        return v.isAdult() && v.getProfession() == Villager.Profession.NITWIT;
    }

    /** Tags adult nitwits, tracks babies. Safe to call many times. */
    private void inspect(Villager villager) {
        if (!villager.isValid() || isMarketNpc(villager)) {
            return;
        }
        if (!villager.isAdult()) {
            PersistentDataContainer pdc = villager.getPersistentDataContainer();
            if (!pdc.has(birthBiomeKey, PersistentDataType.STRING)) {
                pdc.set(birthBiomeKey, PersistentDataType.STRING, currentBiome(villager));
            }
            trackedBabies.add(villager.getUniqueId());
        } else if (isAdultNitwit(villager)) {
            tag(villager);
        }
    }

    private void tag(Villager villager) {
        PersistentDataContainer pdc = villager.getPersistentDataContainer();
        String origin = pdc.getOrDefault(birthBiomeKey, PersistentDataType.STRING, currentBiome(villager));
        pdc.set(marketKey, PersistentDataType.BOOLEAN, true);
        pdc.set(originBiomeKey, PersistentDataType.STRING, origin);
        pdc.remove(birthBiomeKey);
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO villager_instance(uuid, villager_type, origin_biome, world, tagged_at, status, ended_at)
                VALUES(?, ?, ?, ?, ?, 'alive', NULL)
                ON CONFLICT(uuid) DO UPDATE SET villager_type = excluded.villager_type, origin_biome = excluded.origin_biome,
                    world = excluded.world, tagged_at = excluded.tagged_at, status = 'alive', ended_at = NULL""")) {
            ps.setString(1, villager.getUniqueId().toString());
            ps.setString(2, MarketManager.groupOf(villager));
            ps.setString(3, origin);
            ps.setString(4, villager.getWorld().getName());
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "villager_instance : insertion impossible", e);
        }
    }

    private static String currentBiome(Villager villager) {
        return villager.getLocation().getBlock().getBiome().key().asString();
    }

    private void markEnded(UUID uuid, String status) {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE villager_instance SET status = ?, ended_at = ? WHERE uuid = ?")) {
            ps.setString(1, status);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "villager_instance : mise à jour impossible", e);
        }
        onLost.accept(uuid);
    }

    /** Baby → adult detection. Unloaded babies are dropped and re-tracked when their chunk loads. */
    private void checkGrowth() {
        Iterator<UUID> it = trackedBabies.iterator();
        while (it.hasNext()) {
            Entity entity = Bukkit.getEntity(it.next());
            if (!(entity instanceof Villager villager) || !villager.isValid()) {
                it.remove();
            } else if (villager.isAdult()) {
                it.remove();
                if (isAdultNitwit(villager)) {
                    tag(villager);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Villager villager)) {
            return;
        }
        // Profession / age are finalised right after the spawn event.
        boolean makeNitwit = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING
                && ThreadLocalRandom.current().nextDouble() < breedingNitwitChance;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (makeNitwit && villager.isValid() && villager.getProfession() == Villager.Profession.NONE) {
                // Vanilla Java never breeds nitwits; see villagers.breeding_nitwit_chance.
                villager.setProfession(Villager.Profession.NITWIT);
            }
            inspect(villager);
        });
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Villager villager) {
                inspect(villager);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCareerChange(VillagerCareerChangeEvent event) {
        Villager villager = event.getEntity();
        if (event.getProfession() == Villager.Profession.NITWIT) {
            Bukkit.getScheduler().runTask(plugin, () -> inspect(villager));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        Entity from = event.getEntity();
        if (isMarketNpc(from)) {
            // Zombification (or lightning → witch): this market access point is lost.
            markEnded(from.getUniqueId(), event.getTransformReason() == EntityTransformEvent.TransformReason.INFECTION
                    ? "zombified" : "transformed");
        }
        for (Entity to : event.getTransformedEntities()) {
            if (to instanceof Villager villager) {
                // Cured zombie villager: an adult nitwit again → regular rule applies.
                Bukkit.getScheduler().runTask(plugin, () -> inspect(villager));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        trackedBabies.remove(event.getEntity().getUniqueId());
        if (isMarketNpc(event.getEntity())) {
            markEnded(event.getEntity().getUniqueId(), "dead");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)) {
            return;
        }
        if (!isMarketNpc(villager)) {
            if (!isAdultNitwit(villager)) {
                return; // regular villager: vanilla behaviour
            }
            tag(villager); // safety net if an event was missed
        }
        Player player = event.getPlayer();
        Material held = player.getInventory().getItemInMainHand().getType();
        if (held == Material.NAME_TAG || held == Material.LEAD) {
            return; // keep vanilla naming / leashing
        }
        event.setCancelled(true);
        if (!player.hasPermission("vanillaeconomy.market")) {
            return;
        }
        if (player.isSneaking()) {
            openSell.accept(player, villager);
        } else {
            openBuy.accept(player, villager);
        }
    }

    public String originBiome(Villager villager) {
        return villager.getPersistentDataContainer().getOrDefault(originBiomeKey, PersistentDataType.STRING, "?");
    }
}
