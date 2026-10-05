package fr.vanillaeconomy;

import fr.vanillaeconomy.city.BorderManager;
import fr.vanillaeconomy.city.CityAdminCommand;
import fr.vanillaeconomy.city.CityBoardGUI;
import fr.vanillaeconomy.city.CityCommand;
import fr.vanillaeconomy.city.CityConfig;
import fr.vanillaeconomy.city.CityManager;
import fr.vanillaeconomy.city.CityNotifier;
import fr.vanillaeconomy.city.CityPresenceListener;
import fr.vanillaeconomy.city.CityProtectionListener;
import fr.vanillaeconomy.command.EcoCommand;
import fr.vanillaeconomy.command.MarketAdminCommand;
import fr.vanillaeconomy.command.MoneyCommand;
import fr.vanillaeconomy.command.PayCommand;
import fr.vanillaeconomy.command.SoldeCommand;
import fr.vanillaeconomy.currency.CoinProtectionListener;
import fr.vanillaeconomy.discord.DiscordAnnouncer;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.gui.BuyGUI;
import fr.vanillaeconomy.gui.MarketGuiListener;
import fr.vanillaeconomy.gui.SellGUI;
import fr.vanillaeconomy.market.ItemConfigLoader;
import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.ServerTime;
import fr.vanillaeconomy.villager.NitwitVillagerManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Level;

public final class VanillaEconomyPlugin extends JavaPlugin {

    private Database database;
    private MarketManager market;
    private NitwitVillagerManager villagers;
    private BorderManager borders;
    private StaffLog staffLog;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "items.yml").exists()) {
            saveResource("items.yml", false);
        }
        FileConfiguration config = getConfig();
        ServerTime.configure(config.getString("timezone", "Europe/Paris"), getLogger());

        try {
            database = new Database(worldDatabaseFile());

            // 1. Currency
            CurrencyManager currency = new CurrencyManager(this, database);
            getServer().getPluginManager().registerEvents(new CoinProtectionListener(currency), this);
            getServer().getOnlinePlayers().forEach(p -> currency.ensureAccount(p.getUniqueId(), p.getName()));

            // 2. Commands
            register("solde", new SoldeCommand(currency, getLogger()));
            staffLog = new StaffLog(config.getConfigurationSection("discord"), getLogger());
            register("pay", new PayCommand(currency, getLogger(), config.getDouble("pay.max_distance", 10.0), staffLog));
            register("money", new MoneyCommand(currency, getLogger(),
                    config.getLong("currency.max_drop", 2304), config.getBoolean("currency.confiscate_invalid", true), staffLog));

            // 3. Global market (items.yml loaded as is)
            var items = ItemConfigLoader.load(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "items.yml")), getLogger());
            getLogger().info(items.items().size() + " items de marché chargés, " + items.totalSlots() + " slots par interface.");
            market = new MarketManager(this, database, items);
            market.load();
            market.start(20L * Math.max(1, config.getLong("market.check_interval_seconds", 60)));
            DiscordAnnouncer discord = new DiscordAnnouncer(config.getConfigurationSection("discord"), getLogger());
            if (discord.enabled()) {
                market.onUpcomingPromo((start, pct) ->
                        discord.announceUpcomingPromo(pct, start, market.intervalMillis(), market.zone()));
                getLogger().info("Annonces Discord des promotions activées (webhook).");
            }
            register("marketadmin", new MarketAdminCommand(market, discord, this, staffLog));
            register("eco", new EcoCommand(currency, getLogger(), staffLog));

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
            CityNotifier notifier = new CityNotifier(this, database, cityMessages);
            notifier.load();
            CityCommand cityCommand = new CityCommand(cities, cityMessages, notifier);
            register("city", cityCommand);
            register("cityadmin", new CityAdminCommand(cities, cityMessages, cityCommand, notifier, staffLog));
            cities.setAudit(staffLog::city);
            CityBoardGUI.Handler board = new CityBoardGUI.Handler(
                    new CityBoardGUI.Services(this, cities, currency, cityMessages, notifier));
            register("cityboard", board);
            borders = new BorderManager(this, cities, cityMessages);
            register("border", borders);
            CityPresenceListener presence = new CityPresenceListener(cities, cityMessages);
            var pm = getServer().getPluginManager();
            pm.registerEvents(new CityProtectionListener(cities, cityMessages), this);
            pm.registerEvents(presence, this);
            pm.registerEvents(board, this);
            pm.registerEvents(borders, this);
            pm.registerEvents(notifier, this);
            presence.start();
            borders.start();
            if (staffLog.enabled()) {
                getLogger().info("Journal staff Discord activé (webhook #logs-serveur).");
                staffLog.serverStarted("**Paper :** " + getServer().getVersion() + "\n**VanillaEconomy :** "
                        + getPluginMeta().getVersion() + "\n**Cités :** " + cities.all().size());
            }
        } catch (SQLException | IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "Initialisation impossible, plugin désactivé", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        MarketGuiListener.closeAll();
        if (staffLog != null) {
            staffLog.serverStopped();
        }
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

    /**
     * The economy belongs to the Minecraft world: its database lives in the main world
     * folder (level-name), so a new world starts a fresh economy and a restored world
     * comes back with its balances and cities. The database of former versions
     * (plugins/VanillaEconomy/economy.db) is moved into the current world once.
     */
    private File worldDatabaseFile() throws IOException {
        File dir = new File(saveFolder(getServer().getWorlds().getFirst().getWorldFolder()), "vanillaeconomy");
        Files.createDirectories(dir.toPath());
        File target = new File(dir, "economy.db");
        File legacy = new File(getDataFolder(), "economy.db");
        if (!target.exists() && legacy.exists()) {
            for (String suffix : new String[]{"", "-wal", "-shm"}) {
                Path from = Path.of(legacy.getPath() + suffix);
                if (Files.exists(from)) {
                    Files.move(from, Path.of(target.getPath() + suffix));
                }
            }
            getLogger().info("Données économiques déplacées dans le monde : " + target.getPath());
        }
        getLogger().info("Base de données de ce monde : " + target.getPath());
        return target;
    }

    /**
     * Root of the world save. Since 26.x the overworld folder is
     * {@code <level-name>/dimensions/minecraft/overworld}: the save is the parent of
     * "dimensions". Derived from the path (not from level.dat, which a brand-new world
     * only writes after the plugins are enabled).
     */
    private static File saveFolder(File worldFolder) {
        File folder = worldFolder.getAbsoluteFile().toPath().normalize().toFile();
        for (File f = folder; f != null; f = f.getParentFile()) {
            if (f.getName().equals("dimensions") && f.getParentFile() != null) {
                return f.getParentFile();
            }
        }
        return folder;
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
