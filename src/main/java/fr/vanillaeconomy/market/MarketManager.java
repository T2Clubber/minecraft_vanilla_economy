package fr.vanillaeconomy.market;

import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.market.ItemConfigLoader.ItemConfig;
import fr.vanillaeconomy.market.RotationEngine.Rotation;
import fr.vanillaeconomy.market.RotationEngine.Side;
import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.Messages;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * The single, server-wide market state: prices, stock, circulation counters and the
 * current rotations. Every market villager reads from this one instance; only the
 * subset of items shown depends on the villager's biome group.
 *
 * <p>All methods run on the server main thread.
 */
public final class MarketManager {

    /** Live state of one item, shared by every market villager. */
    public static final class ItemState {
        private double circulation;
        private long stock;
        private double buyUnit;
        private double sellUnit;

        public double circulation() { return circulation; }
        public long stock() { return stock; }
        /** What a villager pays the player per unit (SELL interface). */
        public double buyUnit() { return buyUnit; }
        /** What a villager charges the player per unit (BUY interface). */
        public double sellUnit() { return sellUnit; }
    }

    public enum TradeStatus { OK, NOT_OFFERED, OUT_OF_STOCK, NOT_ENOUGH_ITEMS, NOT_ENOUGH_MONEY, NO_SPACE, ERROR }

    /** {@code quantity} items exchanged for {@code coins}; {@code lot} is the minimum quantity. */
    public record TradeResult(TradeStatus status, long quantity, long coins, int lot) {
        static TradeResult of(TradeStatus status) {
            return new TradeResult(status, 0, 0, 0);
        }
    }

    private static final String META_NEXT_ROTATION = "next_rotation_at";
    private static final String META_CYCLE = "rotation_cycle";

    private final Plugin plugin;
    private final Database db;
    private final ItemConfig config;
    private final PricingEngine pricing;
    private final RotationEngine<Material> rotationEngine;
    private final double circulationDecay;
    private final long intervalMillis;
    private final boolean broadcastRotation;
    private final ConfigurationSection biomePools;
    private final Random random = new Random();

    private final Map<Material, ItemState> states = new HashMap<>();
    private final Map<String, Rotation<Material>> rotations = new HashMap<>();
    private long nextRotationAt;
    private long cycle;
    private BukkitTask task;
    private final List<Runnable> changeListeners = new ArrayList<>();
    private final List<Runnable> rotationListeners = new ArrayList<>();

    public MarketManager(Plugin plugin, Database db, ItemConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;
        ConfigurationSection m = plugin.getConfig().getConfigurationSection("market");
        double markup = m == null ? 1.4 : m.getDouble("markup", 1.4);
        double k = m == null ? 1.0 : m.getDouble("k", 1.0);
        double scaleLots = m == null ? 64 : m.getDouble("scale_lots", 64);
        this.pricing = new PricingEngine(config.global().priceFloorPerUnit(), k, scaleLots, markup);
        this.circulationDecay = m == null ? 0.02 : m.getDouble("circulation_decay", 0.02);
        this.broadcastRotation = m == null || m.getBoolean("broadcast_rotation", true);
        this.intervalMillis = (long) (config.global().rotationIntervalHours() * 3_600_000L);
        double inStockWeight = m == null ? 4.0 : m.getDouble("in_stock_weight", 4.0);
        boolean favor = config.global().favorInStock();
        this.rotationEngine = new RotationEngine<>(config.slotsPerCategory(),
                mat -> favor && stockOf(mat) > 0 ? inStockWeight : 1.0);
        this.biomePools = plugin.getConfig().getConfigurationSection("biome_pools");
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void load() throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT material, circulation, stock FROM market_item_state")) {
            while (rs.next()) {
                Material mat = Material.matchMaterial(rs.getString(1));
                if (mat != null && config.items().containsKey(mat)) {
                    ItemState s = new ItemState();
                    s.circulation = rs.getDouble(2);
                    s.stock = rs.getLong(3);
                    states.put(mat, s);
                }
            }
        }
        for (MarketItem item : config.items().values()) {
            states.computeIfAbsent(item.material(), x -> new ItemState());
        }
        // Prices are always recomputed from the circulation counters (config may have changed).
        recomputePrices();

