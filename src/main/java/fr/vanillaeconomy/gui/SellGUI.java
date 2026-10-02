package fr.vanillaeconomy.gui;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.market.MarketManager.ItemState;
import fr.vanillaeconomy.market.MarketManager.TradeResult;
import fr.vanillaeconomy.market.PricingEngine;
import fr.vanillaeconomy.market.RotationEngine.Side;
import fr.vanillaeconomy.util.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** VENTE interface: the player sells to the villager; feeds the global stock. */
public final class SellGUI extends MarketGui {

    public SellGUI(MarketManager market, CurrencyManager currency, Player viewer, Villager villager) {
        super(market, currency, viewer, villager, Messages.parse("<dark_red>Idiot du village — Vente"));
    }

    @Override
    public Side side() {
        return Side.SELL;
    }

    @Override
    protected String toggleLabel() {
        return "Acheter des objets à l'idiot →";
    }

    @Override
    protected MarketGui opposite(Villager villager) {
        return new BuyGUI(market, currency, viewer, villager);
    }

    @Override
    protected ItemStack renderItem(Material material) {
        ItemState s = market.state(material);
        int lot = PricingEngine.lotSize(s.buyUnit());
        int owned = MarketManager.countPlain(viewer, material);
        List<Component> lore = new ArrayList<>();
        lore.add(Messages.item("<gray>Rachat : <gold><price></gold> les <n>",
                Messages.p("price", Messages.coins(PricingEngine.total(s.buyUnit(), lot))), Messages.p("n", lot)));
        lore.add(Messages.item("<dark_gray><unit> pièce / unité", Messages.p("unit", Messages.unitPrice(s.buyUnit()))));
        lore.add(Messages.item("<gray>Vous en avez : <white><owned>", Messages.p("owned", owned)));
        lore.add(Component.empty());
        if (owned >= lot) {
            lore.add(Messages.item("<yellow>Clic : vendre <n>", Messages.p("n", lot)));
            lore.add(Messages.item("<yellow>Shift-clic : tout vendre (<n> → <price>)", Messages.p("n", owned),
                    Messages.p("price", Messages.coins(PricingEngine.total(s.buyUnit(), owned)))));
        } else {
            lore.add(Messages.item("<red>Il vous en faut au moins <n>", Messages.p("n", lot)));
        }
        return icon(material, lot, lore, false);
    }

    @Override
    protected void onItemClick(Material material, boolean bulk) {
        TradeResult r = market.sellToVillager(viewer, group, material, bulk);
        switch (r.status()) {
            case OK -> Messages.send(viewer, "<gray>Vendu <white><n></white> pour <gold><price></gold>.",
                    Messages.p("n", r.quantity()), Messages.p("price", Messages.coins(r.coins())));
            case NOT_ENOUGH_ITEMS -> Messages.send(viewer, "<red>Il vous en faut au moins <n> (objets non modifiés).",
                    Messages.p("n", r.lot()));
            case NOT_OFFERED -> Messages.send(viewer, "<red>Cet article n'est plus racheté.");
            case OUT_OF_STOCK, NOT_ENOUGH_MONEY, NO_SPACE, ERROR ->
                    Messages.send(viewer, "<red>Erreur interne, transaction annulée.");
        }
    }
}
