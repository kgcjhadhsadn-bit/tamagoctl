package pl.kudlacze.core.contest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.TestDb;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContestServiceTest {

    private ContestService contest;
    private final UUID ala = UUID.randomUUID();
    private final UUID bartek = UUID.randomUUID();
    private final UUID celina = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contest = new ContestService(TestDb.fresh());
    }

    @Test
    void onePerPlayerPerEdition() {
        assertTrue(contest.submit(2, ala, "Ala", "world", 1, 64, 2, "Chatka nad jeziorem", 10));
        assertFalse(contest.submit(2, ala, "Ala", "world", 5, 64, 5, "druga", 11));
        assertTrue(contest.submit(3, ala, "Ala", "world", 5, 64, 5, "w nowej edycji", 12));
        assertEquals("Chatka nad jeziorem", contest.byName(2, "ala").orElseThrow().description());
        assertTrue(contest.withdraw(2, ala));
        assertTrue(contest.entry(2, ala).isEmpty());
    }

    @Test
    void winnersAreRatedEntriesByScoreThenEarliest() {
        contest.submit(1, ala, "Ala", "world", 0, 0, 0, "", 30);
        contest.submit(1, bartek, "Bartek", "world", 0, 0, 0, "", 10);
        contest.submit(1, celina, "Celina", "world", 0, 0, 0, "", 20);
        UUID dawid = UUID.randomUUID();
        contest.submit(1, dawid, "Dawid", "world", 0, 0, 0, "", 5);
        contest.rate(1, ala, 9, admin);
        contest.rate(1, bartek, 7, admin);
        contest.rate(1, celina, 9, null); // ocena z konsoli
        List<ContestService.Entry> w = contest.winners(1, 3);
        assertEquals(List.of("Celina", "Ala", "Bartek"), w.stream().map(ContestService.Entry::name).toList());
        assertNull(contest.entry(1, dawid).orElseThrow().rating());
        assertEquals("Dawid", contest.list(1).getLast().name());
    }

    @Test
    void ratingMustBeOneToTen() {
        contest.submit(1, ala, "Ala", "world", 0, 0, 0, "", 1);
        assertThrows(IllegalArgumentException.class, () -> contest.rate(1, ala, 11, admin));
        assertThrows(IllegalArgumentException.class, () -> contest.rate(1, ala, 0, admin));
        assertFalse(contest.rate(1, bartek, 5, admin));
    }
}
