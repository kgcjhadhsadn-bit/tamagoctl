package pl.kudlacze.core.seasons;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.TestDb;
import pl.kudlacze.core.db.KeyValueStore;
import pl.kudlacze.core.db.Sql;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonServiceTest {

    private SeasonService seasons;
    private KeyValueStore kv;
    private final UUID ala = UUID.randomUUID();
    private final UUID bartek = UUID.randomUUID();
    private final UUID celina = UUID.randomUUID();
    private final UUID darek = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Sql sql = TestDb.fresh();
        kv = new KeyValueStore(sql);
        seasons = new SeasonService(sql, kv);
    }

    @Test
    void rankingOrdersByPointsThenCrystalsThenPlaytime() {
        seasons.addPoints(ala, "Ala", 100, 0);
        seasons.addPoints(bartek, "Bartek", 150, 0);
        seasons.addPoints(celina, "Celina", 100, 3);   // remis z Alą — więcej kryształów
        seasons.addPoints(darek, "Darek", 100, 0);     // remis z Alą — dłuższy czas gry
        seasons.addPlaytime(ala, "Ala", 600);
        seasons.addPlaytime(darek, "Darek", 1200);
        List<String> order = seasons.top(10).stream().map(SeasonService.Entry::name).toList();
        assertEquals(List.of("Bartek", "Celina", "Ala", "Darek"), order);
        assertEquals(1, seasons.place(bartek).orElseThrow());
        assertEquals(3, seasons.place(ala).orElseThrow());
        assertEquals(4, seasons.place(darek).orElseThrow());
    }

    @Test
    void playersWithoutPointsAreNotRanked() {
        seasons.addPlaytime(ala, "Ala", 300);
        assertTrue(seasons.top(10).isEmpty());
        assertTrue(seasons.place(ala).isEmpty());
        assertEquals(300, seasons.entry(ala).orElseThrow().playtimeSeconds());
    }

    @Test
    void playtimeAccumulatesAndReturnsTotal() {
        assertEquals(60, seasons.addPlaytime(ala, "Ala", 60));
        assertEquals(120, seasons.addPlaytime(ala, "Ala", 60));
    }

    @Test
    void closeSeasonArchivesTopAndStartsNextSeasonOnce() {
        kv.put(SeasonService.KEY_SEASON, "3");
        seasons.addPoints(ala, "Ala", 50, 0);
        seasons.addPoints(bartek, "Bartek", 70, 0);
        List<SeasonService.Archived> top = seasons.closeSeason(3, 10, 1_000L);
        assertEquals(2, top.size());
        assertEquals("Bartek", top.getFirst().name());
        assertEquals(1, top.getFirst().place());
        assertEquals(4, seasons.currentSeason());
        assertEquals(1_000L, seasons.seasonStart(-1));
        // nowy sezon zaczyna się od zera
        assertTrue(seasons.top(10).isEmpty());
        // drugie wywołanie dla tego samego sezonu nic nie robi (np. dwa serwery, restart w trakcie)
        assertTrue(seasons.closeSeason(3, 10, 2_000L).isEmpty());
        assertEquals(4, seasons.currentSeason());
        assertEquals(2, seasons.archive(3).size());
        assertEquals("Ala", seasons.archive(3).get(1).name());
    }

    @Test
    void challengeCompletesExactlyOnce() {
        assertFalse(seasons.progressChallenge(ala, "gornik", 30, 48, 1L));
        assertTrue(seasons.progressChallenge(ala, "gornik", 30, 48, 2L));
        assertEquals(48, seasons.challengeProgress(ala, "gornik"));
        assertFalse(seasons.progressChallenge(ala, "gornik", 5, 48, 3L));
        assertEquals(1, seasons.challengeStates(ala).get("gornik")[1]);
    }

    @Test
    void questLogFeedsDailyAndWeeklyLimits() {
        seasons.logQuest(ala, "drewno", 128, 15, 1, 1_000L);
        seasons.logQuest(ala, "drewno", 128, 15, 1, 2_000L);
        seasons.logQuest(ala, "diamenty", 8, 50, 2, 3_000L);
        seasons.logQuest(bartek, "drewno", 128, 15, 1, 3_000L);
        assertEquals(2, seasons.questCountSince(ala, "drewno", 0));
        assertEquals(1, seasons.questCountSince(ala, "drewno", 1_500L));
        assertEquals(4, seasons.questKcSince(ala, 0));
        assertEquals(2, seasons.questKcSince(ala, 2_500L));
        assertEquals(2, seasons.questCountsSince(ala, 0).get("drewno"));
    }

    @Test
    void crystalsCanBeSpentOnlyWhenEnough() {
        seasons.addPoints(ala, "Ala", 0, 5);
        assertFalse(seasons.spendCrystals(ala, 6));
        assertTrue(seasons.spendCrystals(ala, 5));
        assertEquals(0, seasons.entry(ala).orElseThrow().crystals());
    }
}
