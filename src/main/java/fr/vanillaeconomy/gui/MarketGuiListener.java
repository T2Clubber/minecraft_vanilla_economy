package fr.vanillaeconomy.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.function.BiConsumer;

/** Market GUIs are read-only views: every click is cancelled and routed to the GUI. */
public final class MarketGuiListener implements Listener {

    private final Plugin plugin;

    public MarketGuiListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MarketGui gui)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(gui.getInventory())) {
            return;
        }
        boolean bulk;
        ClickType click = event.getClick();
        if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            bulk = false;
        } else if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            bulk = true;
        } else {
            return;
        }
        int slot = event.getSlot();
        // Opening / closing inventories must not happen inside the click event itself.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.getWhoClicked().getOpenInventory().getTopInventory().getHolder(false) == gui) {
                gui.click(slot, bulk);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MarketGui) {
            event.setCancelled(true);
        }
    }

    /** Re-renders every open market GUI (prices / stock / balances changed). */
    public static void refreshAll() {
        forEachOpen((player, gui) -> gui.render());
    }

    /** Closes every open market GUI (new rotation). */
    public static void closeAll() {
        forEachOpen((player, gui) -> player.closeInventory());
    }

    /** Closes GUIs bound to a villager that is no longer a market NPC. */
    public static void closeFor(UUID villagerId) {
        forEachOpen((player, gui) -> {
            if (gui.villagerId().equals(villagerId)) {
                player.closeInventory();
            }
        });
    }

    private static void forEachOpen(BiConsumer<Player, MarketGui> action) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof MarketGui gui) {
                action.accept(player, gui);
            }
        }
    }
}
