package fr.vanillaeconomy.currency;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Coins can only live in plain storage. Any crafting / smelting / anvil / smithing /
 * grindstone / trading / brewing... inventory refuses them, and any recipe or
 * repair result involving a coin is cleared.
 */
public final class CoinProtectionListener implements Listener {

    /** Inventories where coins are allowed (pure storage). Everything else is blocked. */
    private static final Set<InventoryType> STORAGE = EnumSet.of(
            InventoryType.CHEST, InventoryType.ENDER_CHEST, InventoryType.SHULKER_BOX, InventoryType.BARREL,
            InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER, InventoryType.PLAYER,
            InventoryType.CREATIVE, InventoryType.SHELF, InventoryType.DECORATED_POT);

    private final CurrencyManager currency;

    public CoinProtectionListener(CurrencyManager currency) {
        this.currency = currency;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        currency.ensureAccount(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private boolean containsCoin(ItemStack[] items) {
        for (ItemStack item : items) {
            if (currency.isCoin(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlocked(Inventory inventory) {
        return inventory != null && !STORAGE.contains(inventory.getType());
    }

    // --- Recipe results ------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsCoin(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    /** Anvil, smithing table, grindstone, cartography, loom, stonecutter... */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareResult(PrepareResultEvent event) {
        if (containsCoin(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState() instanceof org.bukkit.block.Crafter crafter
                && containsCoin(crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceBurn(FurnaceBurnEvent event) {
        if (currency.isCoin(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    // --- Moving coins into forbidden inventories -----------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isBlocked(top)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        if (clicked.equals(top)) {
            boolean coinIncoming = currency.isCoin(event.getCursor())
                    || (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0
                        && currency.isCoin(event.getWhoClicked().getInventory().getItem(event.getHotbarButton())))
                    || (event.getClick() == ClickType.SWAP_OFFHAND
                        && currency.isCoin(event.getWhoClicked().getInventory().getItemInOffHand()));
            if (coinIncoming) {
                event.setCancelled(true);
            }
        } else if (event.isShiftClick() && top.getType() != InventoryType.CRAFTING && currency.isCoin(event.getCurrentItem())) {
            // Shift-click from the player inventory towards the blocked top inventory.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!isBlocked(top) || !currency.isCoin(event.getOldCursor())) {
            return;
        }
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Hoppers / droppers feeding furnaces, crafters, brewing stands... */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (isBlocked(event.getDestination()) && currency.isCoin(event.getItem())) {
            event.setCancelled(true);
        }
    }
}
