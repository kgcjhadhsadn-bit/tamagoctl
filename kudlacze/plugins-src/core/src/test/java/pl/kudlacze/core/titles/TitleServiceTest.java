package pl.kudlacze.core.titles;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.kudlacze.core.TestDb;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TitleServiceTest {

    private TitleService titles;
    private final UUID ala = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        titles = new TitleService(TestDb.fresh());
    }

    @Test
    void grantActivateAndRevoke() {
        assertFalse(titles.setActive(ala, "smokobojca"), "nie można ustawić tytułu, którego się nie ma");
        titles.grant(ala, "smokobojca", "Smok Kudłaty", 1L);
        titles.grant(ala, "mistrz-sezonu", "Sezon 2", 2L);
        titles.grant(ala, "smokobojca", "Smok Kudłaty", 3L); // ponowne nadanie — bez duplikatu
        assertEquals(2, titles.owned(ala).size());
        assertEquals("smokobojca", titles.owned(ala).getFirst().id());
        assertTrue(titles.setActive(ala, "mistrz-sezonu"));
        assertEquals("mistrz-sezonu", titles.active(ala).orElseThrow());
        assertTrue(titles.revoke(ala, "mistrz-sezonu"));
        assertTrue(titles.active(ala).isEmpty());
        titles.setActive(ala, "smokobojca");
        titles.clearActive(ala);
        assertTrue(titles.active(ala).isEmpty());
    }
}
