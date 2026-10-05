package fr.vanillaeconomy.util;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.logging.Logger;

/**
 * Time zone of the server's community (config.yml "timezone"), independent of the host
 * machine's zone: rotation slots, promotion plans, announced hours and displayed dates.
 */
public final class ServerTime {

    private static ZoneId zone = ZoneId.of("Europe/Paris");

    private ServerTime() {
    }

    public static ZoneId zone() {
        return zone;
    }

    /** Called once at startup, before anything uses the zone. */
    public static void configure(String id, Logger logger) {
        try {
            zone = ZoneId.of(id == null || id.isBlank() ? "Europe/Paris" : id.trim());
        } catch (DateTimeException e) {
            logger.warning("timezone invalide '" + id + "' (ex : Europe/Paris), utilisation de Europe/Paris.");
            zone = ZoneId.of("Europe/Paris");
        }
    }
}
