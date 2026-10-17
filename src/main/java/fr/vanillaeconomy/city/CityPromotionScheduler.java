package fr.vanillaeconomy.city;

import fr.vanillaeconomy.market.LocalPromotions;
import fr.vanillaeconomy.market.MarketManager;
import fr.vanillaeconomy.market.PromotionManager;
import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Promotion en folie: every day, each city with the extension gets {@code promos_per_day}
 * extra promotion slots, drawn among the slots WITHOUT a global promotion (table
 * city_promotion_schedule), when the global plan of that day is drawn. The market villagers
 * standing in the city see these promotions (resolved from their position, memory only);
 * the online members are told in the chat. Discord only ever announces global promotions.
 */
public final class CityPromotionScheduler implements LocalPromotions, Listener {

    /** Chat announcements are checked this often (main thread). */
    private static final long CHECK_TICKS = 20L * 20;

    public enum NoticeKind { WARNING, START }

    /** A message due for one city promotion slot. */
    public record Notice(NoticeKind kind, long slotStart, int percent) {
    }

    private final Plugin plugin;
    private final Database db;
    private final CityManager cities;
    private final ExtensionManager extensions;
    private final MarketManager market;
    private final MessageConfig msg;
    private final CityConfig config;
    private final PromotionManager planner;
    private final ZoneId zone;
    private final long interval;
    private final Random random = new Random();
    /** City id -> day -> slot index -> discount. */
    private final Map<Integer, Map<LocalDate, Map<Integer, Integer>>> plans = new HashMap<>();
    private long lastCheck;
    private BukkitTask task;
    /** Market GUIs to refresh when a promotion is forced. */
    private Runnable onChange = () -> { };

    public CityPromotionScheduler(Plugin plugin, Database db, CityManager cities, ExtensionManager extensions,
                                  MarketManager market, MessageConfig msg) {
        this.plugin = plugin;
        this.db = db;
        this.cities = cities;
        this.extensions = extensions;
        this.market = market;
        this.msg = msg;
        this.config = cities.config();
        this.zone = market.zone();
        this.interval = market.intervalMillis();
        int slotsPerDay = (int) Math.max(1, 86_400_000L / interval);
        this.planner = new PromotionManager(slotsPerDay, Math.min(slotsPerDay, config.frenzyPromosPerDay),
                config.frenzyMinPercent, config.frenzyMaxPercent);
        cities.onRemoval(city -> plans.remove(city.id()));
    }

    // ------------------------------------------------------------------
    // Plans
    // ------------------------------------------------------------------

