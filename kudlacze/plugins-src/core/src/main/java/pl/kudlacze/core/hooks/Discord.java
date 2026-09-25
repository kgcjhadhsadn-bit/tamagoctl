package pl.kudlacze.core.hooks;

import org.bukkit.Bukkit;
import pl.kudlacze.core.config.CoreConfig;

import java.util.logging.Logger;

/**
 * Ogłoszenia na kanały Discorda przez komendę DiscordSRV {@code discordsrv bcast #<id kanału> <tekst>}
 * (github.scarsz.discordsrv.commands.CommandBroadcast — kanał po ID albo nazwie, {@code \n} = nowa linia).
 * Bez DiscordSRV albo z niewypełnionym ID kanału (same zera z .env.example) nic nie wysyła.
 */
public final class Discord {

    private final CoreConfig config;
    private final Logger logger;

    public Discord(CoreConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /** Wysyła tekst (bez formatowania MiniMessage) na kanał z {@code discord.kanaly.<kanal>}. Wątek główny. */
    public void announce(String channel, String text) {
        String id = config.string("discord.kanaly." + channel, "");
        if (id == null || id.isBlank() || id.startsWith("${") || id.chars().allMatch(c -> c == '0')) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            return;
        }
        String oneLine = text.replace("\r", "").replace("\n", "\\n");
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discordsrv bcast #" + id + " " + oneLine);
        } catch (RuntimeException e) {
            logger.warning("Nie udało się wysłać ogłoszenia na Discord (" + channel + "): " + e.getMessage());
        }
    }
}
