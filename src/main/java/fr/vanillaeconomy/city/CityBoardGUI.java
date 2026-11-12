package fr.vanillaeconomy.city;

import com.destroystokyo.paper.profile.PlayerProfile;
import fr.vanillaeconomy.city.CityRules.Tier;
import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.util.InventoryGuard;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
import fr.vanillaeconomy.util.ServerTime;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /cityboard &lt;cité&gt;: 54-slot board for members, with four tabs (MEMBRES, PALIERS,
 * TRÉSORERIE, EXTENSIONS); the quests page opens from the Quêtes de cité extension. Every click is cancelled and its rights are checked again on the server
 * (role read from the live city), skins are loaded off the main thread.
 */
public final class CityBoardGUI implements InventoryHolder {

    /** QUESTS is not a tab of its own: the page opened by the Quêtes de cité extension. */
    public enum Tab { MEMBERS, TIERS, TREASURY, EXTENSIONS, QUESTS }

    private static final int SIZE = 54;
    /** Tabs on the first row, one slot out of two. */
    private static final int TAB_MEMBERS = 1;
    private static final int TAB_TIERS = 3;
    private static final int TAB_TREASURY = 5;
    private static final int TAB_EXTENSIONS = 7;
    /** Quests page: the quest, then the deposit, ranking and history buttons. */
    private static final int QUEST_ITEM = 13;
    private static final int QUEST_DEPOSIT = 29;
    private static final int QUEST_RANKING = 31;
    private static final int QUEST_HISTORY = 33;
    private static final int QUEST_HISTORY_PER_PAGE = 27;   // slots 9..35
    private static final int QUEST_CONTRIBUTORS_SHOWN = 15;
    private static final int QUEST_TOTALS = 47;
    private static final int QUEST_BACK = 51;
    /**
     * Extensions on row 3, two slots apart and centred, in the order they unlock (tier
     * required); the skin check button below its extension.
     */
    private static final int EXTENSIONS_ROW_CENTRE = 22;
    private static final int PREV = 45;
    private static final int INFO = 49;
    private static final int NEXT = 53;
    private static final int MEMBERS_PER_PAGE = 36;     // slots 9..44
    private static final int BALANCE = 9;
    private static final int RANKING_FIRST = 11;        // slots 11..17
    private static final int RANKING_SIZE = 7;
    private static final int RECENT_FIRST = 18;         // slots 18..44
    private static final int RECENT_PER_PAGE = 27;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE);

    /** Completed skins, shared by every board. */
    private static final Map<UUID, PlayerProfile> PROFILES = new ConcurrentHashMap<>();
    private static final Set<UUID> LOADING = ConcurrentHashMap.newKeySet();

    private final Services services;
    private final Player viewer;
    private final int cityId;
    private final Inventory inventory;
    private Tab tab;
    private int page;
    /** Slot -> member shown there (members tab). */
    private final Map<Integer, UUID> memberSlots = new HashMap<>();
    /** Slot -> tier level shown there (tiers tab). */
    private final Map<Integer, Integer> tierSlots = new HashMap<>();
    /** Slot -> extension shown there (extensions tab). */
    private final Map<Integer, CityExtension> extensionSlots = new HashMap<>();
    /** Slot -> extension whose conditions the button there checks ("Vérifier les skins / les beacons"). */
    private final Map<Integer, CityExtension> checkSlots = new HashMap<>();
    /** Quests tab showing the history instead of the current quest. */
    private boolean questHistory;

    /** What the board needs, shared by all instances. */
    public record Services(Plugin plugin, CityManager cities, CurrencyManager currency, MessageConfig msg,
                           CityNotifier notifier, StaffLog staffLog, ExtensionManager extensions,
                           fr.vanillaeconomy.city.quest.CityQuestExtension quests,
                           fr.vanillaeconomy.city.quest.QuestDepositService deposits) {
    }

    private CityBoardGUI(Services services, Player viewer, City city, Tab tab) {
        this.services = services;
        this.viewer = viewer;
        this.cityId = city.id();
        this.tab = tab;
        this.inventory = Bukkit.createInventory(this, SIZE,
                Messages.parse("<dark_aqua>Cité <aqua><name>", Messages.p("name", city.name())));
    }

    public static void open(Services services, Player viewer, City city, Tab tab) {
        CityBoardGUI gui = new CityBoardGUI(services, viewer, city, tab);
        if (gui.render()) {
            viewer.openInventory(gui.inventory);
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Live city, or null (and the board is closed) if it vanished or the viewer left it. */
    private City liveCity() {
        City city = services.cities().byId(cityId);
        if (city == null || !city.isMember(viewer.getUniqueId())) {
            viewer.closeInventory();
            if (city != null) {
                services.msg().send(viewer, "not_member", "city", city.name());
            }
            return null;
        }
        return city;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    boolean render() {
        City city = liveCity();
        if (city == null) {
            return false;
        }
        memberSlots.clear();
        tierSlots.clear();
        extensionSlots.clear();
        checkSlots.clear();
        ItemStack[] contents = new ItemStack[SIZE];
        java.util.Arrays.fill(contents, filler());
        contents[TAB_MEMBERS] = tabItem(Material.PLAYER_HEAD, "<yellow>Membres", Tab.MEMBERS);
        contents[TAB_TIERS] = tabItem(Material.FILLED_MAP, "<yellow>Paliers", Tab.TIERS);
        contents[TAB_TREASURY] = tabItem(Material.GOLD_INGOT, "<yellow>Trésorerie", Tab.TREASURY);
        contents[TAB_EXTENSIONS] = tabItem(Material.ENDER_PEARL, "<yellow>Extensions", Tab.EXTENSIONS);
        contents[INFO] = infoItem(city);
        int pages = switch (tab) {
            case MEMBERS -> renderMembers(city, contents);
            case TIERS -> renderTiers(city, contents);
            case TREASURY -> renderTreasury(city, contents);
            case EXTENSIONS -> renderExtensions(city, contents);
            case QUESTS -> renderQuests(city, contents);
        };
        if (page > 0) {
            contents[PREV] = named(Material.ARROW, "<white>Page précédente", List.of());
        }
        if (page < pages - 1) {
            contents[NEXT] = named(Material.ARROW, "<white>Page suivante", List.of());
        }
        inventory.setContents(contents);
        return true;
    }

    private int renderMembers(City city, ItemStack[] contents) {
        CityRole me = city.roleOf(viewer.getUniqueId());
        List<Map.Entry<UUID, CityRole>> members = new ArrayList<>(city.members().entrySet());
        members.sort(Comparator.<Map.Entry<UUID, CityRole>>comparingInt(e -> e.getValue().ordinal())
                .thenComparing(e -> CityManager.nameOf(e.getKey()), String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (members.size() + MEMBERS_PER_PAGE - 1) / MEMBERS_PER_PAGE);
        page = Math.min(page, pages - 1);
        int from = page * MEMBERS_PER_PAGE;
        for (int i = 0; i < MEMBERS_PER_PAGE && from + i < members.size(); i++) {
            UUID uuid = members.get(from + i).getKey();
            CityRole role = members.get(from + i).getValue();
            List<Component> lore = new ArrayList<>();
            lore.add(Messages.item("<gray>Rôle : <white><role>", Messages.p("role", role.label())));
            if (me.canSetCoOwner(role)) {
                lore.add(Messages.item(role == CityRole.CO_OWNER
                        ? "<yellow>Clic gauche : retirer co-propriétaire" : "<yellow>Clic gauche : nommer co-propriétaire"));
            }
            if (me.canKick(role) && !uuid.equals(viewer.getUniqueId())) {
                lore.add(Messages.item("<red>Shift + clic droit : exclure"));
            }
            contents[9 + i] = head(uuid, (role == CityRole.OWNER ? "<gold>" : role == CityRole.CO_OWNER ? "<aqua>" : "<white>")
                    + "<name>", lore);
            memberSlots.put(9 + i, uuid);
        }
        return pages;
    }

    private int renderTiers(City city, ItemStack[] contents) {
        CityRole me = city.roleOf(viewer.getUniqueId());
        List<Tier> tiers = services.cities().config().tiers;
        int start = 22 - Math.min(tiers.size(), 9) / 2;
        for (int i = 0; i < tiers.size() && i < 27; i++) {
            Tier t = tiers.get(i);
            boolean owned = t.level() <= city.tier();
            boolean next = t.level() == city.tier() + 1;
            List<Component> lore = new ArrayList<>();
            lore.add(Messages.item("<gray>Territoire : <white><size>x<size>", Messages.p("size", t.size())));
            if (t.level() > 1) {
                lore.add(Messages.item("<gray>Prix : <gold><price></gold> <dark_gray>(solde de la cité)",
                        Messages.p("price", Messages.coins(t.price()))));
            }
            String status;
            Material mat;
            if (owned) {
                status = "<green>✔ Acquis";
                mat = Material.LIME_CONCRETE;
            } else if (next) {
                status = "<yellow>Prochain palier";
                mat = Material.YELLOW_CONCRETE;
                lore.add(Messages.item("<gray>Solde de la cité : <gold><balance>", Messages.p("balance", Messages.coins(city.balance()))));
                if (me.canBuyTier()) {
                    lore.add(Messages.item(city.balance() >= t.price()
                            ? "<yellow>Clic : acheter (confirmation)" : "<red>Solde de la cité insuffisant"));
                }
            } else {
                status = "<dark_gray>À venir";
                mat = Material.GRAY_CONCRETE;
            }
            lore.add(Messages.item(status));
            int slot = start + i;
            contents[slot] = named(mat, "<white>Palier " + t.level(), lore);
            tierSlots.put(slot, t.level());
        }
        return 1;
    }

    private int renderTreasury(City city, ItemStack[] contents) {
        ItemStack coin = services.currency().icon(services.currency().customModelData());
        ItemMeta meta = coin.getItemMeta();
        meta.displayName(Messages.item("<gold>Solde de la cité : <yellow><balance>",
                Messages.p("balance", Messages.coins(city.balance()))));
        meta.lore(List.of(Messages.item("<gray>Versez avec <white>/city contribute <name> <montant>",
                Messages.p("name", city.name()))));
        coin.setItemMeta(meta);
        contents[BALANCE] = coin;

        int rank = 0;
        for (var e : city.contributionTotals().entrySet()) {
            if (rank >= RANKING_SIZE) {
                break;
            }
            contents[RANKING_FIRST + rank] = head(e.getKey(), "<yellow>#" + (rank + 1) + " <white><name>",
                    List.of(Messages.item("<gray>Total versé : <gold><amount>", Messages.p("amount", Messages.coins(e.getValue())))));
            rank++;
        }

        List<City.Contribution> recent = city.contributions();
        int pages = Math.max(1, (recent.size() + RECENT_PER_PAGE - 1) / RECENT_PER_PAGE);
        page = Math.min(page, pages - 1);
        int from = page * RECENT_PER_PAGE;
        for (int i = 0; i < RECENT_PER_PAGE && from + i < recent.size(); i++) {
            City.Contribution c = recent.get(from + i);
            contents[RECENT_FIRST + i] = named(Material.PAPER,
                    "<white>" + escape(CityManager.nameOf(c.player())) + " <green>+" + Messages.coins(c.amount()),
                    List.of(Messages.item("<dark_gray>" + DATE.format(Instant.ofEpochMilli(c.timestamp()).atZone(ServerTime.zone())))));
        }
        return pages;
    }

    private int renderExtensions(City city, ItemStack[] contents) {
        CityRole me = city.roleOf(viewer.getUniqueId());
        CityConfig config = services.cities().config();
        List<CityExtension> all = java.util.Arrays.stream(CityExtension.values())
                .sorted(java.util.Comparator.comparingInt(config::minTier)).toList();   // stable: enum order on a tie
        int first = EXTENSIONS_ROW_CENTRE - (all.size() - 1);
        for (int i = 0; i < all.size(); i++) {
            CityExtension ext = all.get(i);
            long price = config.price(ext);
            boolean owned = city.hasExtension(ext);
            boolean enabled = config.enabled(ext);
            int minTier = config.minTier(ext);
            List<Component> lore = new ArrayList<>(description(city, ext, config));
            lore.add(Component.empty());
            if (!enabled) {
                lore.add(Messages.item("<red>Désactivée par l'administration"));
            } else if (owned) {
                lore.add(Messages.item("<green>✔ Acquise"));
                if (ext == CityExtension.QUESTS) {
                    lore.add(Messages.item("<yellow>Clic : ouvrir les quêtes"));
                }
            } else {
                lore.add(Messages.item("<gray>Prix : <gold><price></gold> <dark_gray>(solde de la cité)",
                        Messages.p("price", Messages.coins(price))));
                lore.add(Messages.item("<gray>Solde de la cité : <gold><balance>", Messages.p("balance", Messages.coins(city.balance()))));
                lore.add(Messages.item((city.tier() >= minTier ? "<green>✔" : "<red>✘") + " <gray>Palier <tier> minimum "
                        + "<dark_gray>(palier actuel : <current>)", Messages.p("tier", minTier), Messages.p("current", city.tier())));
                if (ext == CityExtension.PROMO_FRENZY) {
                    lore.add(Messages.item("<gray>✦ Les <n> skins d'idiots marchands dans la cité :",
                            Messages.p("n", config.frenzyTypes.size())));
                    lore.add(Messages.item("<white>  <types>", Messages.p("types", services.extensions().labels(config.frenzyTypes))));
                } else if (ext == CityExtension.BEACON) {
                    lore.add(Messages.item("<gray>✦ <n> beacons dans la cité, pyramide à <levels> étages,",
                            Messages.p("n", config.beaconRequired), Messages.p("levels", config.beaconPyramid)));
                    lore.add(Messages.item("<gray>  allumés et avec un effet primaire choisi"));
                }
                if (city.tier() < minTier) {
                    lore.add(Messages.item("<red>Débloquée au palier <tier>", Messages.p("tier", minTier)));
                } else if (me.canBuyExtension()) {
                    lore.add(Messages.item(city.balance() >= price
                            ? "<yellow>Clic : acheter (confirmation)" : "<red>Solde de la cité insuffisant"));
                } else {
                    lore.add(Messages.item("<dark_gray>Achat réservé au propriétaire"));
                }
            }
            boolean active = enabled && owned;
            Material icon = !enabled ? Material.GRAY_DYE : switch (ext) {
                case TELEPORT -> owned ? Material.ENDER_EYE : Material.ENDER_PEARL;
                case PROMO_FRENZY -> owned ? Material.EMERALD_BLOCK : Material.EMERALD;
                case BEACON -> owned ? Material.BEACON : Material.NETHER_STAR;
                case QUESTS -> owned ? Material.WRITABLE_BOOK : Material.BOOK;
            };
            ItemStack item = named(icon, (!enabled ? "<gray>" : owned ? "<green>" : "<white>") + ext.label(), lore);
            if (active) {
                ItemMeta meta = item.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
            int slot = first + 2 * i;
            contents[slot] = item;
            extensionSlots.put(slot, ext);
            if (ext == CityExtension.PROMO_FRENZY && enabled && !owned) {
                checkSlots.put(slot + 9, ext);
                contents[slot + 9] = named(Material.SPYGLASS, "<aqua>Vérifier les skins",
                        List.of(Messages.item("<gray>Compte les idiots marchands présents"),
                                Messages.item("<gray>dans les limites de la cité."),
                                Messages.item("<yellow>Clic : vérifier")));
            } else if (ext == CityExtension.BEACON && enabled && !owned) {
                checkSlots.put(slot + 9, ext);
                contents[slot + 9] = named(Material.SPYGLASS, "<aqua>Vérifier les beacons",
                        List.of(Messages.item("<gray>Compte les beacons valides"),
                                Messages.item("<gray>dans les limites de la cité."),
                                Messages.item("<dark_gray>Une vérification toutes les <s> s", Messages.p("s",
                                        config.beaconCheckCooldownMillis / 1000)),
                                Messages.item("<yellow>Clic : vérifier")));
            }
        }
        return 1;
    }

    private List<Component> description(City city, CityExtension ext, CityConfig config) {
        return switch (ext) {
            case TELEPORT -> List.of(
                    Messages.item("<gray>Les membres se téléportent au centre de la cité"),
                    Messages.item("<gray>avec <white>/city tp <name></white>, depuis n'importe quel monde.",
                            Messages.p("name", city.name())),
                    Messages.item("<gray>Chaque téléportation coûte <gold><cost></gold> <dark_gray>(solde personnel)",
                            Messages.p("cost", Messages.coins(config.teleportUseCost))));
            case BEACON -> List.of(
                    Messages.item("<gray>Les effets des beacons de la cité s'appliquent"),
                    Messages.item("<gray>à tout joueur présent dans la cité, quelle que"),
                    Messages.item("<gray>soit sa distance aux beacons<members>.",
                            Messages.p("members", config.beaconMembersOnly ? " (membres uniquement)" : "")));
            case QUESTS -> List.of(
                    Messages.item("<gray>Une quête à la fois : rassembler des items rares"),
                    Messages.item("<gray>avant l'échéance. Récompense versée au solde"),
                    Messages.item("<gray>de la cité. Clic sur ce livre une fois acquise,"),
                    Messages.item("<gray>ou <white>/city quest</white>."));
            case PROMO_FRENZY -> List.of(
                    Messages.item("<gray>Les idiots marchands de la cité font <white><n> promos</white> de plus",
                            Messages.p("n", config.frenzyPromosPerDay)),
                    Messages.item("<gray>par jour (<min> à <max> %), en plus des promos globales.",
                            Messages.p("min", config.frenzyMinPercent), Messages.p("max", config.frenzyMaxPercent)),
                    Messages.item("<gray>Les membres sont prévenus dans le chat."));
        };
    }

    // ------------------------------------------------------------------
    // Quests page (opened from the Quêtes de cité extension)
    // ------------------------------------------------------------------

    private int renderQuests(City city, ItemStack[] contents) {
        var quests = services.quests();
        if (!quests.active(city)) {
            // extension disabled by the administration while the page was open
            tab = Tab.EXTENSIONS;
            return renderExtensions(city, contents);
        }
        if (questHistory) {
            return renderQuestHistory(city, contents);
        }
        contents[QUEST_BACK] = named(Material.ARROW, "<white>Retour aux extensions", List.of());
        var current = quests.manager().current(city.id());
        if (current.isEmpty()) {
            contents[QUEST_ITEM] = named(Material.BARRIER, "<gray>Aucune quête en cours",
                    List.of(Messages.item("<gray>Aucune quête disponible pour le moment.")));
        } else {
            var quest = current.get();
            long now = System.currentTimeMillis();
            boolean done = quest.status() == fr.vanillaeconomy.city.quest.CityQuest.Status.COMPLETED;
            List<Component> lore = new ArrayList<>();
            lore.add(Messages.item("<gray>Difficulté : ").append(fr.vanillaeconomy.city.quest.CityQuestExtension.difficulty(quest)));
            if (quest.hint() != null) {
                lore.add(Messages.item("<gray><hint>", Messages.p("hint", quest.hint())));
            }
            lore.add(Component.empty());
            lore.add(Messages.item((done ? "<green>" : "<aqua>") + "<bar> <white><progress>/<target>",
                    Messages.p("bar", fr.vanillaeconomy.city.quest.QuestRules.bar(quest.progress(), quest.target(), 20)),
                    Messages.p("progress", quest.progress()), Messages.p("target", quest.target())));
            if (quest.resumed()) {
                lore.add(Messages.item("<light_purple>Reprise : <white><p>/<t></white> déjà accumulés (passage n°<n>)",
                        Messages.p("p", quest.startProgress()), Messages.p("t", quest.target()), Messages.p("n", quest.passage())));
            }
            lore.add(Messages.item(done ? "<green>✔ Terminée <gray>— prochaine quête dans <white><time>"
                    : "<gray>Temps restant : <white><time>", Messages.p("time", Messages.duration(quest.endsAt() - now))));
            lore.add(Messages.item("<gray>Durée : <white><d>", Messages.p("d",
                    fr.vanillaeconomy.city.quest.CityQuestExtension.hours(quest.durationHours()))));
            lore.add(Messages.item("<gray>Récompense : <gold><r></gold> <dark_gray>(solde de la cité)",
                    Messages.p("r", Messages.coins(quest.reward()))));
            ItemStack item = named(fr.vanillaeconomy.city.quest.CityQuestExtension.icon(quest), "<white><name>", lore,
                    Messages.p("name", quest.name()));
            if (done) {
                ItemMeta meta = item.getItemMeta();
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
            contents[QUEST_ITEM] = item;
            contents[QUEST_DEPOSIT] = named(done ? Material.GRAY_DYE : Material.HOPPER,
                    done ? "<gray>Quête terminée" : "<green>Déposer", done ? List.of() : List.of(
                            Messages.item("<gray>Dépose tous les items utiles"),
                            Messages.item("<gray>de votre inventaire (jamais plus que"),
                            Messages.item("<gray>ce qu'il reste à rassembler)."),
                            Messages.item("<red>Les items déposés sont définitivement consommés."),
                            Messages.item("<yellow>Clic : déposer")));
            List<Component> ranking = new ArrayList<>();
            try {
                var top = quests.manager().ranking(quest);
                for (int i = 0; i < top.size() && i < 10; i++) {
                    ranking.add(Messages.item("<gray><rank>. <white><player></white> : <gold><amount>",
                            Messages.p("rank", i + 1), Messages.p("player", CityManager.nameOf(top.get(i).player())),
                            Messages.p("amount", top.get(i).amount())));
                }
            } catch (java.sql.SQLException e) {
                ranking.add(Messages.item("<red>Classement indisponible"));
            }
            if (ranking.isEmpty()) {
                ranking.add(Messages.item("<gray>Aucune contribution pour l'instant."));
            }
            contents[QUEST_RANKING] = named(Material.GOLDEN_HELMET, "<gold>Classement de la quête", ranking);
        }
        contents[QUEST_HISTORY] = named(Material.BOOKSHELF, "<aqua>Historique",
                List.of(Messages.item("<gray>Quêtes réussies par la cité."), Messages.item("<yellow>Clic : afficher")));
        return 1;
    }

    private int renderQuestHistory(City city, ItemStack[] contents) {
        var manager = services.quests().manager();
        try {
            int size = manager.historySize(city.id());
            int pages = Math.max(1, (size + QUEST_HISTORY_PER_PAGE - 1) / QUEST_HISTORY_PER_PAGE);
            page = Math.min(page, pages - 1);
            var entries = manager.history(city.id(), page, QUEST_HISTORY_PER_PAGE);
            for (int i = 0; i < entries.size(); i++) {
                var q = entries.get(i);
                List<Component> lore = new ArrayList<>();
                lore.add(Messages.item("<gray>Difficulté : ").append(fr.vanillaeconomy.city.quest.CityQuestExtension.difficulty(q)));
                lore.add(Messages.item("<gray><t> items rassemblés", Messages.p("t", q.target())));
                lore.add(Messages.item("<gray>Réussie le <white><date>", Messages.p("date",
                        fr.vanillaeconomy.city.quest.QuestCommands.date(q.completedAt()))));
                lore.add(Messages.item("<gray>Récompense : <gold><r>", Messages.p("r", Messages.coins(q.reward()))));
                lore.add(Component.empty());
                lore.add(Messages.item("<aqua>Contributeurs :"));
                // every passage of the attempt, as in the ranking of the quest
                var contributors = manager.ranking(q);
                for (int k = 0; k < contributors.size() && k < QUEST_CONTRIBUTORS_SHOWN; k++) {
                    lore.add(Messages.item("<gray>• <white><player></white> : <gold><amount>",
                            Messages.p("player", CityManager.nameOf(contributors.get(k).player())),
                            Messages.p("amount", contributors.get(k).amount())));
                }
                if (contributors.size() > QUEST_CONTRIBUTORS_SHOWN) {
                    lore.add(Messages.item("<dark_gray>… et <n> autre(s)", Messages.p("n",
                            contributors.size() - QUEST_CONTRIBUTORS_SHOWN)));
                }
                contents[9 + i] = named(fr.vanillaeconomy.city.quest.CityQuestExtension.icon(q),
                        "<green>✔ <white><name>", lore, Messages.p("name", q.name()));
            }
            if (entries.isEmpty()) {
                contents[22] = named(Material.GRAY_DYE, "<gray>Aucune quête réussie pour l'instant", List.of());
            }
            var completion = manager.completion(city.id());
            List<Component> lore = List.of(Messages.item("<gray>Quêtes réalisées : <green><n></green>/<white><total>",
                    Messages.p("n", completion.completed()), Messages.p("total", completion.total())));
            contents[QUEST_TOTALS] = named(Material.BOOK, "<aqua>Récapitulatif", lore);
            contents[QUEST_BACK] = named(Material.ARROW, "<white>Retour à la quête", List.of());
            return pages;
        } catch (java.sql.SQLException e) {
            contents[22] = named(Material.BARRIER, "<red>Historique indisponible", List.of());
            return 1;
        }
    }

    private void questClick(City city, int slot) {
        if (!services.quests().active(city)) {
            return;
        }
        if (questHistory) {
            if (slot == QUEST_BACK) {
                questHistory = false;
                page = 0;
                render();
            }
            return;
        }
        if (slot == QUEST_BACK) {
            switchTab(Tab.EXTENSIONS);
        } else if (slot == QUEST_HISTORY) {
            questHistory = true;
            page = 0;
            render();
        } else if (slot == QUEST_DEPOSIT) {
            // membership, extension and quest state checked again by the service
            try {
                var result = services.deposits().deposit(viewer, city, null);
                services.staffLog().action(viewer, "/cityboard " + city.name(), result.accepted() + " items déposés pour « "
                        + StaffLog.md(result.quest().name()) + " » (" + result.quest().progress() + "/"
                        + result.quest().target() + ")" + (result.completed() ? " : quête réussie" : ""));
            } catch (CityException e) {
                services.msg().send(viewer, e.key(), e.vars());
            }
            refreshOpenBoards();
        }
    }

    private ItemStack infoItem(City city) {
        CityRole me = city.roleOf(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Messages.item("<gray>Propriétaire : <white><owner>", Messages.p("owner", CityManager.nameOf(city.owner()))));
        lore.add(Messages.item("<gray>Palier <white><tier></white> · <white><size>x<size>",
                Messages.p("tier", city.tier()), Messages.p("size", city.territory().size())));
        lore.add(Messages.item("<gray>Membres : <white><n>", Messages.p("n", city.members().size())));
        if (!city.extensions().isEmpty()) {
            lore.add(Messages.item("<gray>Extensions : <white><list>", Messages.p("list",
                    String.join(", ", city.extensions().stream().map(CityExtension::label).toList()))));
        }
        lore.add(Messages.item("<gray>Votre rôle : <white><role>", Messages.p("role", me.label())));
        if (me.canAddMembers()) {
            lore.add(Component.empty());
            lore.add(Messages.item("<yellow>Ajouter un membre : <white>/city add <joueur>"));
        }
        return named(Material.OAK_SIGN, "<aqua>" + escape(city.name()), lore);
    }

    private ItemStack tabItem(Material mat, String name, Tab target) {
        // the quests page belongs to the extensions tab
        boolean shown = tab == target || (tab == Tab.QUESTS && target == Tab.EXTENSIONS);
        ItemStack item = named(mat, name, List.of(Messages.item(shown ? "<green>Onglet affiché" : "<gray>Clic : afficher")));
        if (shown) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack head(UUID uuid, String nameFormat, List<Component> lore) {
        ItemStack skull = ItemStack.of(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        String name = CityManager.nameOf(uuid);
        PlayerProfile profile = PROFILES.get(uuid);
        if (profile != null) {
            meta.setPlayerProfile(profile);
        } else {
            requestSkin(uuid, name);
        }
        meta.displayName(Messages.item(nameFormat, Messages.p("name", name)));
        meta.lore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    /** Fetches the skin asynchronously, then refreshes the open boards on the main thread. */
    private void requestSkin(UUID uuid, String name) {
        if (!LOADING.add(uuid)) {
            return;
        }
        Bukkit.createProfile(uuid, name).update().whenComplete((profile, error) ->
                Bukkit.getScheduler().runTask(services.plugin(), () -> {
                    LOADING.remove(uuid);
                    if (profile != null && profile.hasTextures()) {
                        PROFILES.put(uuid, profile);
                        refreshOpenBoards();
                    }
                }));
    }

    static void refreshOpenBoards() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false) instanceof CityBoardGUI board) {
                board.render();
            }
        }
    }

    /** {@code vars}: placeholders of the name (texts such as a quest name are never parsed). */
    private static ItemStack named(Material mat, String name, List<Component> lore,
                                   net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... vars) {
        ItemStack item = ItemStack.of(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Messages.item(name, vars));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack filler() {
        ItemStack pane = ItemStack.of(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.setHideTooltip(true);
        pane.setItemMeta(meta);
        return pane;
    }

    /** Player and city names are restricted to [A-Za-z0-9_-], so only '<' needs care. */
    private static String escape(String text) {
        return text.replace("<", "\\<");
    }

    // ------------------------------------------------------------------
    // Clicks (rights re-checked by CityManager at every action)
    // ------------------------------------------------------------------

    void click(int slot, ClickType click) {
        City city = liveCity();
        if (city == null) {
            return;
        }
        switch (slot) {
            case TAB_MEMBERS -> switchTab(Tab.MEMBERS);
            case TAB_TIERS -> switchTab(Tab.TIERS);
            case TAB_TREASURY -> switchTab(Tab.TREASURY);
            case TAB_EXTENSIONS -> switchTab(Tab.EXTENSIONS);
            case PREV -> {
                page = Math.max(0, page - 1);
                render();
            }
            case NEXT -> {
                page++;
                render();
            }
            default -> {
                if (tab == Tab.MEMBERS && memberSlots.containsKey(slot)) {
                    memberClick(city, memberSlots.get(slot), click);
                } else if (tab == Tab.TIERS && tierSlots.containsKey(slot)) {
                    tierClick(city, tierSlots.get(slot));
                } else if (tab == Tab.EXTENSIONS && extensionSlots.containsKey(slot)) {
                    extensionClick(city, extensionSlots.get(slot));
                } else if (tab == Tab.EXTENSIONS && checkSlots.get(slot) == CityExtension.PROMO_FRENZY) {
                    skinCheck(city);
                } else if (tab == Tab.EXTENSIONS && checkSlots.get(slot) == CityExtension.BEACON) {
                    beaconCheck(city);
                } else if (tab == Tab.QUESTS) {
                    questClick(city, slot);
                }
            }
        }
    }

    private void switchTab(Tab target) {
        tab = target;
        page = 0;
        render();
    }

    private void memberClick(City city, UUID target, ClickType click) {
        CityRole me = city.roleOf(viewer.getUniqueId());
        CityRole targetRole = city.roleOf(target);
        if (targetRole == null) {
            render();
            return;
        }
        String targetName = CityManager.nameOf(target);
        if (click == ClickType.LEFT && me.canSetCoOwner(targetRole)) {
            boolean promote = targetRole != CityRole.CO_OWNER;
            run(() -> {
                services.cities().setCoOwner(city, viewer.getUniqueId(), target, promote);
                services.msg().send(viewer, promote ? "coowner_set" : "coowner_unset", "player", targetName, "city", city.name());
                services.notifier().notify(target, promote ? "coowner_notify_set" : "coowner_notify_unset", "city", city.name());
                services.staffLog().action(viewer, "/cityboard " + city.name(), StaffLog.md(targetName)
                        + (promote ? " promu" : " n'est plus") + " co-propriétaire de **" + StaffLog.md(city.name()) + "**");
            });
            render();
        } else if (click == ClickType.SHIFT_RIGHT && me.canKick(targetRole) && !target.equals(viewer.getUniqueId())) {
            ConfirmGUI.open(viewer, "Exclure " + targetName + " ?", () -> {
                run(() -> {
                    services.cities().kick(city, viewer.getUniqueId(), target);
                    services.msg().send(viewer, "kicked", "player", targetName, "city", city.name());
                    services.notifier().notify(target, "kicked_notify", "city", city.name());
                    services.staffLog().action(viewer, "/cityboard " + city.name(),
                            StaffLog.md(targetName) + " exclu de **" + StaffLog.md(city.name()) + "**");
                });
                reopen(Tab.MEMBERS);
            }, () -> reopen(Tab.MEMBERS));
        }
    }

    private void tierClick(City city, int level) {
        if (level != city.tier() + 1 || !city.roleOf(viewer.getUniqueId()).canBuyTier()) {
            return;
        }
        Tier next;
        try {
            next = services.cities().checkUpgrade(city, viewer.getUniqueId());
        } catch (CityException e) {
            services.msg().send(viewer, e.key(), e.vars());
            return;
        }
        ConfirmGUI.open(viewer, "Palier " + next.level() + " pour " + Messages.coins(next.price()) + " ?", () -> {
            run(() -> {
                Tier bought = services.cities().upgrade(city, viewer.getUniqueId());
                services.msg().send(viewer, "upgraded", "city", city.name(), "tier", bought.level(), "size", bought.size());
            });
            reopen(Tab.TIERS);
        }, () -> reopen(Tab.TIERS));
    }

    private void extensionClick(City city, CityExtension extension) {
        CityConfig config = services.cities().config();
        if (extension == CityExtension.QUESTS && services.quests().active(city)) {
            questHistory = false;
            switchTab(Tab.QUESTS);
            return;
        }
        if (!config.enabled(extension) || city.hasExtension(extension) || city.tier() < config.minTier(extension)
                || !city.roleOf(viewer.getUniqueId()).canBuyExtension()) {
            return;
        }
        long price = config.price(extension);
        try {
            services.extensions().check(city, viewer.getUniqueId(), extension);
        } catch (CityException e) {
            services.msg().send(viewer, e.key(), e.vars());
            return;
        }
        ConfirmGUI.open(viewer, extension.label() + " pour " + Messages.coins(price) + " ?", () -> {
            if (extension == CityExtension.PROMO_FRENZY) {
                services.msg().send(viewer, "extension_scanning");
            } else if (extension == CityExtension.BEACON) {
                services.msg().send(viewer, "extension_scanning_beacons");
            }
            // Every condition again, with a fresh skin scan for Promotion en folie (asynchronous).
            services.extensions().buy(city, viewer.getUniqueId(), extension, () -> {
                bought(city, extension, price);
                refreshOpenBoards();
            }, e -> services.msg().send(viewer, e.key(), e.vars()));
            reopen(Tab.EXTENSIONS);
        }, () -> reopen(Tab.EXTENSIONS));
    }

    /** "Vérifier les beacons": asynchronous scan (cooldown per city), result in the chat. */
    private void beaconCheck(City city) {
        ExtensionManager extensions = services.extensions();
        extensions.scanBeacons(city, true, check -> {
            if (check.valid().size() >= check.required()) {
                services.msg().send(viewer, "extension_beacons_ok", "city", city.name(), "found", check.valid().size(),
                        "required", check.required(), "reasons", extensions.reasons(check));
            } else {
                services.msg().send(viewer, "extension_beacons_check", "city", city.name(), "found", check.valid().size(),
                        "required", check.required(), "reasons", extensions.reasons(check));
            }
            services.staffLog().action(viewer, "/cityboard " + city.name(), "vérification des beacons de **"
                    + StaffLog.md(city.name()) + "** : " + check.valid().size() + "/" + check.required());
        }, e -> services.msg().send(viewer, e.key(), e.vars()));
    }

    private void bought(City city, CityExtension extension, long price) {
        CityConfig config = services.cities().config();
        Object[] vars = {"extension", extension.label(), "city", city.name(), "price", Messages.coins(price),
                "cost", Messages.coins(config.teleportUseCost), "n", config.frenzyPromosPerDay};
        services.msg().send(viewer, "extension_bought_" + extension.key(), vars);
        for (UUID member : city.members().keySet()) {
            if (!member.equals(viewer.getUniqueId())) {
                services.notifier().notify(member, "extension_notify_" + extension.key(), vars);
            }
        }
    }

    /** "Vérifier les skins": asynchronous scan, result in the chat. */
    private void skinCheck(City city) {
        services.msg().send(viewer, "extension_scanning");
        ExtensionManager extensions = services.extensions();
        extensions.scanTypes(city, missing -> {
            int required = services.cities().config().frenzyTypes.size();
            if (missing.isEmpty()) {
                services.msg().send(viewer, "extension_skins_ok", "city", city.name(), "n", required);
            } else {
                services.msg().send(viewer, "extension_skins_check", "city", city.name(),
                        "present", required - missing.size(), "n", required, "missing", extensions.labels(missing));
            }
            services.staffLog().action(viewer, "/cityboard " + city.name(), "vérification des skins de **"
                    + StaffLog.md(city.name()) + "** : " + (required - missing.size()) + "/" + required);
        }, e -> services.msg().send(viewer, e.key(), e.vars()));
    }

    private void reopen(Tab target) {
        City city = services.cities().byId(cityId);
        if (city != null && city.isMember(viewer.getUniqueId())) {
            open(services, viewer, city, target);
        } else {
            viewer.closeInventory();
        }
    }

    @FunctionalInterface
    private interface CityAction {
        void run() throws CityException;
    }

    private void run(CityAction action) {
        try {
            action.run();
        } catch (CityException e) {
            services.msg().send(viewer, e.key(), e.vars());
        }
    }

    // ------------------------------------------------------------------
    // Confirmation screen
    // ------------------------------------------------------------------

    public static final class ConfirmGUI implements InventoryHolder {
        private final Inventory inventory;
        private final Runnable onConfirm;
        private final Runnable onCancel;

        private ConfirmGUI(String question, Runnable onConfirm, Runnable onCancel) {
            this.onConfirm = onConfirm;
            this.onCancel = onCancel;
            this.inventory = Bukkit.createInventory(this, 27, Messages.parse("<dark_red>Confirmation"));
            for (int i = 0; i < 27; i++) {
                inventory.setItem(i, filler());
            }
            inventory.setItem(11, named(Material.LIME_CONCRETE, "<green><bold>Confirmer", List.of()));
            inventory.setItem(13, named(Material.PAPER, "<white>" + escape(question), List.of()));
            inventory.setItem(15, named(Material.RED_CONCRETE, "<red><bold>Annuler", List.of()));
        }

        static void open(Player viewer, String question, Runnable onConfirm, Runnable onCancel) {
            viewer.openInventory(new ConfirmGUI(question, onConfirm, onCancel).inventory);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }

        void click(int slot) {
            if (slot == 11) {
                onConfirm.run();
            } else if (slot == 15) {
                onCancel.run();
            }
        }
    }

    // ------------------------------------------------------------------
    // Listener + /cityboard command
    // ------------------------------------------------------------------

    public static final class Handler implements Listener, TabExecutor {
        private final Services services;

        public Handler(Services services) {
            this.services = services;
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onClick(InventoryClickEvent event) {
            InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
            if (!(holder instanceof CityBoardGUI) && !(holder instanceof ConfirmGUI)) {
                return;
            }
            if (!InventoryGuard.guardClick(event)) {
                return; // the player's own inventory stays usable
            }
            int slot = event.getSlot();
            ClickType click = event.getClick();
            // Inventories are opened / closed on the next tick, never inside the click event.
            Bukkit.getScheduler().runTask(services.plugin(), () -> {
                if (event.getWhoClicked().getOpenInventory().getTopInventory().getHolder(false) != holder) {
                    return;
                }
                if (holder instanceof CityBoardGUI board) {
                    board.click(slot, click);
                } else {
                    ((ConfirmGUI) holder).click(slot);
                }
            });
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onDrag(InventoryDragEvent event) {
            InventoryHolder holder = event.getView().getTopInventory().getHolder(false);
            if (holder instanceof CityBoardGUI || holder instanceof ConfirmGUI) {
                InventoryGuard.guardDrag(event);
            }
        }

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
            if (!(sender instanceof Player player)) {
                services.msg().send(sender, "players_only");
                return true;
            }
            if (args.length != 1) {
                services.msg().send(player, "cityboard_usage");
                return true;
            }
            try {
                City city = services.cities().require(args[0]);
                if (!city.isMember(player.getUniqueId())) {
                    throw new CityException("not_member", "city", city.name());
                }
                open(services, player, city, Tab.MEMBERS);
                services.staffLog().command(sender, command, args, "tableau de bord de **" + StaffLog.md(city.name()) + "** ouvert");
            } catch (CityException e) {
                services.msg().send(player, e.key(), e.vars());
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
            if (args.length != 1 || !(sender instanceof Player player)) {
                return List.of();
            }
            Set<String> names = new HashSet<>();
            services.cities().citiesOf(player.getUniqueId()).forEach(c -> names.add(c.name()));
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return names.stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
        }
    }
}
