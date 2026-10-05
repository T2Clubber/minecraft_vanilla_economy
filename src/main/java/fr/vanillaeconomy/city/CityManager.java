package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.Tier;
import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.Messages;
import fr.vanillaeconomy.util.Players;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Cities: CRUD, business rules and the in-memory spatial index. The database is only
 * written inside transactions; memory is updated after a successful commit, so event
 * handlers can rely on memory alone. All money moves go through the same transaction
 * as the {@link CurrencyManager} balance update.
 */
public final class CityManager {

    private final Plugin plugin;
    private final Database db;
    private final CityConfig config;
    private final Map<Integer, City> byId = new HashMap<>();
    private final Map<String, City> byName = new HashMap<>();
    private final Map<UUID, City> byOwner = new HashMap<>();
    private final SpatialIndex index = new SpatialIndex();
    /** Territory changed (created, expanded): presence and borders must be refreshed. */
    private final List<Consumer<City>> territoryListeners = new ArrayList<>();
    /** City removed (disbanded, deleted). */
    private final List<Consumer<City>> removalListeners = new ArrayList<>();

    public CityManager(Plugin plugin, Database db, CityConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
    }

    public CityConfig config() {
        return config;
    }

    public void onTerritoryChange(Consumer<City> listener) {
        territoryListeners.add(listener);
    }

    public void onRemoval(Consumer<City> listener) {
        removalListeners.add(listener);
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    public void load() throws SQLException {
        Connection c = db.connection();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, name, owner_uuid, world, center_x, center_z, tier, balance, created_at FROM city")) {
            while (rs.next()) {
                int tier = Math.min(rs.getInt(7), config.maxTier());
                City city = new City(rs.getInt(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4),
                        rs.getInt(5), rs.getInt(6), tier, rs.getLong(8), rs.getLong(9), config.tier(tier).size());
                byId.put(city.id(), city);
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT city_id, player_uuid, role FROM city_member")) {
            while (rs.next()) {
                City city = byId.get(rs.getInt(1));
                if (city != null) {
                    city.putMember(UUID.fromString(rs.getString(2)), CityRole.valueOf(rs.getString(3)));
                }
            }
        }
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT city_id, player_uuid, amount, timestamp FROM city_contribution ORDER BY timestamp, id")) {
            while (rs.next()) {
                City city = byId.get(rs.getInt(1));
                if (city != null) {
                    city.addContribution(new City.Contribution(UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getLong(4)));
                }
            }
        }
        for (City city : byId.values()) {
            city.putMember(city.owner(), CityRole.OWNER);
            indexAdd(city);
        }
        plugin.getLogger().info(byId.size() + " cité(s) chargée(s).");
    }

    private void indexAdd(City city) {
        byName.put(city.name().toLowerCase(Locale.ROOT), city);
        byOwner.put(city.owner(), city);
        index.add(city);
    }

    private void indexRemove(City city) {
        byName.remove(city.name().toLowerCase(Locale.ROOT));
        byOwner.remove(city.owner());
        index.remove(city);
        byId.remove(city.id());
    }

    // ------------------------------------------------------------------
    // Queries (memory only)
    // ------------------------------------------------------------------

