package pl.kudlacze.core.hooks;

import org.bukkit.entity.Player;

import java.util.Optional;

/** Odczyt meta/prefiksów gracza online (implementacja: LuckPerms; zapasowo: brak meta). */
public interface MetaReader {

    Optional<String> meta(Player player, String key);

    String prefix(Player player);

    String suffix(Player player);

    String primaryGroup(Player player);

    /** Uprawnienie gracza, który jeszcze nie jest w pełni zalogowany (dane wczytane przez LuckPerms). */
    default boolean hasPermissionOffline(java.util.UUID uuid, String permission) {
        return false;
    }

    /** Wersja bez pluginu uprawnień (np. w testach). */
    MetaReader NONE = new MetaReader() {
        @Override
        public Optional<String> meta(Player player, String key) {
            return Optional.empty();
        }

        @Override
        public String prefix(Player player) {
            return "";
        }

        @Override
        public String suffix(Player player) {
            return "";
        }

        @Override
        public String primaryGroup(Player player) {
            return "default";
        }
    };

    default double metaDouble(Player player, String key, double def) {
        return meta(player, key).map(v -> {
            try {
                return Double.parseDouble(v.replace(',', '.'));
            } catch (NumberFormatException e) {
                return def;
            }
        }).orElse(def);
    }
}
