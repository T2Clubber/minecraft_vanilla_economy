package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.City;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Single scheduled task of the city quests: ends and next draws (from the quests in
 * memory), reminders before the end, daily purge of the history. Startup closes the
 * quests that ended while the server was off and draws one new quest each.
 */
public final class CityQuestScheduler implements Listener {

    private static final long CHECK_TICKS = 20L * 30;
    private static final long DAY = 86_400_000L;

    private final Plugin plugin;
    private final CityQuestExtension quests;
    private BukkitTask task;
    private long lastCheck;
    private long lastPurge;

    public CityQuestScheduler(Plugin plugin, CityQuestExtension quests) {
        this.plugin = plugin;
        this.quests = quests;
    }

    public void start() throws SQLException {
        CityQuestManager manager = quests.manager();
        manager.load();
        manager.tick(quests.citiesWithExtension(), true);
        lastCheck = System.currentTimeMillis();
        purge();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, CHECK_TICKS, CHECK_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    private void check() {
        long now = System.currentTimeMillis();
        long from = lastCheck;
        lastCheck = now;
        try {
            quests.manager().tick(quests.citiesWithExtension(), false);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Quêtes de cité : vérification des échéances impossible", e);
        }
        long hours = quests.manager().options().reminderHours();
        for (CityQuest quest : quests.manager().currentQuests()) {
            City city = quests.cities().byId(quest.cityId());
            if (city == null || !quests.active(city) || quest.status() != CityQuest.Status.ACTIVE || hours <= 0) {
                continue;
            }
            long at = QuestRules.reminderAt(quest.startsAt(), quest.endsAt(), hours);
            if (from < at && at <= now) {
                quests.broadcast(city, "quest_reminder", quests.vars(quest, city));
            }
        }
        if (now - lastPurge >= DAY) {
            purge();
        }
    }

    private void purge() {
        lastPurge = System.currentTimeMillis();
        try {
            int purged = quests.manager().purge();
            if (purged > 0) {
                plugin.getLogger().info("Quêtes de cité : " + purged + " quête(s) archivée(s) supprimée(s) (history-retention-days).");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Quêtes de cité : purge de l'historique impossible", e);
        }
    }

    /** Reminder of the current quest to a member who logs in (memory only). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (City city : quests.cities().citiesOf(player.getUniqueId())) {
            if (!quests.active(city)) {
                continue;
            }
            quests.manager().current(city.id()).ifPresent(quest -> player.sendMessage(quests.msg().get(
                    quest.status() == CityQuest.Status.ACTIVE ? "quest_join" : "quest_join_completed",
                    quests.vars(quest, city))));
        }
    }
}
