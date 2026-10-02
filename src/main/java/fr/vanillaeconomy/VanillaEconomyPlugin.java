package fr.vanillaeconomy;

import fr.vanillaeconomy.city.BorderManager;
import fr.vanillaeconomy.city.CityAdminCommand;
import fr.vanillaeconomy.city.CityBoardGUI;
import fr.vanillaeconomy.city.CityCommand;
import fr.vanillaeconomy.city.CityConfig;
import fr.vanillaeconomy.city.CityManager;
import fr.vanillaeconomy.city.CityPresenceListener;
import fr.vanillaeconomy.city.CityProtectionListener;
import fr.vanillaeconomy.command.MarketAdminCommand;
import fr.vanillaeconomy.command.MoneyCommand;
import fr.vanillaeconomy.command.PayCommand;
import fr.vanillaeconomy.command.SoldeCommand;
import fr.vanillaeconomy.currency.CoinProtectionListener;
import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.gui.BuyGUI;
import fr.vanillaeconomy.gui.MarketGuiListener;
import fr.vanillaeconomy.gui.SellGUI;
import fr.vanillaeconomy.market.ItemConfigLoader;
import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.villager.NitwitVillagerManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.logging.Level;

public final class VanillaEconomyPlugin extends JavaPlugin {

    private Database database;
    private MarketManager market;
    private NitwitVillagerManager villagers;
    private BorderManager borders;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "items.yml").exists()) {
            saveResource("items.yml", false);
        }
        FileConfiguration config = getConfig();

        try {
            database = new Database(new File(getDataFolder(), "economy.db"));

            // 1. Currency
            CurrencyManager currency = new CurrencyManager(this, database);
            getServer().getPluginManager().registerEvents(new CoinProtectionListener(currency), this);
            getServer().getOnlinePlayers().forEach(p -> currency.ensureAccount(p.getUniqueId(), p.getName()));

            // 2. Commands
            register("solde", new SoldeCommand(currency, getLogger()));
            register("pay", new PayCommand(currency, getLogger(), config.getDouble("pay.max_distance", 10.0)));
            register("money", new MoneyCommand(currency, getLogger(),
                    config.getLong("currency.max_drop", 2304), config.getBoolean("currency.confiscate_invalid", true)));

            // 3. Global market (items.yml loaded as is)
            var items = ItemConfigLoader.load(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "items.yml")), getLogger());
            getLogger().info(items.items().size() + " items de marché chargés, " + items.totalSlots() + " slots par interface.");
            market = new MarketManager(this, database, items);
            market.load();
            market.start(20L * Math.max(1, config.getLong("market.check_interval_seconds", 60)));
            register("marketadmin", new MarketAdminCommand(market));

            // 4. Market villagers + GUIs
            villagers = new NitwitVillagerManager(this, database,
                    20L * Math.max(1, config.getLong("villagers.growth_check_seconds", 10)),
                    config.getDouble("villagers.breeding_nitwit_chance", 0.08));
            villagers.bindGuis(
                    (player, villager) -> new BuyGUI(market, currency, player, villager).open(),
                    (player, villager) -> new SellGUI(market, currency, player, villager).open(),
                    MarketGuiListener::closeFor);
            getServer().getPluginManager().registerEvents(villagers, this);
            getServer().getPluginManager().registerEvents(new MarketGuiListener(this), this);
            market.onChange(MarketGuiListener::refreshAll);
            market.onRotation(MarketGuiListener::closeAll);
            // keeps the "next rotation" clock of open GUIs up to date
            getServer().getScheduler().runTaskTimer(this, MarketGuiListener::refreshAll, 20L * 20, 20L * 20);
            villagers.start();

            // 5. Cities
            if (!new File(getDataFolder(), "cities.yml").exists()) {
                saveResource("cities.yml", false);
            }
            CityConfig cityConfig = new CityConfig(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "cities.yml")), getLogger());
            MessageConfig cityMessages = new MessageConfig(this, "cities");
            CityManager cities = new CityManager(this, database, cityConfig);
            cities.load();
            CityCommand cityCommand = new CityCommand(cities, cityMessages);
            register("city", cityCommand);
            register("cityadmin", new CityAdminCommand(cities, cityMessages, cityCommand));
            CityBoardGUI.Handler board = new CityBoardGUI.Handler(new CityBoardGUI.Services(this, cities, currency, cityMessages));
            register("cityboard", board);
            borders = new BorderManager(this, cities, cityMessages);
            register("border", borders);
            CityPresenceListener presence = new CityPresenceListener(cities, cityMessages);
            var pm = getServer().getPluginManager();
            pm.registerEvents(new CityProtectionListener(cities, cityMessages), this);
            pm.registerEvents(presence, this);
            pm.registerEvents(board, this);
            pm.registerEvents(borders, this);
            presence.start();
            borders.start();
        } catch (SQLException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Initialisation impossible, plugin désactivé", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        MarketGuiListener.closeAll();
        if (borders != null) {
            borders.stop();
        }
        if (villagers != null) {
            villagers.stop();
        }
        if (market != null) {
            market.stop();
        }
        if (database != null) {
            try {
                database.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Fermeture de la base impossible", e);
            }
        }
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            throw new IllegalStateException("Commande absente de plugin.yml : " + name);
        }
        command.setExecutor(executor);
        if (executor instanceof TabExecutor tab) {
            command.setTabCompleter(tab);
        }
    }
}
