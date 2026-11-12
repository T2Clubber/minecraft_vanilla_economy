package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.City;
import fr.vanillaeconomy.city.CityExtension;
import fr.vanillaeconomy.city.CityManager;
import fr.vanillaeconomy.city.ExtensionManager;
import fr.vanillaeconomy.city.quest.QuestProgressRepository.Contributor;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.storage.Database;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;

/**
 * Extension "Quêtes de cité" on the server side: city_quests.yml, purchase, chat messages
 * to the online members of the city (never Discord for players), staff journal. The
 * quests themselves are run by {@link CityQuestManager}.
 */
public final class CityQuestExtension implements CityQuestManager.Events {

    /** Summary of a (re)load of city_quests.yml. */
    public record LoadSummary(int quests, int disabled, int difficulties, List<String> warnings, boolean fileError) {
    }

    private final Plugin plugin;
    private final CityManager cities;
    private final ExtensionManager extensions;
    private final MessageConfig msg;
    private final StaffLog staffLog;
    private final Set<String> marketItems;
    private final String coinMaterial;
    private final CityQuestManager manager;

    public CityQuestExtension(Plugin plugin, Database db, CityManager cities, ExtensionManager extensions, MessageConfig msg,
                              StaffLog staffLog, Set<String> marketItems, String coinMaterial) {
        this.plugin = plugin;
        this.cities = cities;
        this.extensions = extensions;
        this.msg = msg;
        this.staffLog = staffLog;
        this.marketItems = Set.copyOf(marketItems);
        this.coinMaterial = coinMaterial;
        this.manager = new CityQuestManager(db, cities.config().questOptions, System::currentTimeMillis, new Random(), this);
        cities.onRemoval(city -> manager.forget(city.id()));
    }

    public CityQuestManager manager() {
        return manager;
    }

    public MessageConfig msg() {
        return msg;
    }

    public CityManager cities() {
        return cities;
    }

    public StaffLog staffLog() {
        return staffLog;
    }

    public boolean active(City city) {
        return extensions.active(city, CityExtension.QUESTS);
    }

    public List<Integer> citiesWithExtension() {
        return cities.all().stream().filter(this::active).map(City::id).toList();
    }

    // ------------------------------------------------------------------
    // city_quests.yml
    // ------------------------------------------------------------------

