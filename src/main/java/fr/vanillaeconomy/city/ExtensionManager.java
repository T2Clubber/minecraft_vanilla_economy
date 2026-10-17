package fr.vanillaeconomy.city;

import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * City extensions: loading (table city_extension), purchase conditions and purchase with
 * the city's treasury. Promotion en folie also needs the 7 nitwit skins inside the city,
 * checked by an asynchronous scan ({@link NitwitPresenceChecker}) at the time of purchase.
 */
public final class ExtensionManager {

    private final Plugin plugin;
    private final Database db;
    private final CityManager cities;
    private final NitwitPresenceChecker presence;
    private final MessageConfig msg;
    private final List<BiConsumer<City, CityExtension>> purchaseListeners = new ArrayList<>();
    /** Cities whose skin scan is running (one at a time per city). */
    private final Set<Integer> scanning = new java.util.HashSet<>();

    public ExtensionManager(Plugin plugin, Database db, CityManager cities, NitwitPresenceChecker presence,
                            MessageConfig msg) {
        this.plugin = plugin;
        this.db = db;
        this.cities = cities;
        this.presence = presence;
        this.msg = msg;
    }

    public CityConfig config() {
        return cities.config();
    }

    public void onPurchase(BiConsumer<City, CityExtension> listener) {
        purchaseListeners.add(listener);
    }

    /** Bought and not disabled by the administration. */
    public boolean active(City city, CityExtension extension) {
        return city != null && config().enabled(extension) && city.hasExtension(extension);
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    public void load() throws SQLException {
        loadAll(db.connection()).forEach((cityId, extensions) -> {
            City city = cities.byId(cityId);
            if (city != null) {
                extensions.forEach(city::addExtension);
            }
        });
    }

    /** City id -> extensions; unknown keys (extension removed from the plugin) are ignored. */
    static Map<Integer, Set<CityExtension>> loadAll(Connection c) throws SQLException {
        Map<Integer, Set<CityExtension>> all = new HashMap<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT city_id, extension FROM city_extension")) {
            while (rs.next()) {
                int cityId = rs.getInt(1);
                CityExtension.byKey(rs.getString(2)).ifPresent(e ->
                        all.computeIfAbsent(cityId, k -> EnumSet.noneOf(CityExtension.class)).add(e));
            }
        }
        return all;
    }

    // ------------------------------------------------------------------
    // Purchase
    // ------------------------------------------------------------------

    /**
     * Conditions that need no scan: enabled, role, not owned yet, tier, treasury. The skins
     * of Promotion en folie are checked by {@link #buy}.
     */
    public void check(City city, UUID actor, CityExtension extension) throws CityException {
        CityConfig config = config();
        if (!config.enabled(extension)) {
            throw new CityException("extension_disabled", "extension", extension.label());
        }
        if (!CityManager.roleOrThrow(city, actor).canBuyExtension()) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        if (city.hasExtension(extension)) {
            throw new CityException("extension_owned", "city", city.name(), "extension", extension.label());
        }
        if (city.tier() < config.minTier(extension)) {
            throw new CityException("extension_tier", "extension", extension.label(), "city", city.name(),
                    "tier", config.minTier(extension), "current", city.tier());
        }
        long price = config.price(extension);
        if (city.balance() < price) {
            throw new CityException("upgrade_funds", "price", Messages.coins(price),
                    "balance", Messages.coins(city.balance()));
        }
    }

    /**
     * Buys the extension: every condition is checked again, the skins of Promotion en folie
     * by a fresh scan. Exactly one of the callbacks runs, on the main thread.
     */
    public void buy(City city, UUID actor, CityExtension extension, Runnable onSuccess, Consumer<CityException> onFailure) {
        try {
            check(city, actor, extension);
        } catch (CityException e) {
            onFailure.accept(e);
            return;
        }
        if (extension != CityExtension.PROMO_FRENZY) {
            complete(city, actor, extension, onSuccess, onFailure);
            return;
        }
        scanTypes(city, missing -> {
            if (!missing.isEmpty()) {
                onFailure.accept(new CityException("extension_skins_missing", "city", city.name(),
                        "missing", labels(missing)));
                return;
            }
            complete(city, actor, extension, onSuccess, onFailure);
        }, onFailure);
    }

    /** "Vérifier les skins": missing types of Promotion en folie (empty list = all present). */
    public void scanTypes(City city, Consumer<List<String>> onResult, Consumer<CityException> onFailure) {
        if (!scanning.add(city.id())) {
            onFailure.accept(new CityException("extension_scan_running"));
            return;
        }
        presence.scan(city).whenComplete((present, error) -> {
            scanning.remove(city.id());
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Vérification des skins de " + city.name() + " impossible", error);
                onFailure.accept(new CityException("extension_scan_failed", "city", city.name()));
                return;
            }
            onResult.accept(CityRules.missingTypes(config().frenzyTypes, present));
        });
    }

    private void complete(City city, UUID actor, CityExtension extension, Runnable onSuccess,
                          Consumer<CityException> onFailure) {
        try {
            // The city may have changed during the scan (tier, treasury, owner, disbanded).
            if (cities.byId(city.id()) != city) {
                throw new CityException("unknown_city", "city", city.name());
            }
            check(city, actor, extension);
            pay(city, actor, extension);
        } catch (CityException e) {
            onFailure.accept(e);
            return;
        }
        purchaseListeners.forEach(l -> l.accept(city, extension));
        onSuccess.run();
    }

    private void pay(City city, UUID actor, CityExtension extension) throws CityException {
        long price = config().price(extension);
        boolean ok;
        try {
            ok = db.transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE city SET balance = balance - ? WHERE id = ? AND balance >= ?")) {
                    ps.setLong(1, price);
                    ps.setInt(2, city.id());
                    ps.setLong(3, price);
                    if (ps.executeUpdate() != 1) {
                        return false;
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO city_extension(city_id, extension, bought_at) VALUES(?, ?, ?)")) {
                    ps.setInt(1, city.id());
                    ps.setString(2, extension.key());
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Cités : échec de l'achat de " + extension.key() + " pour " + city.name(), e);
            throw new CityException("internal_error");
        }
        if (!ok) {
            throw new CityException("upgrade_funds", "price", Messages.coins(price),
                    "balance", Messages.coins(city.balance()));
        }
        city.setBalance(city.balance() - price);
        city.addExtension(extension);
        cities.audit("Extension : " + city.name(), "**" + extension.label() + "** achetée par "
                + CityManager.nameOf(actor) + " pour " + price + " pièces\n**Solde restant :** " + city.balance() + " pièces");
    }

    /** "désert, savane": French names from messages.yml (villager_type_&lt;key&gt;), else the key. */
    public String labels(List<String> types) {
        return String.join(", ", types.stream().map(this::typeLabel).toList());
    }

    public String typeLabel(String type) {
        return msg.text("villager_type_" + type, type);
    }
}
