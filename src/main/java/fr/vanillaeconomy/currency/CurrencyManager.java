package fr.vanillaeconomy.currency;

import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Balances (table player_balance) and the physical coin item.
 *
 * <p>Anti-dupe: every {@code /money drop} mints a new serial (UUID) recorded in
 * table coin_serial with the issued amount. Each coin stack carries the serial and
 * an HMAC-SHA256 checksum of it (server secret) in its PersistentDataContainer.
 * On deposit the checksum must match (forgery) and the redeemed total of the serial
 * can never exceed the issued amount (duplication): duplicated coins can therefore
 * never create money.
 *
 * <p>Consequence: coins minted by different {@code /money drop} have different
 * serials and do not stack together (vanilla only stacks identical items).
 */
public final class CurrencyManager {

    public enum DepositStatus { OK, PARTIAL_DUPLICATE, NOT_A_COIN, FORGED, UNKNOWN_SERIAL, ALREADY_REDEEMED }

    /** Outcome of a deposit: {@code credited} coins were added to the balance. */
    public record DepositResult(DepositStatus status, long credited, long rejected) {
    }

    private static final String META_SECRET = "coin_secret";
    private static final int COIN_STACK_SIZE = 64;

    private final Plugin plugin;
    private final Database db;
    private final NamespacedKey serialKey;
    private final NamespacedKey signatureKey;
    private final byte[] secret;

    private Material coinMaterial;
    private int customModelData;
    private NamespacedKey itemModel;
    private String displayName;
    private List<String> lore;

    public CurrencyManager(Plugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        this.serialKey = new NamespacedKey(plugin, "coin_serial");
        this.signatureKey = new NamespacedKey(plugin, "coin_sig");
        this.secret = loadOrCreateSecret();
        loadConfig(plugin.getConfig().getConfigurationSection("currency"));
    }

