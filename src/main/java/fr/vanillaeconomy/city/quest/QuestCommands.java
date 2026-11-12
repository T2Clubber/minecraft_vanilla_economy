package fr.vanillaeconomy.city.quest;

import fr.vanillaeconomy.city.City;
import fr.vanillaeconomy.city.CityException;
import fr.vanillaeconomy.city.CityManager;
import fr.vanillaeconomy.city.quest.QuestProgressRepository.Contributor;
import fr.vanillaeconomy.discord.StaffLog;
import fr.vanillaeconomy.util.MessageConfig;
import fr.vanillaeconomy.util.ServerTime;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * /city quest [cité] | deposit &lt;cité&gt; [quantité|all] | history [cité] [page]. Returns the
 * line of the staff command journal (null when nothing was done).
 */
public final class QuestCommands {

    private static final int HISTORY_PAGE = 5;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRANCE);

    private final CityQuestExtension quests;
    private final QuestDepositService deposits;

    public QuestCommands(CityQuestExtension quests, QuestDepositService deposits) {
        this.quests = quests;
        this.deposits = deposits;
    }

    public String handle(Player player, String[] args) throws CityException {
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("deposit")) {
            if (args.length < 3 || args.length > 4) {
                throw new CityException("quest_deposit_usage");
            }
            City city = quests.cities().require(args[2]);
            Long requested = null;
            if (args.length == 4 && !args[3].equalsIgnoreCase("all")) {
                try {
                    requested = Long.parseLong(args[3]);
                } catch (NumberFormatException e) {
                    requested = -1L;
                }
                if (requested <= 0) {
                    throw new CityException("invalid_amount");
                }
            }
            CityQuestManager.Deposit result = deposits.deposit(player, city, requested);
            return result.accepted() + " items déposés pour « " + StaffLog.md(result.quest().name()) + " » de **"
                    + StaffLog.md(city.name()) + "** (" + result.quest().progress() + "/" + result.quest().target() + ")"
                    + (result.completed() ? " : quête réussie" : "");
        }
        if (sub.equals("history")) {
            City city;
            int page = 1;
            if (args.length >= 3 && args[2].matches("\\d+")) {
                city = defaultCity(player);
                page = Integer.parseInt(args[2]);
            } else {
                city = args.length >= 3 ? quests.cities().require(args[2]) : defaultCity(player);
                if (args.length >= 4) {
                    page = args[3].matches("\\d+") ? Integer.parseInt(args[3]) : 1;
                }
            }
            history(player, city, Math.max(1, page));
            return "historique des quêtes de **" + StaffLog.md(city.name()) + "** consulté";
        }
        City city = args.length >= 2 ? quests.cities().require(args[1]) : defaultCity(player);
        status(player, city);
        return "quête de **" + StaffLog.md(city.name()) + "** consultée";
    }

    /** Without a name: the player's only city with the extension. */
    private City defaultCity(Player player) throws CityException {
        List<City> mine = quests.cities().citiesOf(player.getUniqueId()).stream().filter(quests::active).toList();
        if (mine.isEmpty()) {
            throw new CityException("quest_no_city");
        }
        if (mine.size() > 1) {
            throw new CityException("quest_ambiguous");
        }
        return mine.getFirst();
    }

    private void status(Player player, City city) throws CityException {
        MessageConfig msg = quests.msg();
        if (!quests.active(city)) {
            throw new CityException("quest_no_extension", "city", city.name());
        }
        CityQuest quest = quests.manager().current(city.id())
                .orElseThrow(() -> new CityException("quest_none", "city", city.name()));
        Object[] vars = quests.vars(quest, city);
        player.sendMessage(msg.get(quest.status() == CityQuest.Status.ACTIVE ? "quest_status" : "quest_status_completed", vars));
        if (quest.resumed()) {
            player.sendMessage(msg.get("quest_resume", vars));
        }
        if (quest.hint() != null) {
            player.sendMessage(msg.get("quest_hint", "hint", quest.hint()));
        }
        try {
            List<Contributor> ranking = quests.manager().ranking(quest);
            for (int i = 0; i < ranking.size() && i < 5; i++) {
                player.sendMessage(msg.get("quest_ranking_line", "rank", i + 1,
                        "player", CityManager.nameOf(ranking.get(i).player()), "amount", ranking.get(i).amount()));
            }
        } catch (SQLException e) {
            throw new CityException("internal_error");
        }
    }

    private void history(Player player, City city, int page) throws CityException {
        MessageConfig msg = quests.msg();
        try {
            int size = quests.manager().historySize(city.id());
            int pages = Math.max(1, (size + HISTORY_PAGE - 1) / HISTORY_PAGE);
            int shown = Math.min(page, pages);
            player.sendMessage(msg.get("quest_history_header", "city", city.name(), "page", shown, "pages", pages));
            if (size == 0) {
                player.sendMessage(msg.get("quest_history_empty"));
                return;
            }
            for (CityQuest q : quests.manager().history(city.id(), shown - 1, HISTORY_PAGE)) {
                player.sendMessage(msg.get("quest_history_completed", "name", q.name(),
                        "difficulty", CityQuestExtension.difficulty(q), "target", q.target(), "date", date(q.completedAt()),
                        "top", CityQuestExtension.top(quests.manager().ranking(q), 5)));
            }
            CityQuestManager.Completion completion = quests.manager().completion(city.id());
            player.sendMessage(msg.get("quest_history_totals", "completed", completion.completed(),
                    "total", completion.total()));
        } catch (SQLException e) {
            throw new CityException("internal_error");
        }
    }

    public static String date(Long millis) {
        return millis == null ? "?" : DATE.format(Instant.ofEpochMilli(millis).atZone(ServerTime.zone()));
    }
}
