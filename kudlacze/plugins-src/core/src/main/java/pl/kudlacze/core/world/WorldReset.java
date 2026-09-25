package pl.kudlacze.core.world;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Zaplanowane czyszczenie światów (nowa edycja survivalu, nowy sezon). Wykonywane w onLoad(),
 * zanim serwer załaduje światy. Pełna logika w {@link #runPending(JavaPlugin)}.
 */
public final class WorldReset {

    private WorldReset() {
    }

    /** Wykonuje reset, jeśli w folderze pluginu leży znacznik zaplanowany przed restartem. */
    public static void runPending(JavaPlugin plugin) {
        // uzupełniane w kamieniu M4 (edycje i sezony)
    }
}
