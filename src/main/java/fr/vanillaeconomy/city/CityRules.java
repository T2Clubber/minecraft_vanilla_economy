package fr.vanillaeconomy.city;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure business rules of cities (unit-tested): refunds, tiers, presence, visitors. */
public final class CityRules {

    private CityRules() {
    }

    // ------------------------------------------------------------------
    // Disband refund: prorata of net contributions
    // ------------------------------------------------------------------

    /**
     * Splits {@code balance} between contributors in proportion to their total
     * contributions (largest-remainder rounding, so the shares add up to the balance
     * exactly). Without any contribution, everything goes to {@code fallback}.
     */
    public static Map<UUID, Long> refunds(Map<UUID, Long> contributions, long balance, UUID fallback) {
        Map<UUID, Long> result = new LinkedHashMap<>();
        if (balance <= 0) {
            return result;
        }
        long total = contributions.values().stream().filter(v -> v > 0).mapToLong(Long::longValue).sum();
        if (total <= 0) {
            result.put(fallback, balance);
            return result;
        }
        record Share(UUID player, long base, long remainder) {
        }
        List<Share> shares = new ArrayList<>();
        long distributed = 0;
        for (var e : contributions.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            // balance * contribution / total, without overflow for realistic amounts
            java.math.BigInteger num = java.math.BigInteger.valueOf(balance).multiply(java.math.BigInteger.valueOf(e.getValue()));
            java.math.BigInteger[] qr = num.divideAndRemainder(java.math.BigInteger.valueOf(total));
            shares.add(new Share(e.getKey(), qr[0].longValue(), qr[1].longValue()));
            distributed += qr[0].longValue();
        }
        shares.sort(Comparator.comparingLong(Share::remainder).reversed().thenComparing(sh -> sh.player().toString()));
        Map<UUID, Long> extra = new HashMap<>();
        for (int i = 0; i < balance - distributed; i++) {
            extra.merge(shares.get(i % shares.size()).player(), 1L, Long::sum);
        }
        for (Share sh : shares) {
            long amount = sh.base() + extra.getOrDefault(sh.player(), 0L);
            if (amount > 0) {
                result.put(sh.player(), amount);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Tiers
    // ------------------------------------------------------------------

    public record Tier(int level, int size, long price) {
    }

    /** Checks levels 1..n consecutive, strictly increasing sizes, non-negative prices. */
    public static List<String> validateTiers(List<Tier> tiers) {
        List<String> errors = new ArrayList<>();
        if (tiers.isEmpty()) {
            errors.add("aucun palier défini");
            return errors;
        }
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (t.level() != i + 1) {
                errors.add("palier " + t.level() + " : les paliers doivent être numérotés 1, 2, 3...");
            }
            if (t.size() <= 0) {
                errors.add("palier " + t.level() + " : taille invalide");
            }
            if (t.price() < 0) {
                errors.add("palier " + t.level() + " : prix négatif");
            }
            if (i > 0 && t.size() <= tiers.get(i - 1).size()) {
                errors.add("palier " + t.level() + " : la taille doit être supérieure au palier précédent");
            }
        }
        return errors;
    }

    public static Optional<Tier> nextTier(List<Tier> tiers, int currentLevel) {
        return tiers.stream().filter(t -> t.level() == currentLevel + 1).findFirst();
    }

    // ------------------------------------------------------------------
    // Presence transitions
    // ------------------------------------------------------------------

    public enum PresenceKind { EXIT, ENTER }

    public record PresenceChange(PresenceKind kind, int cityId) {
    }

    /** Messages to show when moving from city {@code from} to city {@code to} (null = no city). */
    public static List<PresenceChange> transitions(Integer from, Integer to) {
        List<PresenceChange> changes = new ArrayList<>(2);
        if (java.util.Objects.equals(from, to)) {
            return changes;
        }
        if (from != null) {
            changes.add(new PresenceChange(PresenceKind.EXIT, from));
        }
        if (to != null) {
            changes.add(new PresenceChange(PresenceKind.ENTER, to));
        }
        return changes;
    }

    // ------------------------------------------------------------------
    // Visitors (non-members)
    // ------------------------------------------------------------------

    /**
     * Visitors may only open / close the allow-listed blocks (doors and trapdoors by
     * default); every other block interaction is refused.
     */
    public static <M> boolean visitorMayInteract(M clickedBlock, Set<M> allowed) {
        return clickedBlock != null && allowed.contains(clickedBlock);
    }

    // ------------------------------------------------------------------
    // Names
    // ------------------------------------------------------------------

    public static boolean validName(String name, int minLength, int maxLength) {
        return name != null && name.length() >= minLength && name.length() <= maxLength
                && name.matches("[A-Za-z0-9_-]+");
    }
}
