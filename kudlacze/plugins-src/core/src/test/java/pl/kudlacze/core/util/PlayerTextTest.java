package pl.kudlacze.core.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerTextTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void plainLevelShowsTagsLiterally() {
        Component c = PlayerText.format("<red>hej &a<click:run_command:/op>", PlayerText.Level.PLAIN);
        assertEquals("<red>hej &a<click:run_command:/op>", plain(c));
        assertNull(c.color());
    }

    @Test
    void colorLevelConvertsLegacyCodesButNotClickTags() {
        Component c = PlayerText.format("&aZielony <click:run_command:'/op'>klik</click>", PlayerText.Level.COLORS);
        assertTrue(plain(c).contains("Zielony"));
        assertTrue(plain(c).contains("<click"), "tag click nie może działać");
        assertEquals(NamedTextColor.GREEN, c.children().isEmpty() ? c.color() : c.children().getFirst().color());
    }

    @Test
    void colorLevelDoesNotAllowGradientsOrHex() {
        Component c = PlayerText.format("<gradient:red:blue>tekst</gradient> &#ff00ffx", PlayerText.Level.COLORS);
        assertTrue(plain(c).contains("<gradient:red:blue>"));
        assertFalse(plain(c).contains("#ff00ff"), "kolory HEX tylko od poziomu GRADIENTS");
    }

    @Test
    void gradientLevelRendersGradient() {
        Component c = PlayerText.format("<gradient:red:blue>tekst</gradient>", PlayerText.Level.GRADIENTS);
        assertEquals("tekst", plain(c));
    }

    @Test
    void legacyBoldBecomesDecoration() {
        Component c = PlayerText.format("&lGruby", PlayerText.Level.COLORS);
        Component target = c.children().isEmpty() ? c : c.children().getFirst();
        assertEquals(TextDecoration.State.TRUE, target.decoration(TextDecoration.BOLD));
    }

    @Test
    void polishPluralOfKlak() {
        assertEquals("Kłak", TimeFormat.klaki(1));
        assertEquals("Kłaki", TimeFormat.klaki(3));
        assertEquals("Kłaków", TimeFormat.klaki(5));
        assertEquals("Kłaków", TimeFormat.klaki(12));
        assertEquals("Kłaki", TimeFormat.klaki(22));
    }

    @Test
    void durationsArePolish() {
        assertEquals("5 min", TimeFormat.duration(Duration.ofMinutes(5)));
        assertEquals("2 dni 3 godz.", TimeFormat.duration(Duration.ofHours(51)));
        assertEquals("1 dzień", TimeFormat.duration(Duration.ofDays(1)));
        assertEquals("45 s", TimeFormat.duration(Duration.ofSeconds(45)));
    }
}
