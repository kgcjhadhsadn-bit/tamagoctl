package pl.kudlacze.core.parkour;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import pl.kudlacze.core.KudlaczeCore;
import pl.kudlacze.core.TestDb;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParkourModuleTest {

    private ParkourModule parkour;

    @BeforeEach
    void setUp() {
        System.setProperty(KudlaczeCore.TEST_JDBC, TestDb.url());
        System.setProperty("CFG_SERVER_NAME", "lobby");
        System.setProperty("CFG_KC_ITEM_SECRET", "sekret-testowy-kudlaczy");
        MockBukkit.mock().addSimpleWorld("world");
        KudlaczeCore plugin = MockBukkit.load(KudlaczeCore.class);
        parkour = new ParkourModule(plugin.context());
        parkour.enable();
    }

    @AfterEach
    void tearDown() {
        parkour.disable();
        MockBukkit.unmock();
        System.clearProperty(KudlaczeCore.TEST_JDBC);
    }

    @Test
    void keepsOnlyBestTime() {
        UUID ala = UUID.randomUUID();
        assertTrue(parkour.record(ala, "Ala", 42_000, 1).isEmpty());
        assertEquals(Optional.of(42_000L), parkour.record(ala, "Ala", 50_000, 2));
        assertEquals(Optional.of(42_000L), parkour.best(ala));
        assertEquals(Optional.of(42_000L), parkour.record(ala, "Ala", 39_500, 3));
        assertEquals(Optional.of(39_500L), parkour.best(ala));
    }

    @Test
    void timeFormat() {
        assertEquals("0:39.500", ParkourModule.format(39_500));
        assertEquals("2:05.007", ParkourModule.format(125_007));
    }
}
