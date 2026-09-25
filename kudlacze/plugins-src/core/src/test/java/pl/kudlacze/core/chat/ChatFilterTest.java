package pl.kudlacze.core.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFilterTest {

    private static final List<String> ALLOWED = List.of("kudlacze.pl", "discord.gg", "youtube.com");

    @Test
    void capsNeedsEnoughLetters() {
        assertTrue(ChatModule.isCaps("SPRZEDAM DIAMENTY TANIO"));
        assertFalse(ChatModule.isCaps("OK"));
        assertFalse(ChatModule.isCaps("Hej, co tam u Was?"));
    }

    @Test
    void detectsForeignServersAndIps() {
        assertTrue(ChatModule.isAdvert("wbijajcie na inny-serwer.pl", ALLOWED));
        assertTrue(ChatModule.isAdvert("graj na 123.45.67.89", ALLOWED));
        assertTrue(ChatModule.isAdvert("wejdz na mojserwer . pl", ALLOWED), "spacje wokół kropki nie pomagają");
        assertTrue(ChatModule.isAdvert("mojserwer,pl", ALLOWED), "przecinek zamiast kropki też");
    }

    @Test
    void allowsOwnDomainAndWhitelisted() {
        assertFalse(ChatModule.isAdvert("zapraszam na kudlacze.pl", ALLOWED));
        assertFalse(ChatModule.isAdvert("discord: discord.gg/kudlacze", ALLOWED));
        assertFalse(ChatModule.isAdvert("zwykła wiadomość bez linków", ALLOWED));
    }
}
