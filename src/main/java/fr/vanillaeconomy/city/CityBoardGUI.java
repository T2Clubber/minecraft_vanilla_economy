package fr.vanillaeconomy.city;

import com.destroystokyo.paper.profile.PlayerProfile;
import fr.vanillaeconomy.city.CityRules.Tier;
import fr.vanillaeconomy.currency.CurrencyManager;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.Messages;
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
import java.time.ZoneId;
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
 * /cityboard &lt;cité&gt;: 54-slot board for members, with three tabs (MEMBRES, PALIERS,
 * TRÉSORERIE). Every click is cancelled and its rights are checked again on the server
 * (role read from the live city), skins are loaded off the main thread.
 */
public final class CityBoardGUI implements InventoryHolder {

    public enum Tab { MEMBERS, TIERS, TREASURY }

    private static final int SIZE = 54;
    private static final int TAB_MEMBERS = 3;
    private static final int TAB_TIERS = 4;
    private static final int TAB_TREASURY = 5;
    private static final int PREV = 45;
    private static final int INFO = 49;
    private static final int NEXT = 53;
    private static final int MEMBERS_PER_PAGE = 36;     // slots 9..44
    private static final int BALANCE = 9;
    private static final int RANKING_FIRST = 11;        // slots 11..17
    private static final int RANKING_SIZE = 7;
    private static final int RECENT_FIRST = 18;         // slots 18..44
    private static final int RECENT_PER_PAGE = 27;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE)
            .withZone(ZoneId.systemDefault());

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

    /** What the board needs, shared by all instances. */
    public record Services(Plugin plugin, CityManager cities, CurrencyManager currency, MessageConfig msg,
                           CityNotifier notifier) {
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
        ItemStack[] contents = new ItemStack[SIZE];
        java.util.Arrays.fill(contents, filler());
        contents[TAB_MEMBERS] = tabItem(Material.PLAYER_HEAD, "<yellow>Membres", Tab.MEMBERS);
        contents[TAB_TIERS] = tabItem(Material.FILLED_MAP, "<yellow>Paliers", Tab.TIERS);
        contents[TAB_TREASURY] = tabItem(Material.GOLD_INGOT, "<yellow>Trésorerie", Tab.TREASURY);
        contents[INFO] = infoItem(city);
        int pages = switch (tab) {
            case MEMBERS -> renderMembers(city, contents);
            case TIERS -> renderTiers(city, contents);
            case TREASURY -> renderTreasury(city, contents);
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
                    List.of(Messages.item("<dark_gray>" + DATE.format(Instant.ofEpochMilli(c.timestamp())))));
        }
        return pages;
    }

    private ItemStack infoItem(City city) {
        CityRole me = city.roleOf(viewer.getUniqueId());
        List<Component> lore = new ArrayList<>();
        lore.add(Messages.item("<gray>Propriétaire : <white><owner>", Messages.p("owner", CityManager.nameOf(city.owner()))));
        lore.add(Messages.item("<gray>Palier <white><tier></white> · <white><size>x<size>",
                Messages.p("tier", city.tier()), Messages.p("size", city.territory().size())));
        lore.add(Messages.item("<gray>Membres : <white><n>", Messages.p("n", city.members().size())));
        lore.add(Messages.item("<gray>Votre rôle : <white><role>", Messages.p("role", me.label())));
        if (me.canAddMembers()) {
            lore.add(Component.empty());
            lore.add(Messages.item("<yellow>Ajouter un membre : <white>/city add <joueur>"));
        }
        return named(Material.OAK_SIGN, "<aqua>" + escape(city.name()), lore);
    }

    private ItemStack tabItem(Material mat, String name, Tab target) {
        ItemStack item = named(mat, name, List.of(Messages.item(tab == target ? "<green>Onglet affiché" : "<gray>Clic : afficher")));
        if (tab == target) {
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

    private static ItemStack named(Material mat, String name, List<Component> lore) {
        ItemStack item = ItemStack.of(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Messages.item(name));
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
            });
            render();
        } else if (click == ClickType.SHIFT_RIGHT && me.canKick(targetRole) && !target.equals(viewer.getUniqueId())) {
            ConfirmGUI.open(viewer, "Exclure " + targetName + " ?", () -> {
                run(() -> {
                    services.cities().kick(city, viewer.getUniqueId(), target);
                    services.msg().send(viewer, "kicked", "player", targetName, "city", city.name());
                    services.notifier().notify(target, "kicked_notify", "city", city.name());
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
            event.setCancelled(true);
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
                return;
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
                event.setCancelled(true);
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
