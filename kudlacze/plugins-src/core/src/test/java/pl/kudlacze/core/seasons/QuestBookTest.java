package pl.kudlacze.core.seasons;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestBookTest {

    private static QuestBook bundled() {
        return QuestBook.load(YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(QuestBookTest.class.getResourceAsStream("/questy.yml")), StandardCharsets.UTF_8)));
    }

    @Test
    void bundledQuestFileIsValid() {
        QuestBook book = bundled();
        assertEquals(10, book.weeklyKcLimit());
        assertTrue(book.quests().size() >= 6);
        assertTrue(book.quests().get("drewno").accepts(Material.SPRUCE_LOG));
        assertFalse(book.quests().get("drewno").accepts(Material.DIAMOND));
        assertEquals(QuestBook.ChallengeType.KOPANIE, book.challenges().get("gornik").type());
        assertTrue(book.challenges().get("lowca").targets().contains("WROGIE"));
        assertFalse(book.shop().isEmpty());
    }

    @Test
    void weeklyCapIsReachableButNotExceeded() {
        // limity dzienne pozwalają zarobić więcej niż 10 KC tygodniowo — limit tygodniowy przycina nagrodę
        QuestBook book = bundled();
        long maxPerDay = book.quests().values().stream().mapToLong(q -> q.kc() * Math.max(1, q.dailyLimit())).sum();
        assertTrue(maxPerDay * 7 > book.weeklyKcLimit());
        assertEquals(1, QuestBook.cappedKc(1, 0, 10));
        assertEquals(1, QuestBook.cappedKc(2, 9, 10));
        assertEquals(0, QuestBook.cappedKc(2, 10, 10));
        assertEquals(2, QuestBook.cappedKc(2, 50, 0));
    }

    @Test
    void invalidDefinitionsAreRejected() {
        YamlConfiguration bad = new YamlConfiguration();
        bad.set("questy.x.przedmioty", java.util.List.of("NIE_MA_TAKIEGO"));
        assertThrows(IllegalArgumentException.class, () -> QuestBook.load(bad));
        YamlConfiguration bad2 = new YamlConfiguration();
        bad2.set("wyzwania.y.typ", "LOWIENIE");
        bad2.set("wyzwania.y.cele", java.util.List.of("COD"));
        assertThrows(IllegalArgumentException.class, () -> QuestBook.load(bad2));
    }
}