    private byte[] loadOrCreateSecret() throws SQLException {
        var stored = db.getMeta(META_SECRET);
        if (stored.isPresent()) {
            return Base64.getDecoder().decode(stored.get());
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        db.transaction(c -> {
            Database.setMeta(c, META_SECRET, Base64.getEncoder().encodeToString(fresh));
            return null;
        });
        return fresh;
    }

    private void loadConfig(ConfigurationSection cfg) {
        Material mat = cfg == null ? null : Material.matchMaterial(cfg.getString("material", "GOLD_NUGGET"));
        if (mat == null || !mat.isItem()) {
            plugin.getLogger().warning("currency.material invalide, utilisation de GOLD_NUGGET");
            mat = Material.GOLD_NUGGET;
        }
        this.coinMaterial = mat;
        this.customModelData = cfg == null ? 0 : cfg.getInt("custom_model_data", 0);
        String model = cfg == null ? "" : cfg.getString("item_model", "");
        this.itemModel = model == null || model.isBlank() ? null : NamespacedKey.fromString(model);
        this.displayName = cfg == null ? "<gold>Pièce" : cfg.getString("display_name", "<gold>Pièce");
        this.lore = cfg == null ? List.of() : cfg.getStringList("lore");
    }

    // ------------------------------------------------------------------
    // Balances
    // ------------------------------------------------------------------

    public void ensureAccount(UUID uuid, String name) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                INSERT INTO player_balance(uuid, name, balance, updated_at) VALUES(?, ?, 0, ?)
                ON CONFLICT(uuid) DO UPDATE SET name = excluded.name""")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Impossible de créer le compte de " + name, e);
        }
    }

    public long getBalance(UUID uuid) throws SQLException {
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT balance FROM player_balance WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** Adds {@code amount} (> 0) to the balance, creating the account if needed. Use inside a transaction. */
    public static void credit(Connection c, UUID uuid, long amount) throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("negative credit");
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO player_balance(uuid, name, balance, updated_at) VALUES(?, NULL, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance, updated_at = excluded.updated_at""")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, amount);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Removes {@code amount} from the balance only if it is sufficient. Use inside a transaction. */
    public static boolean debit(Connection c, UUID uuid, long amount) throws SQLException {
        if (amount < 0) {
            throw new IllegalArgumentException("negative debit");
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE player_balance SET balance = balance - ?, updated_at = ? WHERE uuid = ? AND balance >= ?")) {
            ps.setLong(1, amount);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.setLong(4, amount);
            return ps.executeUpdate() == 1;
        }
    }

    /** Atomic transfer. Returns false (nothing changed) if the payer cannot afford it. */
    public boolean transfer(UUID from, UUID to, long amount) throws SQLException {
        return db.transaction(c -> {
            if (!debit(c, from, amount)) {
                return false;
            }
            credit(c, to, amount);
            return true;
        });
    }

    // ------------------------------------------------------------------
    // Physical coins
    // ------------------------------------------------------------------

    /**
     * Debits {@code amount} and mints the matching coin stacks under a new serial,
     * in one transaction. Returns an empty list if the balance is insufficient.
     */
    public List<ItemStack> withdrawAsCoins(UUID owner, long amount) throws SQLException {
        String serial = UUID.randomUUID().toString();
        boolean ok = db.transaction(c -> {
            if (!debit(c, owner, amount)) {
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO coin_serial(serial, issued, redeemed, issuer, created_at) VALUES(?, ?, 0, ?, ?)")) {
                ps.setString(1, serial);
                ps.setLong(2, amount);
                ps.setString(3, owner.toString());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return true;
        });
        if (!ok) {
            return List.of();
        }
        List<ItemStack> stacks = new ArrayList<>();
        long remaining = amount;
        while (remaining > 0) {
            int size = (int) Math.min(COIN_STACK_SIZE, remaining);
            stacks.add(createCoinStack(serial, size));
            remaining -= size;
        }
        return stacks;
    }

    public static int stacksNeeded(long amount) {
        return (int) ((amount + COIN_STACK_SIZE - 1) / COIN_STACK_SIZE);
    }

    private ItemStack createCoinStack(String serial, int amount) {
        ItemStack stack = new ItemStack(coinMaterial, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.setMaxStackSize(COIN_STACK_SIZE);
        meta.displayName(Messages.item(displayName));
        meta.lore(lore.stream().map(Messages::item).toList());
        if (customModelData > 0) {
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(List.of((float) customModelData));
            meta.setCustomModelDataComponent(cmd);
        }
        if (itemModel != null) {
            meta.setItemModel(itemModel);
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(serialKey, PersistentDataType.STRING, serial);
        pdc.set(signatureKey, PersistentDataType.STRING, sign(serial));
        stack.setItemMeta(meta);
        return stack;
    }

    /** custom_model_data of real coins (0 = none). */
    public int customModelData() {
        return customModelData;
    }

    /**
     * Display-only item on the coin material with the given custom_model_data (resource
     * pack variant). It carries no serial: it is not money and cannot be deposited.
     */
    public ItemStack icon(int modelData) {
        ItemStack stack = ItemStack.of(coinMaterial);
        if (modelData > 0) {
            ItemMeta meta = stack.getItemMeta();
            CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(List.of((float) modelData));
            meta.setCustomModelDataComponent(cmd);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** True for anything carrying the coin tag, valid or not. */
    public boolean isCoin(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(serialKey, PersistentDataType.STRING);
    }

    /**
     * Credits the coins of {@code stack} to {@code owner}. The caller removes the
     * stack from the player's hand afterwards ({@code credited + rejected} items).
     */
    public DepositResult deposit(UUID owner, ItemStack stack) throws SQLException {
        if (!isCoin(stack)) {
            return new DepositResult(DepositStatus.NOT_A_COIN, 0, 0);
        }
        var pdc = stack.getPersistentDataContainer();
        String serial = pdc.get(serialKey, PersistentDataType.STRING);
        String signature = pdc.get(signatureKey, PersistentDataType.STRING);
        int amount = stack.getAmount();
        if (serial == null || signature == null || !constantTimeEquals(sign(serial), signature)
                || stack.getType() != coinMaterial) {
            return new DepositResult(DepositStatus.FORGED, 0, amount);
        }
        return db.transaction(c -> {
            long issued;
            long redeemed;
            try (PreparedStatement ps = c.prepareStatement("SELECT issued, redeemed FROM coin_serial WHERE serial = ?")) {
                ps.setString(1, serial);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new DepositResult(DepositStatus.UNKNOWN_SERIAL, 0, amount);
                    }
                    issued = rs.getLong(1);
                    redeemed = rs.getLong(2);
                }
            }
            long available = issued - redeemed;
            if (available <= 0) {
                return new DepositResult(DepositStatus.ALREADY_REDEEMED, 0, amount);
            }
            long credited = Math.min(available, amount);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE coin_serial SET redeemed = redeemed + ? WHERE serial = ?")) {
                ps.setLong(1, credited);
                ps.setString(2, serial);
                ps.executeUpdate();
            }
            credit(c, owner, credited);
            long rejected = amount - credited;
            return new DepositResult(rejected > 0 ? DepositStatus.PARTIAL_DUPLICATE : DepositStatus.OK, credited, rejected);
        });
    }

    public String serialOf(ItemStack stack) {
        return isCoin(stack) ? stack.getPersistentDataContainer().get(serialKey, PersistentDataType.STRING) : null;
    }

    private String sign(String serial) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(serial.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