    /**
     * (Re)loads difficulties and quests. A broken file keeps the previous catalog; invalid
     * entries are ignored one by one. Only future draws are affected.
     */
    public LoadSummary reload() {
        File file = new File(plugin.getDataFolder(), "city_quests.yml");
        if (!file.exists()) {
            plugin.saveResource("city_quests.yml", false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            String error = "city_quests.yml illisible (" + e.getMessage().lines().findFirst().orElse("") + ") : quêtes inchangées";
            plugin.getLogger().warning(error);
            QuestCatalog previous = manager.catalog();
            return new LoadSummary(previous.drawable().size(), 0, previous.difficulties().size(), List.of(error), true);
        }
        QuestCatalog catalog = QuestDefinitionLoader.load(yaml, new BukkitItemResolver(), marketItems, coinMaterial,
                manager.options().allowMarketItems());
        manager.setCatalog(catalog);
        catalog.warnings().forEach(w -> plugin.getLogger().warning("city_quests.yml : " + w));
        int disabled = (int) catalog.quests().values().stream().filter(q -> !q.enabled()).count();
        plugin.getLogger().info("Quêtes de cité : " + (catalog.quests().size() - disabled) + " quête(s) active(s), "
                + disabled + " désactivée(s), " + catalog.difficulties().size() + " difficulté(s), "
                + catalog.warnings().size() + " avertissement(s).");
        if (catalog.drawable().isEmpty()) {
            plugin.getLogger().warning("Quêtes de cité : aucune quête valide, aucune nouvelle quête ne sera tirée "
                    + "(les quêtes en cours continuent).");
        }
        return new LoadSummary(catalog.quests().size() - disabled, disabled, catalog.difficulties().size(),
                catalog.warnings(), false);
    }

    /** Server registries: Material names and item tags. */
    private static final class BukkitItemResolver implements ItemResolver {
        @Override
        public Optional<String> material(String name) {
            Material material = name == null ? null : Material.matchMaterial(name.trim());
            return material != null && material.isItem() && !material.isAir() ? Optional.of(material.name()) : Optional.empty();
        }

        @Override
        public Optional<Set<String>> tag(String key) {
            NamespacedKey id = NamespacedKey.fromString(key);
            Tag<Material> tag = id == null ? null : Bukkit.getTag(Tag.REGISTRY_ITEMS, id, Material.class);
            if (tag == null) {
                return Optional.empty();
            }
            Set<String> names = new HashSet<>();
            tag.getValues().forEach(m -> names.add(m.name()));
            return Optional.of(names);
        }
    }

    // ------------------------------------------------------------------
    // Purchase
    // ------------------------------------------------------------------

    /** Extension bought: the first quest starts right away. */
    public void onPurchase(City city, CityExtension extension) {
        if (extension != CityExtension.QUESTS) {
            return;
        }
        try {
            manager.start(city.id());
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Première quête de " + city.name() + " impossible", e);
        }
    }

    // ------------------------------------------------------------------
    // Events -> chat (online members) and staff journal
    // ------------------------------------------------------------------

    @Override
    public void drawn(CityQuest quest) {
        City city = cities.byId(quest.cityId());
        if (city == null) {
            return;
        }
        broadcast(city, "quest_new", vars(quest, city));
        if (quest.resumed()) {
            broadcast(city, "quest_resume", vars(quest, city));
        }
    }

    @Override
    public void completed(CityQuest quest, List<Contributor> top) {
        cities.applyQuestReward(quest.cityId(), quest.reward());
        City city = cities.byId(quest.cityId());
        if (city == null) {
            return;
        }
        List<Object> vars = new ArrayList<>(List.of(vars(quest, city)));
        vars.add("top");
        vars.add(top(top, 3));
        broadcast(city, "quest_completed", vars.toArray());
        plugin.getLogger().info("Quête « " + quest.name() + " » réussie par " + city.name() + " : " + quest.reward()
                + " pièces versées au solde de la cité.");
        staffLog.city("Quête réussie : " + city.name(), "**" + quest.name() + "** (" + quest.difficultyName() + ", "
                + quest.target() + " items)\n**Récompense :** " + quest.reward() + " pièces versées au solde de la cité\n"
                + "**Contributeurs :** " + StaffLog.md(top(top, 5)));
    }

    @Override
    public void expired(CityQuest quest, boolean progressKept) {
        City city = cities.byId(quest.cityId());
        if (city != null) {
            broadcast(city, progressKept ? "quest_expired_kept" : "quest_expired", vars(quest, city));
        }
    }

    @Override
    public void nothingToDraw(int cityId) {
        plugin.getLogger().warning("Quêtes de cité : aucune quête valide dans city_quests.yml, aucune nouvelle quête tirée.");
    }

    /** Common placeholders of a quest. */
    public Object[] vars(CityQuest quest, City city) {
        long now = System.currentTimeMillis();
        return new Object[]{"city", city.name(), "name", quest.name(), "difficulty", difficulty(quest),
                "amount", quest.target(), "progress", quest.progress(), "target", quest.target(),
                "reward", Messages.coins(quest.reward()), "duration", hours(quest.durationHours()),
                "remaining", Messages.duration(quest.endsAt() - now), "passage", quest.passage(),
                "bar", QuestRules.bar(quest.progress(), quest.target(), 20)};
    }

    /** Online members of the city, main thread, no queue for the offline ones. */
    public void broadcast(City city, String key, Object... vars) {
        Component line = msg.get(key, vars);
        for (var member : city.members().keySet()) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) {
                player.sendMessage(line);
            }
        }
    }

    public static Component difficulty(CityQuest quest) {
        NamedTextColor color = NamedTextColor.NAMES.value(quest.difficultyColor());
        return Component.text(quest.difficultyName(), color == null ? NamedTextColor.WHITE : color);
    }

    public static String hours(long hours) {
        return hours % 24 == 0 && hours >= 48 ? (hours / 24) + " jours" : hours + " h";
    }

    /** "Alice (12), Bob (4)". */
    public static String top(List<Contributor> contributors, int n) {
        if (contributors.isEmpty()) {
            return "aucun";
        }
        return String.join(", ", contributors.stream().limit(n)
                .map(c -> CityManager.nameOf(c.player()) + " (" + c.amount() + ")").toList());
    }

    /** Item shown for a quest: its material, or the first one of a multi-item quest. */
    public static Material icon(CityQuest quest) {
        for (String name : quest.materials()) {
            Material material = Material.matchMaterial(name);
            if (material != null && material.isItem()) {
                return material;
            }
        }
        return Material.PAPER;
    }
}
