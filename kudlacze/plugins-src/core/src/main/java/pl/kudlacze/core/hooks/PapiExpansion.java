package pl.kudlacze.core.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/** Rozszerzenie PlaceholderAPI {@code %kudlacze_...%} — ładowane tylko, gdy PAPI jest włączone. */
public final class PapiExpansion extends PlaceholderExpansion {

    private final Placeholders registry;
    private final String version;

    public PapiExpansion(Placeholders registry, String version) {
        this.registry = registry;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "kudlacze";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Kudłacze";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return registry.resolve(player, params);
    }
}
