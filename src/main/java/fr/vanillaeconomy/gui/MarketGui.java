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
 * default, ordered by category) on the top row(s), then a bottom row with the
 * balance, the time left before the next rotation and the button switching to the
 * other interface.
 * Items shown come from the rotation of the clicked villager's biome group; prices and
 * stock come from the global {@link MarketManager}.
 */
public abstract sealed class MarketGui implements InventoryHolder permits BuyGUI, SellGUI {

    /** Max distance between the player and the villager while trading. */
    private static final double MAX_DISTANCE_SQ = 8 * 8;
    /** custom_model_data of the navigation icons, see resourcepack/assets/minecraft/items/gold_nugget.json. */
    private static final int ICON_GO_TO_BUY = 1002;
    private static final int ICON_GO_TO_SELL = 1003;

    protected final MarketManager market;
    protected final CurrencyManager currency;
    protected final Player viewer;
    protected final UUID villagerId;
    protected final String group;
    private final Inventory inventory;
    private final int balanceSlot;
    private final int clockSlot;
    private final int toggleSlot;
    private List<Material> slots = List.of();

    protected MarketGui(MarketManager market, CurrencyManager currency, Player viewer, Villager villager, Component title) {
        this.market = market;
        this.currency = currency;
        this.viewer = viewer;
        this.villagerId = villager.getUniqueId();
        this.group = MarketManager.groupOf(villager);
        int entries = market.rotationFor(group).buy().size();
        int size = Math.min(54, ((entries + 8) / 9 + 1) * 9);
        this.balanceSlot = size - 9;
        this.clockSlot = size - 5;
        this.toggleSlot = size - 1;
        this.inventory = Bukkit.createInventory(this, size, title);
    }

    public abstract Side side();

    protected abstract ItemStack renderItem(Material material);

    /** Handles a click on a rotation slot; {@code bulk} = shift-click. */
    protected abstract void onItemClick(Material material, boolean bulk);

    protected abstract MarketGui opposite(Villager villager);

    /** Name and hint of the button leading to the other interface. */
    protected abstract String toggleName();

    protected abstract String toggleHint();

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
        ItemStack[] contents = new ItemStack[inventory.getSize()];
        java.util.Arrays.fill(contents, filler());
        for (int i = 0; i < slots.size() && i < balanceSlot; i++) {
            Material material = slots.get(i);
            if (material != null) {
                contents[i] = renderItem(material);
            }
        }
        contents[balanceSlot] = balanceItem();
        contents[clockSlot] = clockItem();
        contents[toggleSlot] = toggleButton();
        inventory.setContents(contents);
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
        ItemStack button = currency.icon(side() == Side.BUY ? ICON_GO_TO_SELL : ICON_GO_TO_BUY);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(Messages.item(toggleName()));
        meta.lore(List.of(Messages.item("<gray>" + toggleHint())));
        button.setItemMeta(meta);
        return button;
    }

    private ItemStack balanceItem() {
        ItemStack coin = currency.icon(currency.customModelData());
        ItemMeta meta = coin.getItemMeta();
        meta.displayName(Messages.item("<gold>Solde : <yellow><balance>", Messages.p("balance", Messages.coins(balance()))));
        coin.setItemMeta(meta);
        return coin;
    }

    private ItemStack clockItem() {
        ItemStack clock = ItemStack.of(Material.CLOCK);
        ItemMeta meta = clock.getItemMeta();
        meta.displayName(Messages.item("<aqua>Prochaine rotation : <white><time>",
                Messages.p("time", timeLeft(market.nextRotationAt() - System.currentTimeMillis()))));
        List<Component> lore = new ArrayList<>();
        lore.add(Messages.item("<gray>Les étals et les prix changent à chaque rotation."));
        if (market.promoPercent() > 0) {
            lore.add(Messages.item("<gold>✦ PROMO -<pct>% jusqu'à la prochaine rotation ✦",
                    Messages.p("pct", market.promoPercent())));
            meta.setEnchantmentGlintOverride(true);
        }
        meta.lore(lore);
        clock.setItemMeta(meta);
        return clock;
    }

    static String timeLeft(long millis) {
        long minutes = Math.max(0, millis) / 60_000L;
        if (minutes < 1) {
            return "moins d'une minute";
        }
        return minutes >= 60 ? (minutes / 60) + " h " + String.format("%02d", minutes % 60) + " min" : minutes + " min";
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

    /** Glint + "PROMO -X%" after the item name. */
    protected static ItemStack promoStyle(ItemStack stack, int percent) {
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.translatable(stack.translationKey(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .append(Messages.item(" <gold><bold>PROMO -<pct>%", Messages.p("pct", percent))));
        stack.setItemMeta(meta);
        return stack;
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
