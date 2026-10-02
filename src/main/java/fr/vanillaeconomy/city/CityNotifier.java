package fr.vanillaeconomy.city;

import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * City notifications: shown at once to an online player, otherwise stored (table
 * city_notification, survives restarts) and shown at his next login.
 */
public final class CityNotifier implements Listener {

    private record Notice(long id, String key, String[] vars) {
    }

    /** Separates the variables in the database column (never typed by players). */
    private static final String SEP = "\u001F";
    private static final long JOIN_DELAY_TICKS = 40;

    private final Plugin plugin;
    private final Database db;
    private final MessageConfig msg;
    private final Map<UUID, List<Notice>> pending = new HashMap<>();

    public CityNotifier(Plugin plugin, Database db, MessageConfig msg) {
        this.plugin = plugin;
        this.db = db;
        this.msg = msg;
    }

    public void load() throws SQLException {
        try (Statement st = db.connection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, player_uuid, message_key, vars FROM city_notification ORDER BY created_at, id")) {
            while (rs.next()) {
                String vars = rs.getString(4);
                pending.computeIfAbsent(UUID.fromString(rs.getString(2)), u -> new ArrayList<>())
                        .add(new Notice(rs.getLong(1), rs.getString(3), vars.isEmpty() ? new String[0] : vars.split(SEP, -1)));
            }
        }
    }

    /** Sends now if the player is online, otherwise keeps the message for his next login. */
    public void notify(UUID player, String key, Object... vars) {
        Player online = Bukkit.getPlayer(player);
        if (online != null) {
            msg.send(online, key, vars);
            return;
        }
        String[] values = new String[vars.length];
        for (int i = 0; i < vars.length; i++) {
            values[i] = String.valueOf(vars[i]);
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO city_notification(player_uuid, message_key, vars, created_at) VALUES(?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, player.toString());
            ps.setString(2, key);
            ps.setString(3, String.join(SEP, values));
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                pending.computeIfAbsent(player, u -> new ArrayList<>()).add(new Notice(keys.getLong(1), key, values));
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Cités : notification non enregistrée pour " + player, e);
        }
    }

    /**
     * Dissolution (owner or admin): every member but {@code actor} is told, and each
     * contributor learns his refund.
     */
    public void dissolution(City city, Collection<UUID> members, UUID actor, Map<UUID, Long> refunds, String key) {
        for (UUID member : members) {
            if (!member.equals(actor)) {
                notify(member, key, "city", city.name());
            }
        }
        refunds.forEach((uuid, amount) -> notify(uuid, "refund_notify", "city", city.name(),
                "amount", Messages.coins(amount)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!pending.containsKey(uuid)) {
            return;
        }
        // A little later, so the messages are not lost among the join messages.
        Bukkit.getScheduler().runTaskLater(plugin, () -> deliver(uuid), JOIN_DELAY_TICKS);
    }

    private void deliver(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        List<Notice> notices = pending.get(uuid);
        if (player == null || notices == null) {
            return;
        }
        pending.remove(uuid);
        msg.send(player, "offline_header", "count", notices.size());
        for (Notice n : notices) {
            msg.send(player, n.key(), (Object[]) n.vars());
        }
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM city_notification WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Cités : notifications de " + uuid + " non effacées", e);
        }
    }
}
