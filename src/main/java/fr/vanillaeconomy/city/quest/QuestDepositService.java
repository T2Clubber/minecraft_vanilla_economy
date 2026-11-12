package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.City;
import fr.vanillaeconomy.city.CityException;
import fr.vanillaeconomy.city.quest.QuestRules.Slot;
import fr.vanillaeconomy.city.quest.QuestRules.Take;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Deposit of quest items by a member (/city quest deposit, "Déposer" button). The useful
 * items of the inventory are planned (never more than the quest still needs, never an item
 * tagged by this plugin such as a coin), recorded by {@link CityQuestManager#deposit} in one
 * transaction, then removed from the inventory in the same tick: they are consumed for
 * good, never stored, given back or dropped. Main thread only, so quick clicks run one
 * after the other on fresh values.
 */
public final class QuestDepositService {

    private final Plugin plugin;
    private final CityQuestExtension quests;
    private final String namespace;

    public QuestDepositService(Plugin plugin, CityQuestExtension quests) {
        this.plugin = plugin;
        this.quests = quests;
        this.namespace = plugin.getName().toLowerCase(Locale.ROOT);
    }

    /** {@code requested}: at most this many items, null = every useful item. Returns the quest after the deposit. */
    public CityQuestManager.Deposit deposit(Player player, City city, Long requested) throws CityException {
        CityQuest current = quests.manager().current(city.id()).orElse(null);
        var refusal = QuestRules.depositRefusal(city.isMember(player.getUniqueId()), quests.active(city),
                current == null ? null : current.status());
        if (refusal.isPresent()) {
            throw new CityException(refusal.get(), "city", city.name(), "name", current == null ? "" : current.name());
        }
        CityQuest quest = current;
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getStorageContents();
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.isEmpty()) {
                slots.add(new Slot(i, item.getType().name(), item.getAmount(), pluginTagged(item)));
            }
        }
        List<Take> plan = QuestRules.planDeposit(slots, quest.materials(), quest.remaining(), requested);
        long total = QuestRules.total(plan);
        if (total <= 0) {
            throw new CityException("quest_deposit_nothing", "name", quest.name());
        }
        CityQuestManager.Deposit result;
        try {
            result = quests.manager().deposit(city.id(), player.getUniqueId(), total);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Dépôt de quête impossible pour " + player.getName(), e);
            throw new CityException("internal_error");
        }
        if (result.accepted() <= 0) {
            throw new CityException("quest_deposit_refused", "name", quest.name());
        }
        // Recorded: the items are now consumed (same tick, the inventory has not changed).
        for (Take take : plan) {
            ItemStack item = inventory.getItem(take.index());
            if (item == null) {
                continue;
            }
            if (item.getAmount() <= take.amount()) {
                inventory.setItem(take.index(), null);
            } else {
                item.setAmount(item.getAmount() - take.amount());
                inventory.setItem(take.index(), item);
            }
        }
        quests.msg().send(player, result.completed() ? "quest_deposited_completed" : "quest_deposited",
                "amount", result.accepted(), "name", quest.name(), "progress", quest.progress(), "target", quest.target());
        return result;
    }

    /** Coins and other items tagged by this plugin are never accepted. */
    private boolean pluginTagged(ItemStack item) {
        ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : null;
        return meta != null && meta.getPersistentDataContainer().getKeys().stream()
                .anyMatch(key -> key.getNamespace().equals(namespace));
    }
}
