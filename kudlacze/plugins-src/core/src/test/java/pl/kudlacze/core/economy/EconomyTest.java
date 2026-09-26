package pl.kudlacze.core.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.ranks.RankDefinition;
import pl.kudlacze.core.seasons.QuestBook;
import pl.kudlacze.core.vip.DailyReward;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Symulacja z docs/EKONOMIA.md liczona z tych samych plików co plugin (config.yml, questy.yml, rangi.yml) —
 * zmiana balansu, która rozjedzie się z dokumentem, wywali ten test.
 */
class EconomyTest {

    private static YamlConfiguration yml(String resource) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(EconomyTest.class.getResourceAsStream(resource)), StandardCharsets.UTF_8));
    }

    @Test
    void activePlayerEarnsAboutTwentyKcPerWeek() {
        YamlConfiguration config = yml("/config.yml");
        double quests = QuestBook.load(yml("/questy.yml")).weeklyKcLimit();          // ~10 KC (limit tygodniowy)
        double daily = DailyReward.fromSection(config, "nagroda-codzienna.nagrody").expectedKc() * 7; // ~5 KC
        List<Long> dragon = config.getLongList("smok.kc");                              // 0–10 KC
        double dragonTypical = dragon.getFirst() / 2.0;
        double week = quests + daily + dragonTypical;

        assertEquals(10, quests);
        assertEquals(4.9, daily, 0.05);
        assertEquals(List.of(10L, 8L, 6L, 4L, 2L), dragon);
        assertEquals(19.9, week, 0.1);
        assertTrue(week >= 18 && week <= 22, "ok. 20 KC tygodniowo, jest " + week);
    }

    @Test
    void rankPricesMatchBalanceTable() {
        Map<String, RankDefinition> ranks = RankDefinition.load(yml("/rangi.yml").getConfigurationSection("rangi"));
        for (String id : List.of("vip", "svip", "mvip", "uvip", "tytan")) {
            assertEquals(80, ranks.get(id).cost(), id);
            assertEquals(30, ranks.get(id).days(), id);
        }
        assertEquals(40, ranks.get("drwal").cost());
        // VIP za ok. 4 tygodnie aktywnej gry
        double week = 10 + 4.9 + 5;
        assertEquals(4, Math.round(ranks.get("vip").cost() / week));
    }

    @Test
    void eventRewardsMatchSpec() {
        YamlConfiguration config = yml("/config.yml");
        assertEquals(List.of(50L, 30L, 20L), config.getLongList("sezon.nagrody-kc"));
        assertEquals(List.of(60L, 40L, 20L), config.getLongList("konkurs.nagrody-kc"));
        assertEquals(List.of(100L, 80L, 60L, 40L, 20L), config.getLongList("smok.punkty"));
    }
}
