package fr.vanillaeconomy.market;

import java.util.Random;

/**
 * Decides which rotation cycles carry promotional prices (pure, unit-tested).
 *
 * <p>Guarantee: every sliding window of {@code window} consecutive cycles contains at
 * least {@code minPerWindow} promo cycles (e.g. 2 out of 12 = at least 2 per 24 h with
 * 2 h rotations), whatever the starting point. On top of the guaranteed ones, each
 * cycle is a promo cycle with probability {@code chance}.
 *
 * <p>The history is a string of '0' / '1' (oldest first), at most {@code window - 1} long.
 */
public final class PromoScheduler {

    private final int window;
    private final int minPerWindow;
    private final double chance;

    public PromoScheduler(int window, int minPerWindow, double chance) {
        if (window < 1 || minPerWindow < 0 || minPerWindow > window || chance < 0 || chance > 1) {
            throw new IllegalArgumentException("invalid promo parameters");
        }
        this.window = window;
        this.minPerWindow = minPerWindow;
        this.chance = chance;
    }

    /** Whether the next cycle is a promo cycle, given the previous cycles. */
    public boolean decide(String history, Random random) {
        long recent = history.chars().filter(c -> c == '1').count();
        // The window ending with the next cycle = the (window - 1) stored cycles + the next one.
        if (recent < minPerWindow) {
            return true;
        }
        return random.nextDouble() < chance;
    }

    /** Appends a cycle to the history and keeps only what the next decision needs. */
    public String push(String history, boolean promo) {
        String updated = history + (promo ? '1' : '0');
        int keep = window - 1;
        return updated.length() > keep ? updated.substring(updated.length() - keep) : updated;
    }
}
