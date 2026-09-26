package pl.kudlacze.core.punish;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentLogTest {

    private static PunishmentLogModule.Punishment p(int type, int victimType, String reason, long start, long end) {
        return new PunishmentLogModule.Punishment(1, type, victimType, UUID.randomUUID(), new UUID(0, 0), reason, start, end);
    }

    @Test
    void formatsEachPunishmentType() {
        assertEquals(":hammer: **BAN** — Griefer — na zawsze — powód: grief działki (nałożył: Admin)",
                PunishmentLogModule.format(p(0, 0, "grief działki", 1000, 0), "Griefer", "Admin"));
        assertEquals(":mute: **WYCISZENIE** — Spamer — na 1 godz. — powód: spam (nałożył: Konsola)",
                PunishmentLogModule.format(p(1, 0, "spam", 1000, 4600), "Spamer", "Konsola"));
        assertTrue(PunishmentLogModule.format(p(2, 0, "caps", 1, 0), "X", "Mod").startsWith(":warning: **OSTRZEŻENIE** — X — powód"));
        assertFalse(PunishmentLogModule.format(p(3, 0, "", 1, 0), "X", "Mod").contains("na zawsze"));
    }

    @Test
    void ipBansNeverRevealAddress() {
        String text = PunishmentLogModule.format(p(0, 1, "multikonta", 1, 0), null, "Admin");
        assertTrue(text.contains("adres IP (ukryty)"));
    }

    @Test
    void mentionsAreDefused() {
        String text = PunishmentLogModule.format(p(2, 0, "pingował @everyone", 1, 0), "X", "Mod");
        assertFalse(text.contains("@everyone"));
    }

    @Test
    void binaryUuidFromLibertyBans() {
        UUID u = UUID.randomUUID();
        byte[] bytes = ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array();
        assertEquals(u, PunishmentLogModule.uuid(bytes));
        assertNull(PunishmentLogModule.uuid(new byte[4]));
    }
}