    /** Loads the recent plans and forgets the old ones. */
    public void load() throws SQLException {
        LocalDate today = PromotionManager.dayOf(System.currentTimeMillis(), zone);
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM city_promotion_schedule WHERE date < ?")) {
                ps.setString(1, today.minusDays(2).toString());
                ps.executeUpdate();
            }
            return null;
        });
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT city_id, date, slot_index, discount_percent FROM city_promotion_schedule WHERE date >= ?")) {
            ps.setString(1, today.minusDays(1).toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    plans.computeIfAbsent(rs.getInt(1), k -> new HashMap<>())
                            .computeIfAbsent(LocalDate.parse(rs.getString(2)), k -> new TreeMap<>())
                            .put(rs.getInt(3), rs.getInt(4));
                }
            }
        }
    }

    /** Global plan of {@code day} known: each city with the extension gets its own plan for that day. */
    public void onGlobalPlan(LocalDate day, Map<Integer, Integer> globalPlan) {
        for (City city : cities.all()) {
            if (extensions.active(city, CityExtension.PROMO_FRENZY)) {
                ensure(city, day, globalPlan);
            }
        }
    }

    /** Extension just bought: plans of today and tomorrow right away. */
    public void onPurchase(City city, CityExtension extension) {
        if (extension != CityExtension.PROMO_FRENZY) {
            return;
        }
        LocalDate today = PromotionManager.dayOf(System.currentTimeMillis(), zone);
        for (LocalDate day : List.of(today, today.plusDays(1))) {
            ensure(city, day, market.globalPlan(day));
        }
    }

    private void ensure(City city, LocalDate day, Map<Integer, Integer> globalPlan) {
        Map<LocalDate, Map<Integer, Integer>> byDay = plans.computeIfAbsent(city.id(), k -> new HashMap<>());
        if (byDay.containsKey(day)) {
            return;
        }
        Map<Integer, Integer> plan = planner.planDay(random, globalPlan.keySet());
        try {
            db.transaction(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT OR IGNORE INTO city_promotion_schedule(city_id, date, slot_index, discount_percent)
                        VALUES(?, ?, ?, ?)""")) {
                    for (var e : plan.entrySet()) {
                        ps.setInt(1, city.id());
                        ps.setString(2, day.toString());
                        ps.setInt(3, e.getKey());
                        ps.setInt(4, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Planning des promos de " + city.name() + " impossible", e);
            return;
        }
        byDay.put(day, plan);
        plugin.getLogger().info("Promotion en folie, " + city.name() + ", " + day + " : " + describe(plan));
    }

    private String describe(Map<Integer, Integer> plan) {
        List<String> parts = new ArrayList<>();
        long hours = interval / 3_600_000L;
        plan.forEach((slot, pct) -> parts.add(String.format(java.util.Locale.ROOT, "%02dh -%d%%", slot * hours, pct)));
        return parts.isEmpty() ? "aucune" : String.join(", ", parts);
    }

    /** City promotion slots of today and tomorrow: slot start -> discount. */
    NavigableMap<Long, Integer> starts(int cityId, long now) {
        NavigableMap<Long, Integer> starts = new TreeMap<>();
        Map<LocalDate, Map<Integer, Integer>> byDay = plans.getOrDefault(cityId, Map.of());
        LocalDate today = PromotionManager.dayOf(now, zone);
        for (LocalDate day : List.of(today.minusDays(1), today, today.plusDays(1))) {
            byDay.getOrDefault(day, Map.of()).forEach((slot, pct) ->
                    starts.put(PromotionManager.slotStartOf(day, slot, zone, interval), pct));
        }
        return starts;
    }

    public void onChange(Runnable listener) {
        this.onChange = listener;
    }

    /** Admin: plans of today and tomorrow of a city. */
    public String describePlans(City city) {
        LocalDate today = PromotionManager.dayOf(System.currentTimeMillis(), zone);
        Map<LocalDate, Map<Integer, Integer>> byDay = plans.getOrDefault(city.id(), Map.of());
        return today + " : " + describe(byDay.getOrDefault(today, Map.of())) + " | " + today.plusDays(1) + " : "
                + describe(byDay.getOrDefault(today.plusDays(1), Map.of()));
    }

    /** Admin (tests): the current rotation becomes a promotion of the city, announced right away. */
    public void forceNow(City city, int percent) throws SQLException {
        long slotStart = market.cycleStartedAt();
        LocalDate day = PromotionManager.dayOf(slotStart, zone);
        int slot = PromotionManager.slotIndex(slotStart, zone, interval);
        db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO city_promotion_schedule(city_id, date, slot_index, discount_percent) VALUES(?, ?, ?, ?)")) {
                ps.setInt(1, city.id());
                ps.setString(2, day.toString());
                ps.setInt(3, slot);
                ps.setInt(4, percent);
                ps.executeUpdate();
            }
            return null;
        });
        plans.computeIfAbsent(city.id(), k -> new HashMap<>()).computeIfAbsent(day, k -> new TreeMap<>()).put(slot, percent);
        long now = System.currentTimeMillis();
        for (UUID member : recipients(city.members().keySet(), id -> Bukkit.getPlayer(id) != null)) {
            Bukkit.getPlayer(member).sendMessage(msg.get("frenzy_start", "city", city.name(), "percent", percent,
                    "delay", Messages.duration(slotStart + interval - now)));
        }
        onChange.run();
    }

    // ------------------------------------------------------------------
    // LocalPromotions (market GUIs)
    // ------------------------------------------------------------------

    @Override
    public Integer contextAt(Location location) {
        City city = cities.at(location);
        return extensions.active(city, CityExtension.PROMO_FRENZY) ? city.id() : null;
    }

    @Override
    public int percent(int context, long slotStart) {
        if (!extensions.active(cities.byId(context), CityExtension.PROMO_FRENZY)) {
            return 0;
        }
        LocalDate day = PromotionManager.dayOf(slotStart, zone);
        return plans.getOrDefault(context, Map.of()).getOrDefault(day, Map.of())
                .getOrDefault(PromotionManager.slotIndex(slotStart, zone, interval), 0);
    }

    @Override
    public long nextStart(int context, long now) {
        if (!extensions.active(cities.byId(context), CityExtension.PROMO_FRENZY)) {
            return Long.MAX_VALUE;
        }
        Long next = starts(context, now).higherKey(now);
        return next == null ? Long.MAX_VALUE : next;
    }

    // ------------------------------------------------------------------
    // Chat announcements to the online members
    // ------------------------------------------------------------------

    /** Plans of today and tomorrow (the market published its plans before this listener existed), then the chat checks. */
    public void start() {
        LocalDate today = PromotionManager.dayOf(System.currentTimeMillis(), zone);
        for (LocalDate day : List.of(today, today.plusDays(1))) {
            onGlobalPlan(day, market.globalPlan(day));
        }
        lastCheck = System.currentTimeMillis();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::announce, CHECK_TICKS, CHECK_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    /** Messages due since the last check (no catch-up after a restart, nothing queued for offline members). */
    private void announce() {
        long now = System.currentTimeMillis();
        long from = lastCheck;
        lastCheck = now;
        for (City city : cities.all()) {
            if (!extensions.active(city, CityExtension.PROMO_FRENZY)) {
                continue;
            }
            for (Notice notice : notices(from, now, starts(city.id(), now), interval, config.frenzyWarningMillis,
                    config.frenzyWarning, config.frenzyStart)) {
                String key = notice.kind() == NoticeKind.WARNING ? "frenzy_warning" : "frenzy_start";
                long delay = notice.kind() == NoticeKind.WARNING ? notice.slotStart() - now
                        : notice.slotStart() + interval - now;
                for (UUID member : recipients(city.members().keySet(), id -> Bukkit.getPlayer(id) != null)) {
                    Bukkit.getPlayer(member).sendMessage(msg.get(key, "city", city.name(), "percent", notice.percent(),
                            "delay", Messages.duration(delay)));
                }
            }
        }
    }

    /** Reminder at login when a promotion of one of the player's cities is running. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.frenzyJoinReminder) {
            return;
        }
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        for (City city : cities.citiesOf(player.getUniqueId())) {
            if (!extensions.active(city, CityExtension.PROMO_FRENZY)) {
                continue;
            }
            active(now, starts(city.id(), now), interval).ifPresent(n ->
                    player.sendMessage(msg.get("frenzy_reminder", "city", city.name(), "percent", n.percent(),
                            "delay", Messages.duration(n.slotStart() + interval - now))));
        }
    }

    /**
     * Messages due in (from, to]: a START when a promotion slot begins, a WARNING
     * {@code warningLead} before it, unless the previous slot is itself a promotion of the
     * city (consecutive promotions: only the start of the second one is announced).
     */
    static List<Notice> notices(long from, long to, NavigableMap<Long, Integer> starts, long interval,
                                long warningLead, boolean warning, boolean start) {
        List<Notice> due = new ArrayList<>();
        starts.forEach((slotStart, percent) -> {
            long warnAt = slotStart - warningLead;
            if (warning && from < warnAt && warnAt <= to && !starts.containsKey(slotStart - interval)) {
                due.add(new Notice(NoticeKind.WARNING, slotStart, percent));
            }
            if (start && from < slotStart && slotStart <= to) {
                due.add(new Notice(NoticeKind.START, slotStart, percent));
            }
        });
        return due;
    }

    /** The city promotion running at {@code now}, if any. */
    static Optional<Notice> active(long now, NavigableMap<Long, Integer> starts, long interval) {
        Map.Entry<Long, Integer> slot = starts.floorEntry(now);
        return slot != null && now < slot.getKey() + interval
                ? Optional.of(new Notice(NoticeKind.START, slot.getKey(), slot.getValue())) : Optional.empty();
    }

    /** Members of the city who are online right now. */
    static List<UUID> recipients(Collection<UUID> members, Predicate<UUID> online) {
        return members.stream().filter(online).toList();
    }
}