        boolean rotationValid = loadRotations();
        nextRotationAt = db.getMeta(META_NEXT_ROTATION).map(Long::parseLong).orElse(0L);
        cycle = db.getMeta(META_CYCLE).map(Long::parseLong).orElse(0L);
        if (!rotationValid || nextRotationAt == 0) {
            plugin.getLogger().info("Aucune rotation valide en base : tirage initial du marché.");
            runCycle(false);
        } else {
            persistStates();
        }
    }

    public void start(long checkIntervalTicks) {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (System.currentTimeMillis() >= nextRotationAt) {
                runCycle(true);
            }
        }, checkIntervalTicks, checkIntervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    public void onChange(Runnable listener) {
        changeListeners.add(listener);
    }

    public void onRotation(Runnable listener) {
        rotationListeners.add(listener);
    }

    // ------------------------------------------------------------------
    // Rotation cycle (every rotation_interval_hours)
    // ------------------------------------------------------------------

    /** Decay → reprice → check constraints → draw rotations → persist. */
    public void runCycle(boolean applyDecay) {
        if (applyDecay) {
            states.values().forEach(s -> s.circulation = PricingEngine.decay(s.circulation, circulationDecay));
        }
        recomputePrices();
        Map<String, Rotation<Material>> drawn = rotationEngine.draw(buildPools(allGroups()), random);
        verifyNoOverlap(drawn.values());
        long next = System.currentTimeMillis() + intervalMillis;
        try {
            db.transaction(c -> {
                writeStates(c);
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("DELETE FROM market_rotation");
                }
                writeRotations(c, drawn);
                Database.setMeta(c, META_NEXT_ROTATION, Long.toString(next));
                Database.setMeta(c, META_CYCLE, Long.toString(cycle + 1));
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Échec de la sauvegarde de la rotation, nouvel essai dans 5 min", e);
            nextRotationAt = System.currentTimeMillis() + 5 * 60_000L;
            return;
        }
        rotations.clear();
        rotations.putAll(drawn);
        nextRotationAt = next;
        cycle++;
        plugin.getLogger().info("Rotation du marché n°" + cycle + " effectuée (" + drawn.size() + " biomes).");
        rotationListeners.forEach(Runnable::run);
        if (broadcastRotation && applyDecay) {
            Bukkit.getOnlinePlayers().forEach(p -> Messages.send(p,
                    "<gray>Les idiots du village ont renouvelé leurs étals ! Nouveaux prix en vigueur."));
        }
    }

    private void recomputePrices() {
        for (MarketItem item : config.items().values()) {
            ItemState s = states.get(item.material());
            PricingEngine.Prices prices = pricing.compute(item, s.circulation);
            s.buyUnit = prices.buyUnit();
            s.sellUnit = prices.sellUnit();
        }
        // Hard constraint re-checked on every recalculation, not only at init.
        for (MarketItem item : config.items().values()) {
            ItemState s = states.get(item.material());
            pricing.check(item, new PricingEngine.Prices(s.buyUnit, s.sellUnit));
        }
    }

    private static void verifyNoOverlap(Iterable<Rotation<Material>> drawn) {
        Set<Material> sell = new HashSet<>();
        Set<Material> buy = new HashSet<>();
        for (Rotation<Material> r : drawn) {
            r.sell().stream().filter(java.util.Objects::nonNull).forEach(sell::add);
            r.buy().stream().filter(java.util.Objects::nonNull).forEach(buy::add);
        }
        sell.retainAll(buy);
        if (!sell.isEmpty()) {
            throw new IllegalStateException("Exclusion VENTE/ACHAT violée pour " + sell);
        }
    }

    // ------------------------------------------------------------------
    // Biome groups (= vanilla villager type, i.e. the skin)
    // ------------------------------------------------------------------

    public static String groupOf(Villager villager) {
        return villager.getVillagerType().key().value();
    }

    private static List<String> allGroups() {
        List<String> groups = new ArrayList<>();
        RegistryAccess.registryAccess().getRegistry(RegistryKey.VILLAGER_TYPE)
                .forEach(type -> groups.add(type.key().value()));
        return groups;
    }

    private Map<String, Map<String, List<Material>>> buildPools(List<String> groups) {
        Map<String, Map<String, List<Material>>> pools = new LinkedHashMap<>();
        for (String group : groups) {
            ConfigurationSection filter = biomePools == null ? null : biomePools.getConfigurationSection(group);
            Set<Material> include = filter == null || !filter.isList("include") ? null : materials(filter.getStringList("include"));
            Set<Material> exclude = filter == null ? Set.of() : materials(filter.getStringList("exclude"));
            Map<String, List<Material>> byCategory = new LinkedHashMap<>();
            for (MarketItem item : config.items().values()) {
                if ((include == null || include.contains(item.material())) && !exclude.contains(item.material())) {
                    byCategory.computeIfAbsent(item.category(), x -> new ArrayList<>()).add(item.material());
                }
            }
            pools.put(group, byCategory);
        }
        return pools;
    }

    private Set<Material> materials(List<String> ids) {
        Set<Material> set = new HashSet<>();
        for (String id : ids) {
            Material mat = Material.matchMaterial(id);
            if (mat == null) {
                plugin.getLogger().warning("biome_pools : item inconnu '" + id + "'");
            } else {
                set.add(mat);
            }
        }
        return set;
    }

    /**
     * Current rotation for a biome group. A group unknown to the current cycle (new
     * villager type) is drawn on the fly while respecting the sides already claimed.
     */
    public Rotation<Material> rotationFor(String group) {
        Rotation<Material> rotation = rotations.get(group);
        if (rotation != null) {
            return rotation;
        }
        Map<String, Rotation<Material>> drawn = rotationEngine.draw(buildPools(List.of(group)),
                RotationEngine.claimsOf(rotations.values()), random);
        rotation = drawn.get(group);
        rotations.put(group, rotation);
        try {
            db.transaction(c -> {
                writeRotations(c, drawn);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Sauvegarde de la rotation de " + group + " impossible", e);
        }
        return rotation;
    }

    public boolean isOffered(String group, Side side, Material material) {
        Rotation<Material> r = rotationFor(group);
        return (side == Side.SELL ? r.sell() : r.buy()).contains(material);
    }

    // ------------------------------------------------------------------
    // Trades
    // ------------------------------------------------------------------

    /**
     * Player sells to the villager. Sells one lot, or everything the player carries
     * when {@code all} is set (always at least one lot so that the payout is >= 1 coin
     * without relying on the minimum-1 rule).
     */
    public TradeResult sellToVillager(Player player, String group, Material material, boolean all) {
        if (!isOffered(group, Side.SELL, material)) {
            return TradeResult.of(TradeStatus.NOT_OFFERED);
        }
        ItemState s = states.get(material);
        int lot = PricingEngine.lotSize(s.buyUnit);
        int owned = countPlain(player, material);
        long quantity = all ? owned : lot;
        if (owned < lot) {
            return new TradeResult(TradeStatus.NOT_ENOUGH_ITEMS, 0, 0, lot);
        }
        long payout = PricingEngine.total(s.buyUnit, quantity);
        long newStock = s.stock + quantity;
        double newCirculation = s.circulation + quantity;
        try {
            db.transaction(c -> {
                CurrencyManager.credit(c, player.getUniqueId(), payout);
                updateState(c, material, newStock, newCirculation);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Échec de la vente de " + player.getName(), e);
            return TradeResult.of(TradeStatus.ERROR);
        }
        removePlain(player, material, (int) quantity);
        s.stock = newStock;
        s.circulation = newCirculation;
        changeListeners.forEach(Runnable::run);
        return new TradeResult(TradeStatus.OK, quantity, payout, lot);
    }

    /** Player buys from the villager: one lot, or one full stack when {@code stack} is set. */
    public TradeResult buyFromVillager(Player player, String group, Material material, boolean stack) {
        if (!isOffered(group, Side.BUY, material)) {
            return TradeResult.of(TradeStatus.NOT_OFFERED);
        }
        ItemState s = states.get(material);
        int lot = PricingEngine.lotSize(s.sellUnit);
        if (s.stock <= 0) {
            return new TradeResult(TradeStatus.OUT_OF_STOCK, 0, 0, lot);
        }
        long quantity = Math.min(stack ? material.getMaxStackSize() : lot, s.stock);
        long cost = PricingEngine.total(s.sellUnit, quantity);
        if (freeSpaceFor(player, material) < quantity) {
            return new TradeResult(TradeStatus.NO_SPACE, quantity, cost, lot);
        }
        long newStock = s.stock - quantity;
        boolean paid;
        try {
            paid = db.transaction(c -> {
                if (!CurrencyManager.debit(c, player.getUniqueId(), cost)) {
                    return false;
                }
                updateState(c, material, newStock, s.circulation);
                return true;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Échec de l'achat de " + player.getName(), e);
            return TradeResult.of(TradeStatus.ERROR);
        }
        if (!paid) {
            return new TradeResult(TradeStatus.NOT_ENOUGH_MONEY, quantity, cost, lot);
        }
        s.stock = newStock;
        givePlain(player, material, (int) quantity);
        changeListeners.forEach(Runnable::run);
        return new TradeResult(TradeStatus.OK, quantity, cost, lot);
    }

    // ------------------------------------------------------------------
    // Inventory helpers (only plain, unmodified items are traded)
    // ------------------------------------------------------------------

    private static boolean isPlain(ItemStack stack, Material material) {
        return stack != null && stack.getType() == material && stack.isSimilar(ItemStack.of(material));
    }

    public static int countPlain(Player player, Material material) {
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (isPlain(stack, material)) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static void removePlain(Player player, Material material, int quantity) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        int remaining = quantity;
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (isPlain(stack, material)) {
                int take = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - take);
                contents[i] = stack.getAmount() == 0 ? null : stack;
                remaining -= take;
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    private static int freeSpaceFor(Player player, Material material) {
        int max = material.getMaxStackSize();
        int free = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.isEmpty()) {
                free += max;
            } else if (isPlain(stack, material)) {
                free += Math.max(0, max - stack.getAmount());
            }
        }
        return free;
    }

    private static void givePlain(Player player, Material material, int quantity) {
        int max = material.getMaxStackSize();
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = quantity; left > 0; left -= max) {
            stacks.add(ItemStack.of(material, Math.min(max, left)));
        }
        player.getInventory().addItem(stacks.toArray(ItemStack[]::new)).values()
                .forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private boolean loadRotations() throws SQLException {
        Map<String, Map<Side, Material[]>> raw = new HashMap<>();
        int size = config.totalSlots();
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery("SELECT group_key, side, slot, material FROM market_rotation")) {
            while (rs.next()) {
                int slot = rs.getInt(3);
                Side side;
                try {
                    side = Side.valueOf(rs.getString(2));
                } catch (IllegalArgumentException e) {
                    return false;
                }
                if (slot < 0 || slot >= size) {
                    return false;
                }
                String matName = rs.getString(4);
                Material mat = matName == null ? null : Material.matchMaterial(matName);
                if (matName != null && (mat == null || !config.items().containsKey(mat))) {
                    return false; // items.yml changed: redraw
                }
                raw.computeIfAbsent(rs.getString(1), g -> new HashMap<>())
                        .computeIfAbsent(side, x -> new Material[size])[slot] = mat;
            }
        }
        if (raw.isEmpty()) {
            return false;
        }
        Map<String, Rotation<Material>> loaded = new HashMap<>();
        raw.forEach((group, sides) -> loaded.put(group, new Rotation<>(
                Collections.unmodifiableList(java.util.Arrays.asList(sides.getOrDefault(Side.SELL, new Material[size]))),
                Collections.unmodifiableList(java.util.Arrays.asList(sides.getOrDefault(Side.BUY, new Material[size]))))));
        try {
            verifyNoOverlap(loaded.values());
        } catch (IllegalStateException e) {
            return false;
        }
        rotations.putAll(loaded);
        return true;
    }

    private void writeRotations(Connection c, Map<String, Rotation<Material>> drawn) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO market_rotation(group_key, side, slot, material) VALUES(?, ?, ?, ?)")) {
            for (var entry : drawn.entrySet()) {
                addRotationRows(ps, entry.getKey(), Side.SELL, entry.getValue().sell());
                addRotationRows(ps, entry.getKey(), Side.BUY, entry.getValue().buy());
            }
            ps.executeBatch();
        }
    }

    private static void addRotationRows(PreparedStatement ps, String group, Side side, List<Material> slots) throws SQLException {
        for (int i = 0; i < slots.size(); i++) {
            ps.setString(1, group);
            ps.setString(2, side.name());
            ps.setInt(3, i);
            ps.setString(4, slots.get(i) == null ? null : slots.get(i).name());
            ps.addBatch();
        }
    }

    private void persistStates() throws SQLException {
        db.transaction(c -> {
            writeStates(c);
            return null;
        });
    }

    private void writeStates(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO market_item_state(material, circulation, stock, price_buy, price_sell, updated_at)
                VALUES(?, ?, ?, ?, ?, ?)
                ON CONFLICT(material) DO UPDATE SET circulation = excluded.circulation, stock = excluded.stock,
                    price_buy = excluded.price_buy, price_sell = excluded.price_sell, updated_at = excluded.updated_at""")) {
            long now = System.currentTimeMillis();
            for (var entry : states.entrySet()) {
                ItemState s = entry.getValue();
                ps.setString(1, entry.getKey().name());
                ps.setDouble(2, s.circulation);
                ps.setLong(3, s.stock);
                ps.setDouble(4, s.buyUnit);
                ps.setDouble(5, s.sellUnit);
                ps.setLong(6, now);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void updateState(Connection c, Material material, long stock, double circulation) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE market_item_state SET stock = ?, circulation = ?, updated_at = ? WHERE material = ?")) {
            ps.setLong(1, stock);
            ps.setDouble(2, circulation);
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, material.name());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("market_item_state manquant pour " + material);
            }
        }
    }

    // ------------------------------------------------------------------
    // Read access
    // ------------------------------------------------------------------

    public ItemState state(Material material) {
        return states.get(material);
    }

    public MarketItem item(Material material) {
        return config.items().get(material);
    }

    public long stockOf(Material material) {
        ItemState s = states.get(material);
        return s == null ? 0 : s.stock;
    }

    public long nextRotationAt() {
        return nextRotationAt;
    }

    public long cycle() {
        return cycle;
    }

    public String describe(Material material) {
        MarketItem item = config.items().get(material);
        ItemState s = states.get(material);
        if (item == null || s == null) {
            return material + " n'est pas dans items.yml";
        }
        return String.format(Locale.ROOT, "%s [%s/%s] base=%.4f/u achat-PNJ=%.4f/u vente-PNJ=%.4f/u stock=%d circulation=%.1f",
                material, item.category(), item.tier(), item.baseUnitPrice(), s.buyUnit, s.sellUnit, s.stock, s.circulation);
    }
}