    public City at(Location loc) {
        return loc.getWorld() == null ? null : index.at(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    public City at(String world, int x, int z) {
        return index.at(world, x, z);
    }

    public Optional<City> byName(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public City byId(int id) {
        return byId.get(id);
    }

    public Optional<City> ownedBy(UUID player) {
        return Optional.ofNullable(byOwner.get(player));
    }

    public Collection<City> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public List<City> citiesOf(UUID player) {
        return byId.values().stream().filter(c -> c.isMember(player)).toList();
    }

    /** Player name (first characters of the UUID if the server has never seen him). */
    public static String nameOf(UUID player) {
        return Players.name(player);
    }

    public City require(String name) throws CityException {
        return byName(name).orElseThrow(() -> new CityException("unknown_city", "city", name));
    }

    private static CityRole roleOrThrow(City city, UUID player) throws CityException {
        CityRole role = city.roleOf(player);
        if (role == null) {
            throw new CityException("not_member", "city", city.name());
        }
        return role;
    }

    // ------------------------------------------------------------------
    // Creation
    // ------------------------------------------------------------------

    public City create(Player founder, String name) throws CityException {
        Location loc = founder.getLocation();
        String world = loc.getWorld().getName();
        if (!config.allowedWorlds.contains(world)) {
            throw new CityException("create_world_denied");
        }
        City owned = byOwner.get(founder.getUniqueId());
        if (owned != null) {
            throw new CityException("create_already_owner", "city", owned.name());
        }
        if (!CityRules.validName(name, config.nameMin, config.nameMax)) {
            throw new CityException("create_invalid_name", "min", config.nameMin, "max", config.nameMax);
        }
        if (byName.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new CityException("create_name_taken", "city", name);
        }
        Tier first = config.tier(1);
        Territory territory = new Territory(world, loc.getBlockX(), loc.getBlockZ(), first.size());
        City conflict = conflictWith(territory, null);
        if (conflict != null) {
            throw new CityException("create_conflict", "city", conflict.name(), "gap", config.minGap);
        }
        UUID owner = founder.getUniqueId();
        long now = System.currentTimeMillis();
        int id;
        try {
            id = db.transaction(c -> {
                if (!CurrencyManager.debit(c, owner, config.creationCost)) {
                    return -1;
                }
                int newId;
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO city(name, owner_uuid, world, center_x, center_z, tier, balance, created_at)
                        VALUES(?, ?, ?, ?, ?, 1, 0, ?)""", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, name);
                    ps.setString(2, owner.toString());
                    ps.setString(3, world);
                    ps.setInt(4, territory.centerX());
                    ps.setInt(5, territory.centerZ());
                    ps.setLong(6, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        newId = keys.getInt(1);
                    }
                }
                upsertMember(c, newId, owner, CityRole.OWNER);
                return newId;
            });
        } catch (SQLException e) {
            throw internal("création de la cité " + name, e);
        }
        if (id < 0) {
            throw new CityException("insufficient_funds", "amount", Messages.coins(config.creationCost));
        }
        City city = new City(id, name, owner, world, territory.centerX(), territory.centerZ(), 1, 0, now, first.size());
        city.putMember(owner, CityRole.OWNER);
        byId.put(id, city);
        indexAdd(city);
        territoryListeners.forEach(l -> l.accept(city));
        return city;
    }

    /** First city (other than {@code self}) too close to {@code territory}, or null. */
    private City conflictWith(Territory territory, City self) {
        for (City other : byId.values()) {
            if (other != self && territory.conflicts(other.territory(), config.minGap)) {
                return other;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Members
    // ------------------------------------------------------------------

    /**
     * The city an actor manages for /city add|kick: the one given by name, else the one he
     * owns, else his only co-owned city.
     */
    public City managedCity(UUID actor, String explicitName) throws CityException {
        if (explicitName != null) {
            City city = require(explicitName);
            CityRole role = roleOrThrow(city, actor);
            if (!role.canAddMembers()) {
                throw new CityException("no_permission_role", "city", city.name());
            }
            return city;
        }
        City owned = byOwner.get(actor);
        if (owned != null) {
            return owned;
        }
        List<City> coOwned = byId.values().stream().filter(c -> c.roleOf(actor) == CityRole.CO_OWNER).toList();
        if (coOwned.isEmpty()) {
            throw new CityException("no_managed_city");
        }
        if (coOwned.size() > 1) {
            throw new CityException("ambiguous_city");
        }
        return coOwned.getFirst();
    }

    public void addMember(City city, UUID actor, UUID target) throws CityException {
        CityRole role = roleOrThrow(city, actor);
        if (!role.canAddMembers()) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        if (city.isMember(target)) {
            throw new CityException("add_already_member", "player", nameOf(target), "city", city.name());
        }
        write("ajout d'un membre à " + city.name(), c -> upsertMember(c, city.id(), target, CityRole.MEMBER));
        city.putMember(target, CityRole.MEMBER);
    }

    public void kick(City city, UUID actor, UUID target) throws CityException {
        CityRole role = roleOrThrow(city, actor);
        CityRole targetRole = city.roleOf(target);
        if (targetRole == null) {
            throw new CityException("kick_not_member", "player", nameOf(target), "city", city.name());
        }
        if (!role.canKick(targetRole)) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        write("exclusion d'un membre de " + city.name(), c -> deleteMember(c, city.id(), target));
        city.removeMember(target);
    }

    public void leave(City city, UUID player) throws CityException {
        CityRole role = roleOrThrow(city, player);
        if (!role.canLeave()) {
            throw new CityException("leave_owner");
        }
        write("départ d'un membre de " + city.name(), c -> deleteMember(c, city.id(), player));
        city.removeMember(player);
    }

    public void setCoOwner(City city, UUID actor, UUID target, boolean coOwner) throws CityException {
        CityRole role = roleOrThrow(city, actor);
        CityRole targetRole = city.roleOf(target);
        if (targetRole == null) {
            throw new CityException("kick_not_member", "player", nameOf(target), "city", city.name());
        }
        if (!role.canSetCoOwner(targetRole)) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        CityRole newRole = coOwner ? CityRole.CO_OWNER : CityRole.MEMBER;
        write("changement de rôle dans " + city.name(), c -> upsertMember(c, city.id(), target, newRole));
        city.putMember(target, newRole);
    }

    // ------------------------------------------------------------------
    // Treasury
    // ------------------------------------------------------------------

    public void contribute(City city, UUID player, long amount) throws CityException {
        roleOrThrow(city, player);
        if (amount <= 0) {
            throw new CityException("invalid_amount");
        }
        long now = System.currentTimeMillis();
        boolean ok;
        try {
            ok = db.transaction(c -> {
                if (!CurrencyManager.debit(c, player, amount)) {
                    return false;
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE city SET balance = balance + ? WHERE id = ?")) {
                    ps.setLong(1, amount);
                    ps.setInt(2, city.id());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO city_contribution(city_id, player_uuid, amount, timestamp) VALUES(?, ?, ?, ?)")) {
                    ps.setInt(1, city.id());
                    ps.setString(2, player.toString());
                    ps.setLong(3, amount);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
                return true;
            });
        } catch (SQLException e) {
            throw internal("contribution à " + city.name(), e);
        }
        if (!ok) {
            throw new CityException("insufficient_funds", "amount", Messages.coins(amount));
        }
        city.setBalance(city.balance() + amount);
        city.addContribution(new City.Contribution(player, amount, now));
    }

    // ------------------------------------------------------------------
    // Tiers
    // ------------------------------------------------------------------

    /** Checks that the owner can buy the next tier now; returns it. */
    public Tier checkUpgrade(City city, UUID actor) throws CityException {
        if (!roleOrThrow(city, actor).canBuyTier()) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        Tier next = CityRules.nextTier(config.tiers, city.tier())
                .orElseThrow(() -> new CityException("upgrade_max", "city", city.name()));
        if (city.balance() < next.price()) {
            throw new CityException("upgrade_funds", "price", Messages.coins(next.price()),
                    "balance", Messages.coins(city.balance()));
        }
        City conflict = conflictWith(city.territory().withSize(next.size()), city);
        if (conflict != null) {
            throw new CityException("upgrade_conflict", "other", conflict.name());
        }
        return next;
    }

    public Tier upgrade(City city, UUID actor) throws CityException {
        Tier next = checkUpgrade(city, actor);
        boolean ok;
        try {
            ok = db.transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE city SET balance = balance - ?, tier = ? WHERE id = ? AND tier = ? AND balance >= ?")) {
                    ps.setLong(1, next.price());
                    ps.setInt(2, next.level());
                    ps.setInt(3, city.id());
                    ps.setInt(4, city.tier());
                    ps.setLong(5, next.price());
                    return ps.executeUpdate() == 1;
                }
            });
        } catch (SQLException e) {
            throw internal("achat de palier pour " + city.name(), e);
        }
        if (!ok) {
            throw new CityException("upgrade_funds", "price", Messages.coins(next.price()),
                    "balance", Messages.coins(city.balance()));
        }
        index.remove(city);
        city.setBalance(city.balance() - next.price());
        city.setTier(next.level(), next.size());
        index.add(city);
        territoryListeners.forEach(l -> l.accept(city));
        return next;
    }

    // ------------------------------------------------------------------
    // Disband / admin
    // ------------------------------------------------------------------

    /** Owner disband: refunds the balance prorata of contributions. Returns the refunds. */
    public Map<UUID, Long> disband(City city, UUID actor) throws CityException {
        if (!roleOrThrow(city, actor).canDisband()) {
            throw new CityException("no_permission_role", "city", city.name());
        }
        return delete(city);
    }

    public Map<UUID, Long> delete(City city) throws CityException {
        Map<UUID, Long> refunds = CityRules.refunds(city.contributionTotals(), city.balance(), city.owner());
        write("suppression de " + city.name(), c -> {
            for (var e : refunds.entrySet()) {
                CurrencyManager.credit(c, e.getKey(), e.getValue());
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM city WHERE id = ?")) {
                ps.setInt(1, city.id());
                ps.executeUpdate();
            }
        });
        indexRemove(city);
        removalListeners.forEach(l -> l.accept(city));
        return refunds;
    }

    public void adminSetTier(City city, int level) throws CityException {
        Tier tier = config.tiers.stream().filter(t -> t.level() == level).findFirst()
                .orElseThrow(() -> new CityException("upgrade_max", "city", city.name()));
        City conflict = conflictWith(city.territory().withSize(tier.size()), city);
        if (conflict != null) {
            throw new CityException("upgrade_conflict", "other", conflict.name());
        }
        write("changement de palier de " + city.name(), c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE city SET tier = ? WHERE id = ?")) {
                ps.setInt(1, level);
                ps.setInt(2, city.id());
                ps.executeUpdate();
            }
        });
        index.remove(city);
        city.setTier(level, tier.size());
        index.add(city);
        territoryListeners.forEach(l -> l.accept(city));
    }

    public void adminRename(City city, String newName) throws CityException {
        if (!CityRules.validName(newName, config.nameMin, config.nameMax)) {
            throw new CityException("create_invalid_name", "min", config.nameMin, "max", config.nameMax);
        }
        City existing = byName.get(newName.toLowerCase(Locale.ROOT));
        if (existing != null && existing != city) {
            throw new CityException("create_name_taken", "city", newName);
        }
        write("renommage de " + city.name(), c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE city SET name = ? WHERE id = ?")) {
                ps.setString(1, newName);
                ps.setInt(2, city.id());
                ps.executeUpdate();
            }
        });
        byName.remove(city.name().toLowerCase(Locale.ROOT));
        city.rename(newName);
        byName.put(newName.toLowerCase(Locale.ROOT), city);
    }

    // ------------------------------------------------------------------
    // SQL helpers
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlStep {
        void run(Connection c) throws SQLException;
    }

    private void write(String what, SqlStep step) throws CityException {
        try {
            db.transaction(c -> {
                step.run(c);
                return null;
            });
        } catch (SQLException e) {
            throw internal(what, e);
        }
    }

    private CityException internal(String what, SQLException e) {
        plugin.getLogger().log(Level.SEVERE, "Cités : échec de " + what, e);
        return new CityException("internal_error");
    }

    private static void upsertMember(Connection c, int cityId, UUID player, CityRole role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO city_member(city_id, player_uuid, role) VALUES(?, ?, ?)
                ON CONFLICT(city_id, player_uuid) DO UPDATE SET role = excluded.role""")) {
            ps.setInt(1, cityId);
            ps.setString(2, player.toString());
            ps.setString(3, role.name());
            ps.executeUpdate();
        }
    }

    private static void deleteMember(Connection c, int cityId, UUID player) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM city_member WHERE city_id = ? AND player_uuid = ?")) {
            ps.setInt(1, cityId);
            ps.setString(2, player.toString());
            ps.executeUpdate();
        }
    }
}
