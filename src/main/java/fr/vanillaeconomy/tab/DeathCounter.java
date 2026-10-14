package fr.vanillaeconomy.tab;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Death count shown next to each name in the player list (TAB), e.g. "☠ 3". It is a vanilla
 * scoreboard objective displayed in the "list" slot, so clients need nothing. The value is
 * the vanilla DEATHS statistic of the world, which also counts deaths from before the
 * plugin was installed.
 */
public final class DeathCounter implements Listener {

    private static final String OBJECTIVE = "vanillaeco_deaths";

    private final Plugin plugin;
    private Objective objective;

    public DeathCounter(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.text("Morts"));
        }
        objective.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        Bukkit.getOnlinePlayers().forEach(this::update);
    }

    /** Feature disabled in config.yml: the objective (saved with the world) is removed. */
    public static void remove() {
        Objective existing = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(OBJECTIVE);
        if (existing != null) {
            existing.unregister();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer());
    }

    /** The statistic is incremented after the event: read it on the next tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Bukkit.getScheduler().runTask(plugin, () -> update(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        update(event.getPlayer());
    }

    private void update(Player player) {
        if (objective == null || !player.isOnline()) {
            return;
        }
        int deaths = player.getStatistic(Statistic.DEATHS);
        Score score = objective.getScore(player.getName());
        score.setScore(deaths);
        score.numberFormat(NumberFormat.fixed(Component.text("☠ " + deaths, NamedTextColor.RED)));
    }
}
