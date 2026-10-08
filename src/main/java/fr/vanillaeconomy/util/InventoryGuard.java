package fr.vanillaeconomy.util;

import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * Read-only plugin GUIs (market, cityboard): the GUI itself can never be modified, but the
 * player keeps full use of his own inventory while it is open (move, split, swap, drop),
 * except the actions that would push items into the GUI or pull its items out.
 */
public final class InventoryGuard {

    private InventoryGuard() {
    }

    /**
     * Cancels what must not happen and tells whether the click targets the GUI (top inventory),
     * in which case the caller handles it as a button.
     */
    public static boolean guardClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return false; // outside the window: vanilla drop of the cursor item
        }
        if (!clicked.equals(top)) {
            InventoryAction action = event.getAction();
            // shift-click would move the item into the GUI, double-click would collect GUI items
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.COLLECT_TO_CURSOR) {
                event.setCancelled(true);
            }
            return false;
        }
        event.setCancelled(true);
        return true;
    }

    /** Dragging is only refused when it would drop items into the GUI. */
    public static void guardDrag(InventoryDragEvent event) {
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
