package pl.kudlacze.core.config;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MessagesTest {

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void newlinesInMessagesAreRealLineBreaks() {
        Messages m = new Messages(new YamlConfiguration(), Logger.getLogger("test"));
        assertEquals("a\nb", plain(m.parse("a<newline>b")));
        // „\n” z pliku nadpisań w pojedynczych cudzysłowach YAML
        assertEquals("a\nb", plain(m.parse("a\\nb")));
    }

    @Test
    void bundledKickMessagesHaveNoLiteralBackslashN() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                Objects.requireNonNull(getClass().getResourceAsStream("/messages_pl.yml")), StandardCharsets.UTF_8));
        Messages m = new Messages(yml, Logger.getLogger("test"));
        for (String key : new String[]{"smierc.kick", "smierc.kick-blokada", "smierc.blokada-trwa", "afk.kick",
                "restart.kick", "sezon.kick-reset", "edycja.kick"}) {
            String text = plain(m.get(key));
            assertFalse(text.contains("\\n"), key);
            assertFalse(text.contains("<newline>"), key);
        }
    }
}
