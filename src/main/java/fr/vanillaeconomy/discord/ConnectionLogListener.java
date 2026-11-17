package fr.vanillaeconomy.discord;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Connections and disconnections in the #logs-serveur journal ({@link StaffLog#joined},
 * {@link StaffLog#left}): online count, first connection, length of the session and, for a
 * kick, its reason. No IP address is ever logged.
 */
public final class ConnectionLogListener implements Listener {

    private static final int MAX_REASON = 120;

    private final StaffLog staffLog;
    private final Map<UUID, Long> sessions = new HashMap<>();
    private final Map<UUID, String> kickReasons = new HashMap<>();

    public ConnectionLogListener(StaffLog staffLog) {
        this.staffLog = staffLog;
        // plugin (re)loaded with players online: their session starts now
        long now = System.currentTimeMillis();
        Bukkit.getOnlinePlayers().forEach(p -> sessions.put(p.getUniqueId(), now));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessions.put(player.getUniqueId(), System.currentTimeMillis());
        staffLog.joined(player.getName(), !player.hasPlayedBefore(), Bukkit.getOnlinePlayers().size());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        String reason = PlainTextComponentSerializer.plainText().serialize(event.reason()).strip().replace('\n', ' ');
        if (reason.length() > MAX_REASON) {
            reason = reason.substring(0, MAX_REASON) + "…";
        }
        kickReasons.put(event.getPlayer().getUniqueId(), reason);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Long since = sessions.remove(id);
        String kick = kickReasons.remove(id);
        String reason = switch (event.getReason()) {
            case KICKED -> kick == null || kick.isEmpty() ? "expulsion" : "expulsion (" + kick + ")";
            case TIMED_OUT -> "déconnexion (délai dépassé)";
            case ERRONEOUS_STATE -> "déconnexion (erreur)";
            case DISCONNECTED -> null;
        };
        // the leaving player is still in the online list during the event
        int online = (int) Bukkit.getOnlinePlayers().stream().filter(p -> !p.getUniqueId().equals(id)).count();
        staffLog.left(player.getName(), since == null ? 0 : System.currentTimeMillis() - since, reason, online);
    }
}
