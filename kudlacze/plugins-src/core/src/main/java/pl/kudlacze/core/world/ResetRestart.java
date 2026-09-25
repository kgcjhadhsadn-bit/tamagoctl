package pl.kudlacze.core.world;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.kudlacze.core.CoreContext;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;

/**
 * Wspólny koniec resetu (sezon, edycja): czyszczenie logów CoreProtect starego świata, zapis znacznika
 * {@link WorldReset}, wyrzucenie graczy (Velocity przenosi ich do lobby) i wyłączenie serwera — Docker
 * ({@code restart: unless-stopped}) uruchamia go ponownie, a reset wykonuje się w onLoad().
 */
public final class ResetRestart {

    /** Tabele CoreProtect z danymi przypisanymi do bloków świata (bez czatu, komend i sesji). */
    static final List<String> COREPROTECT_WORLD_TABLES = List.of("block", "container", "item", "sign", "skull", "entity");

    private ResetRestart() {
    }

    /** Wywoływać na wątku głównym, po zapisaniu zmian w bazie (numer edycji/sezonu, nagrody). */
    public static void restart(CoreContext ctx, WorldReset.Plan plan, Component kickMessage) {
        try {
            WorldReset.schedule(ctx.plugin.getDataFolder(), plan);
        } catch (IOException e) {
            ctx.plugin.getLogger().log(Level.SEVERE, "Nie udało się zapisać znacznika resetu — przerwano", e);
            return;
        }
        String prefix = ctx.config.string("reset.coreprotect-prefiks", "");
        if (prefix != null && !prefix.isBlank() && !prefix.startsWith("${")) {
            ctx.tasks.runAsync(() -> purgeCoreProtect(ctx, prefix));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.kick(kickMessage);
        }
        Bukkit.getScheduler().runTaskLater(ctx.plugin, () -> {
            Bukkit.savePlayers();
            Bukkit.shutdown();
        }, 60L);
    }

    /**
     * Stare wpisy CoreProtect odnoszą się do współrzędnych w nowym świecie o tej samej nazwie — rollback
     * mógłby przywrócić bloki z poprzedniej edycji. Czyścimy tylko tabele z danymi bloków.
     */
    static void purgeCoreProtect(CoreContext ctx, String prefix) {
        if (!prefix.matches("[A-Za-z0-9_]+")) {
            ctx.plugin.getLogger().warning("Nieprawidłowy prefiks tabel CoreProtect: " + prefix);
            return;
        }
        for (String table : COREPROTECT_WORLD_TABLES) {
            try {
                ctx.sql.update("TRUNCATE TABLE " + prefix + table);
            } catch (RuntimeException e) {
                ctx.plugin.getLogger().warning("CoreProtect: nie wyczyszczono " + prefix + table + " — " + e.getMessage());
            }
        }
        ctx.plugin.getLogger().info("Wyczyszczono logi bloków CoreProtect (" + prefix + "*) starego świata.");
    }
}
