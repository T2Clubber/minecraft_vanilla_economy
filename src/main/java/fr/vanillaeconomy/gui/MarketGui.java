package fr.vanillaeconomy.gui;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.market.RotationEngine.Side;
import fr.vanillaeconomy.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Common layout of the two market interfaces: one slot per rotation entry (8 by
 * default, ordered by category) followed by a button switching to the other interface.
 * Items shown come from the rotation of the clicked villager's biome group; prices and
 * stock come from the global {@link MarketManager}.
 */
public abstract sealed class MarketGui implements InventoryHolder permits BuyGUI, SellGUI {

    /** Max distance between the player and the villager while trading. */
    private static final double MAX_DISTANCE_SQ = 8 * 8;

    protected final MarketManager market;
    protected final CurrencyManager currency;
    protected final Player viewer;
    protected final UUID villagerId;
    protected final String group;
    private final Inventory inventory;
    private final int toggleSlot;
    private List<Material> slots = List.of();

    protected MarketGui(MarketManager market, CurrencyManager currency, Player viewer, Villager villager, Component title) {
        this.market = market;
        this.currency = currency;
        this.viewer = viewer;
        this.villagerId = villager.getUniqueId();
        this.group = MarketManager.groupOf(villager);
        int entries = market.rotationFor(group).buy().size();
        int size = Math.min(54, ((entries + 1 + 8) / 9) * 9);
        this.toggleSlot = size - 1;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public abstract Side side();

    protected abstract ItemStack renderItem(Material material);

    /** Handles a click on a rotation slot; {@code bulk} = shift-click. */
    protected abstract void onItemClick(Material material, boolean bulk);

    protected abstract MarketGui opposite(Villager villager);

    protected abstract String toggleLabel();

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public UUID villagerId() {
        return villagerId;
    }

    public void open() {
        render();
        viewer.openInventory(inventory);
    }

    public void render() {
        var rotation = market.rotationFor(group);
        slots = new ArrayList<>(side() == Side.SELL ? rotation.sell() : rotation.buy());
        inventory.clear();
        for (int i = 0; i < slots.size() && i < toggleSlot; i++) {
            Material material = slots.get(i);
            inventory.setItem(i, material == null ? filler() : renderItem(material));
        }
        inventory.setItem(toggleSlot, toggleButton());
    }

    /** Routes a click on the top inventory. */
    void click(int slot, boolean bulk) {
        Villager villager = villagerInReach();
        if (villager == null) {
            viewer.closeInventory();
            Messages.send(viewer, "<red>Le marchand n'est plus à portée.");
            return;
        }
        if (slot == toggleSlot) {
            opposite(villager).open();
            return;
        }
        if (slot >= 0 && slot < slots.size() && slots.get(slot) != null) {
            onItemClick(slots.get(slot), bulk);
            render();
        }
    }

    private Villager villagerInReach() {
        Entity entity = Bukkit.getEntity(villagerId);
        if (!(entity instanceof Villager villager) || !villager.isValid()
                || !villager.getWorld().equals(viewer.getWorld())
                || villager.getLocation().distanceSquared(viewer.getLocation()) > MAX_DISTANCE_SQ) {
            return null;
        }
        return villager;
    }

    private ItemStack toggleButton() {
        ItemStack button = ItemStack.of(side() == Side.BUY ? Material.CHEST : Material.EMERALD);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(Messages.item("<yellow>" + toggleLabel()));
        List<Component> lore = new ArrayList<>();
        lore.add(Messages.item("<gray>Solde : <gold><balance>", Messages.p("balance", Messages.coins(balance()))));
        long minutes = Math.max(0, (market.nextRotationAt() - System.currentTimeMillis()) / 60_000L);
        lore.add(Messages.item("<dark_gray>Nouvel étal dans <time>",
                Messages.p("time", minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min")));
        meta.lore(lore);
        button.setItemMeta(meta);
        return button;
    }

    protected long balance() {
        try {
            return currency.getBalance(viewer.getUniqueId());
        } catch (SQLException e) {
            return 0;
        }
    }

    private static ItemStack filler() {
        ItemStack pane = ItemStack.of(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        return pane;
    }

    /** Item icon with the given lore; {@code greyed} also greys out its name. */
    protected static ItemStack icon(Material material, int amount, List<Component> lore, boolean greyed) {
        ItemStack stack = ItemStack.of(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        ItemMeta meta = stack.getItemMeta();
        if (greyed) {
            meta.displayName(Component.translatable(stack.translationKey(), NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.STRIKETHROUGH, true));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
