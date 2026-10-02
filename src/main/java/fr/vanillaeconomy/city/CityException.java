package fr.vanillaeconomy.city;

/** A refused city action: message key of messages.yml (section cities) + its variables. */
public final class CityException extends Exception {

    private final String key;
    private final Object[] vars;

    public CityException(String key, Object... vars) {
        super(key, null, false, false);
        this.key = key;
        this.vars = vars;
    }

    public String key() {
        return key;
    }

    public Object[] vars() {
        return vars;
    }
}
