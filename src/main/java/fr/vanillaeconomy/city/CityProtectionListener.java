package fr.vanillaeconomy.city;

import fr.vanillaeconomy.util.MessageConfig;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Territory protection. Cities are never closed: anyone walks in freely, but non-members
 * are read-only. Exceptions: they may open / close doors and trapdoors (configurable list,
 * without ever using the item in hand) and they may always use market nitwits.
 *
 * <p>Handlers only use the in-memory spatial index and member maps (no database access).
 */
public final class CityProtectionListener implements Listener {

    public static final String BYPASS = "cities.admin.bypass";
    private static final long FEEDBACK_COOLDOWN_MS = 1000;

    private final CityManager cities;
    private final CityConfig config;
    private final MessageConfig msg;
    private final Map<UUID, Long> lastFeedback = new HashMap<>();

    public CityProtectionListener(CityManager cities, MessageConfig msg) {
        this.cities = cities;
        this.config = cities.config();
        this.msg = msg;
    }

    /**
     * City where {@code player} is a visitor at {@code loc}, or null when he may act there
     * (outside any city, member of that city, or admin bypass).
     */
    private City visitorCity(Player player, Location loc) {
        City city = cities.at(loc);
        if (city == null || city.isMember(player.getUniqueId()) || player.hasPermission(BYPASS)) {
            return null;
        }
        return city;
    }

    private boolean deny(Player player, Location loc) {
        City city = visitorCity(player, loc);
        if (city == null) {
            return false;
        }
        feedback(player, city);
        return true;
    }

    private void feedback(Player player, City city) {
        if (!config.actionbarMessage) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastFeedback.get(player.getUniqueId());
        if (last == null || now - last >= FEEDBACK_COOLDOWN_MS) {
            lastFeedback.put(player.getUniqueId(), now);
            msg.actionBar(player, "denied", "city", city.name());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastFeedback.remove(event.getPlayer().getUniqueId());
    }

    /** The player behind a direct hit, a projectile, or null. */
    private static Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player p) {
            return p;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (deny(event.getPlayer(), event.getBlockPlaced().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (deny(event.getPlayer(), event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (deny(event.getPlayer(), event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (deny(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            if (deny(player, event.getBlock().getLocation())) {
                event.setCancelled(true);
            }
        } else if (cities.at(event.getBlock().getLocation()) != null) {
            // Fire spread, lava, fireballs, lightning... never ignite a city.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (cities.at(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        Material source = event.getSource().getType();
        if ((source == Material.FIRE || source == Material.SOUL_FIRE) && cities.at(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLectern(PlayerTakeLecternBookEvent event) {
        if (deny(event.getPlayer(), event.getLectern().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Interactions with blocks
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Player player = event.getPlayer();
        City city = visitorCity(player, block.getLocation());
        if (city == null) {
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && CityRules.visitorMayInteract(block.getType(), config.visitorInteractions)) {
            // Doors / trapdoors: the block may be used, never the item in hand (no placing, no bucket...).
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }
        // Containers, buttons, levers, pressure plates (PHYSICAL), farmland, signs, note blocks...
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getAction() != Action.PHYSICAL) {
            feedback(player, city);
        }
    }

    /** Arrows / tridents of a visitor pressing buttons or plates. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        Player shooter = responsiblePlayer(event.getEntity());
        if (shooter != null && event.getEntity() instanceof Projectile
                && visitorCity(shooter, event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Entities: frames, armor stands, vehicles, animals
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        // Villagers (market nitwits included) stay usable by everyone, as well as players.
        if (target instanceof AbstractVillager || target instanceof Player) {
            return;
        }
        if (deny(event.getPlayer(), target.getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (deny(event.getPlayer(), event.getRightClicked().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && deny(event.getPlayer(), event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        Location loc = event.getEntity().getLocation();
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            if (cities.at(loc) != null) {
                event.setCancelled(true);
            }
            return;
        }
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            Player player = responsiblePlayer(byEntity.getRemover());
            if (player != null && deny(player, loc)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        Player attacker = responsiblePlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        boolean protectedEntity = victim instanceof Hanging || victim instanceof ArmorStand || victim instanceof Vehicle
                || (config.protectAnimals && (victim instanceof Animals || victim instanceof AbstractVillager
                    || victim instanceof WaterMob || victim instanceof Allay));
        if (protectedEntity && deny(attacker, victim.getLocation())) {
            event.setCancelled(true);
        }
    }

    /** Explosions never damage armor stands inside a city. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if ((cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION)
                && (event.getEntity() instanceof ArmorStand || event.getEntity() instanceof Hanging)
                && cities.at(event.getEntity().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Player attacker = event.getAttacker() == null ? null : responsiblePlayer(event.getAttacker());
        if (attacker != null && deny(attacker, event.getVehicle().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        Player attacker = event.getAttacker() == null ? null : responsiblePlayer(event.getAttacker());
        if (attacker != null && deny(attacker, event.getVehicle().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && deny(player, event.getVehicle().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> cities.at(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> cities.at(b.getLocation()) != null);
    }

    /** Endermen, ravagers, withers, zombies breaking doors... (falling sand stays allowed). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMobGrief(EntityChangeBlockEvent event) {
        if (event.getEntity() instanceof Enemy && cities.at(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    /** Fluids coming from outside (or from another city) stop at the border. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        City to = cities.at(event.getToBlock().getLocation());
        if (to != null && to != cities.at(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (crossesBorder(event.getBlock(), event.getBlocks(), event.getDirection(), true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (crossesBorder(event.getBlock(), event.getBlocks(), event.getDirection(), false)) {
            event.setCancelled(true);
        }
    }

    private boolean crossesBorder(Block piston, List<Block> moved, BlockFace direction, boolean extending) {
        City home = cities.at(piston.getLocation());
        if (extending && cities.at(piston.getRelative(direction).getLocation()) != home) {
            return true; // the piston head itself
        }
        for (Block block : moved) {
            if (cities.at(block.getLocation()) != home || cities.at(block.getRelative(direction).getLocation()) != home) {
                return true;
            }
        }
        return false;
    }

    /** Dispensers outside a city cannot pour, place or fire into it. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Directional directional)) {
            return;
        }
        City target = cities.at(event.getBlock().getRelative(directional.getFacing()).getLocation());
        if (target != null && target != cities.at(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }
}
