package fr.vanillaeconomy.city;

/** Roles inside a city and what each one may do (pure, unit-tested). */
public enum CityRole {
    OWNER("Propriétaire"),
    CO_OWNER("Co-propriétaire"),
    MEMBER("Membre");

    private final String label;

    CityRole(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Add a new member: owner and co-owners. */
    public boolean canAddMembers() {
        return this == OWNER || this == CO_OWNER;
    }

    /** Owner: anyone but himself. Co-owner: plain members only (never the owner or another co-owner). */
    public boolean canKick(CityRole target) {
        return switch (this) {
            case OWNER -> target != OWNER;
            case CO_OWNER -> target == MEMBER;
            case MEMBER -> false;
        };
    }

    /** Promote / demote co-owners: owner only, never on the owner himself. */
    public boolean canSetCoOwner(CityRole target) {
        return this == OWNER && target != OWNER;
    }

    public boolean canBuyTier() {
        return this == OWNER;
    }

    public boolean canDisband() {
        return this == OWNER;
    }

    /** The owner cannot leave: he must disband. */
    public boolean canLeave() {
        return this != OWNER;
    }
}
