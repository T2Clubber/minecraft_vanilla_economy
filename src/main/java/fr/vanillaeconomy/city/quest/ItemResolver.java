package fr.vanillaeconomy.city.quest;

import java.util.Optional;
import java.util.Set;

/** Looks items up for {@link QuestDefinitionLoader} (the server's registries, or a fake in tests). */
public interface ItemResolver {

    /** Material name (e.g. "SPONGE") of an existing item, empty if unknown or not an item. */
    Optional<String> material(String name);

    /** Material names of an item tag (e.g. "minecraft:decorated_pot_sherds"), empty if unknown. */
    Optional<Set<String>> tag(String key);
}
