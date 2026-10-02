package fr.vanillaeconomy.gui;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.market.MarketManager.ItemState;
import fr.vanillaeconomy.market.MarketManager.TradeResult;
import fr.vanillaeconomy.market.PricingEngine;
import fr.vanillaeconomy.market.RotationEngine.Side;
import fr.vanillaeconomy.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** ACHAT interface: the villager sells to the player, from the global stock. */
public final class BuyGUI extends MarketGui {

    public BuyGUI(MarketManager market, CurrencyManager currency, Player viewer, Villager villager) {
        super(market, currency, viewer, villager, Messages.parse("<dark_green>Achat"));
    }

    @Override
    public Side side() {
        return Side.BUY;
    }

    @Override
    protected String toggleName() {
        return "<red><bold>Vente";
    }

    @Override
    protected String toggleHint() {
        return "Vendre vos objets à l'idiot";
    }

    @Override
    protected MarketGui opposite(Villager villager) {
        return new SellGUI(market, currency, viewer, villager);
    }

    @Override
    protected ItemStack renderItem(Material material) {
        ItemState s = market.state(material);
        int lot = PricingEngine.lotSize(s.sellUnit());
        int stack = material.getMaxStackSize();
        List<Component> lore = new ArrayList<>();
        if (s.promo()) {
            lore.add(Messages.item("<gray>Prix : <dark_gray><st><old></st></dark_gray> <gold><price></gold> les <n>",
                    Messages.p("old", Messages.coins(PricingEngine.total(s.regularSellUnit(), lot))),
                    Messages.p("price", Messages.coins(PricingEngine.total(s.sellUnit(), lot))), Messages.p("n", lot)));
        } else {
            lore.add(Messages.item("<gray>Prix : <gold><price></gold> les <n>",
                    Messages.p("price", Messages.coins(PricingEngine.total(s.sellUnit(), lot))), Messages.p("n", lot)));
        }
        lore.add(Messages.item("<dark_gray><unit> pièce / unité", Messages.p("unit", Messages.unitPrice(s.sellUnit()))));
        if (s.stock() <= 0) {
            lore.add(Messages.item("<red><bold>Rupture de stock"));
            return icon(material, 1, lore, true);
        }
        lore.add(Messages.item("<gray>Stock : <white><stock>", Messages.p("stock", s.stock())));
        lore.add(Component.empty());
        lore.add(Messages.item("<yellow>Clic : acheter <n>", Messages.p("n", Math.min(lot, s.stock()))));
        if (stack > lot) {
            long bulk = Math.min(stack, s.stock());
            lore.add(Messages.item("<yellow>Shift-clic : acheter <n> (<price>)", Messages.p("n", bulk),
                    Messages.p("price", Messages.coins(PricingEngine.total(s.sellUnit(), bulk)))));
        }
        ItemStack item = icon(material, lot, lore, false);
        return s.promo() ? promoStyle(item, s) : item;
    }

    /** Glint + "PROMO -25%" after the item name. */
    private static ItemStack promoStyle(ItemStack stack, ItemState s) {
        long percent = Math.round((1 - s.sellUnit() / s.regularSellUnit()) * 100);
        ItemMeta meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.displayName(Component.translatable(stack.translationKey(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .append(Messages.item(" <gold><bold>PROMO -<pct>%", Messages.p("pct", percent))));
        stack.setItemMeta(meta);
        return stack;
    }

    @Override
    protected void onItemClick(Material material, boolean bulk) {
        if (market.stockOf(material) <= 0) {
            return; // greyed out, not clickable
        }
        TradeResult r = market.buyFromVillager(viewer, group, material, bulk);
        switch (r.status()) {
            case OK -> Messages.send(viewer, "<gray>Acheté <white><n></white> pour <gold><price></gold>.",
                    Messages.p("n", r.quantity()), Messages.p("price", Messages.coins(r.coins())));
            case NOT_ENOUGH_MONEY -> Messages.send(viewer, "<red>Solde insuffisant (<price> requises).",
                    Messages.p("price", Messages.coins(r.coins())));
            case NO_SPACE -> Messages.send(viewer, "<red>Pas assez de place dans votre inventaire.");
            case OUT_OF_STOCK -> Messages.send(viewer, "<red>Rupture de stock.");
            case NOT_OFFERED -> Messages.send(viewer, "<red>Cet article n'est plus proposé.");
            case NOT_ENOUGH_ITEMS, ERROR -> Messages.send(viewer, "<red>Erreur interne, transaction annulée.");
        }
    }
}
