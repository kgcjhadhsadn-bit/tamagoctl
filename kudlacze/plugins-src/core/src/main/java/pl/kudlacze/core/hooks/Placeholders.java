package pl.kudlacze.core.hooks;

import org.bukkit.OfflinePlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Rejestr placeholderów {@code %kudlacze_<nazwa>%}. Moduły dopisują swoje wartości; rozszerzenie
 * PlaceholderAPI tylko deleguje do rejestru. Nazwy z parametrem, np. {@code top_3_name}, obsługuje
 * dostawca zarejestrowany pod prefiksem (np. {@code top_}).
 */
public final class Placeholders {

    private final Map<String, BiFunction<OfflinePlayer, String, String>> exact = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<OfflinePlayer, String, String>> prefixed = new ConcurrentHashMap<>();

    /** Placeholder bez parametrów. */
    public void register(String name, java.util.function.Function<OfflinePlayer, String> provider) {
        exact.put(name, (p, rest) -> provider.apply(p));
    }

    /** Placeholder z parametrem: dostawca dostaje resztę nazwy po prefiksie. */
    public void registerPrefix(String prefix, BiFunction<OfflinePlayer, String, String> provider) {
        prefixed.put(prefix, provider);
    }

    public String resolve(OfflinePlayer player, String params) {
        BiFunction<OfflinePlayer, String, String> f = exact.get(params);
        if (f != null) {
            return f.apply(player, "");
        }
        for (Map.Entry<String, BiFunction<OfflinePlayer, String, String>> e : prefixed.entrySet()) {
            if (params.startsWith(e.getKey())) {
                return e.getValue().apply(player, params.substring(e.getKey().length()));
            }
        }
        return null;
    }
}
