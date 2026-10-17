package fr.vanillaeconomy.market;

import org.bukkit.Location;

/**
 * Promotions that only apply to the villagers of one place (a city with Promotion en
 * folie). The market itself stays global: only the applicable discount changes.
 */
public interface LocalPromotions {

    /** No local promotion anywhere. */
    LocalPromotions NONE = new LocalPromotions() {
        @Override
        public Integer contextAt(Location location) {
            return null;
        }

        @Override
        public int percent(int context, long slotStart) {
            return 0;
        }

        @Override
        public long nextStart(int context, long now) {
            return Long.MAX_VALUE;
        }
    };

    /**
     * Context of a villager standing at {@code location} (memory only: called on every
     * right click), or null when no local promotion applies there.
     */
    Integer contextAt(Location location);

    /** Discount of the context for the slot starting at {@code slotStart}, 0 if none. */
    int percent(int context, long slotStart);

    /** Start of the next promotion slot of the context after {@code now}, Long.MAX_VALUE if none. */
    long nextStart(int context, long now);
}
