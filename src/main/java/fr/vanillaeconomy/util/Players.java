package fr.vanillaeconomy.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public final class Players {

    private Players() {
    }

    /** Online name, UUID, or a player the server has already seen (never a blocking web lookup). */
    public static Optional<UUID> resolve(String raw) {
        Player online = Bukkit.getPlayerExact(raw);
        if (online != null) {
            return Optional.of(online.getUniqueId());
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            // not a UUID
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(raw);
        return cached == null ? Optional.empty() : Optional.of(cached.getUniqueId());
    }

    /** Player name (first characters of the UUID if the server has never seen him). */
    public static String name(UUID player) {
        String name = Bukkit.getOfflinePlayer(player).getName();
        return name != null ? name : player.toString().substring(0, 8);
    }
}
