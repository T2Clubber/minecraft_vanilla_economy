package fr.vanillaeconomy.city;

import fr.vanillaeconomy.city.CityRules.Standing;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.tab.DeathCounter;
import fr.vanillaeconomy.util.MessageConfig;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/**
 * /classement on|off: ranking of the best cities in the sidebar (right of the screen), for
 * the players who asked for it. They share one scoreboard, refreshed every few seconds;
 * the others keep the main scoreboard. The choice is stored on the player (persistent
 * data), so it survives reconnections and restarts.
 */
public final class CityRanking implements Listener, TabExecutor {

    private static final String OBJECTIVE = "vanillaeco_top";
    private static final long REFRESH_TICKS = 100;

    private final Plugin plugin;
    private final CityManager cities;
    private final MessageConfig msg;
    private final StaffLog staffLog;
    private final NamespacedKey key;
    private final int size;
    private final Scoreboard board;
    private final Objective sidebar;
    private BukkitTask task;

    public CityRanking(Plugin plugin, CityManager cities, MessageConfig msg, StaffLog staffLog, DeathCounter deaths,
                       int size) {
        this.plugin = plugin;
        this.cities = cities;
        this.msg = msg;
        this.staffLog = staffLog;
        this.key = new NamespacedKey(plugin, "city_ranking");
        this.size = Math.max(1, Math.min(15, size));
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        this.sidebar = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, msg.get("ranking_title"));
        sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        if (deaths != null) {
            deaths.attach(board);
        }
    }

    public void start() {
        render();
        Bukkit.getOnlinePlayers().stream().filter(this::wanted).forEach(p -> p.setScoreboard(board));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::render, REFRESH_TICKS, REFRESH_TICKS);
    }

    /** Viewers get the main scoreboard back (plugin disabled). */
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Bukkit.getOnlinePlayers().stream().filter(p -> p.getScoreboard() == board).forEach(p -> p.setScoreboard(main));
    }

    /**
     * Lines "rank1".."rankN": the left text is the score's custom name, the right text its
     * number format. Never start an entry with '#': the client hides those in the sidebar.
     */
    private void render() {
        List<City> all = List.copyOf(cities.all());
        List<Standing> ranked = CityRules.rank(all.stream()
                .map(c -> new Standing(c.name(), c.tier(), c.extensions().size(), c.balance())).toList());
        for (int i = 0; i < size; i++) {
            String entry = "rank" + (i + 1);
            if (i >= ranked.size() && !(i == 0 && ranked.isEmpty())) {
                board.resetScores(entry);
                continue;
            }
            Score score = sidebar.getScore(entry);
            score.setScore(size - i);
            if (ranked.isEmpty()) {
                score.customName(msg.get("ranking_empty"));
                score.numberFormat(NumberFormat.blank());
                continue;
            }
            Standing s = ranked.get(i);
            score.customName(msg.get(i < 3 ? "ranking_line_podium" : "ranking_line", "rank", i + 1, "city", s.name()));
            score.numberFormat(NumberFormat.fixed(msg.get("ranking_stats", "tier", s.tier(),
                    "extensions", s.extensions(), "balance", CityRules.compact(s.balance()))));
        }
    }

    private boolean wanted(Player player) {
        return player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.BOOLEAN, false);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (wanted(event.getPlayer())) {
            event.getPlayer().setScoreboard(board);
        }
    }

    // ------------------------------------------------------------------
    // /classement on|off
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            msg.send(sender, "players_only");
            return true;
        }
        String arg = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!arg.equals("on") && !arg.equals("off")) {
            msg.send(player, "ranking_usage");
            return true;
        }
        boolean show = arg.equals("on");
        player.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, show);
        if (show) {
            render();
            player.setScoreboard(board);
            msg.send(player, "ranking_on");
        } else {
            if (player.getScoreboard() == board) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            msg.send(player, "ranking_off");
        }
        staffLog.command(sender, command, args, show ? "classement des cités affiché" : "classement des cités masqué");
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return List.of("on", "off").stream().filter(o -> o.startsWith(prefix)).toList();
    }
}
