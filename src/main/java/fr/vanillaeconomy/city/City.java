package fr.vanillaeconomy.city;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory state of a city (authoritative copy of the database rows, kept in sync by
 * {@link CityManager}). Event handlers only read this, never the database.
 */
public final class City {

    public record Contribution(UUID player, long amount, long timestamp) {
    }

    private final int id;
    private String name;
    private UUID owner;
    private final String world;
    private final int centerX;
    private final int centerZ;
    /** Height where the founder stood, null for cities founded before it was recorded. */
    private final Integer centerY;
    private int tier;
    private long balance;
    private final long createdAt;
    private Territory territory;
    private final Map<UUID, CityRole> members = new LinkedHashMap<>();
    private final List<Contribution> contributions = new ArrayList<>();
    private final Set<CityExtension> extensions = EnumSet.noneOf(CityExtension.class);

    City(int id, String name, UUID owner, String world, int centerX, Integer centerY, int centerZ, int tier,
         long balance, long createdAt, int size) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.tier = tier;
        this.balance = balance;
        this.createdAt = createdAt;
        this.territory = new Territory(world, centerX, centerZ, size);
    }

    public int id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public String world() { return world; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public Integer centerY() { return centerY; }
    public int tier() { return tier; }
    public long balance() { return balance; }
    public long createdAt() { return createdAt; }
    public Territory territory() { return territory; }

    public Map<UUID, CityRole> members() {
        return Collections.unmodifiableMap(members);
    }

    /** Most recent first. */
    public List<Contribution> contributions() {
        List<Contribution> list = new ArrayList<>(contributions);
        Collections.reverse(list);
        return list;
    }

    /** Total contributed per player, highest first. */
    public Map<UUID, Long> contributionTotals() {
        Map<UUID, Long> totals = new LinkedHashMap<>();
        contributions.forEach(c -> totals.merge(c.player(), c.amount(), Long::sum));
        return totals.entrySet().stream()
                .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    public CityRole roleOf(UUID player) {
        return members.get(player);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public boolean hasExtension(CityExtension extension) {
        return extensions.contains(extension);
    }

    public Set<CityExtension> extensions() {
        return Collections.unmodifiableSet(extensions);
    }

    // --- mutations, only through CityManager after a successful commit ---

    void rename(String newName) { this.name = newName; }
    void setBalance(long balance) { this.balance = balance; }

    void setTier(int tier, int size) {
        this.tier = tier;
        this.territory = territory.withSize(size);
    }

    void putMember(UUID player, CityRole role) {
        members.put(player, role);
        if (role == CityRole.OWNER) {
            owner = player;
        }
    }

    void removeMember(UUID player) { members.remove(player); }

    void addContribution(Contribution c) { contributions.add(c); }

    void addExtension(CityExtension extension) { extensions.add(extension); }
}
