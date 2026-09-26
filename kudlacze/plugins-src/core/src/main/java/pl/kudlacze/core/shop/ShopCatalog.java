package pl.kudlacze.core.shop;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Katalog sklepu kosmetycznego. Zgodnie z EULA Minecrafta za prawdziwe pieniądze wolno sprzedawać wyłącznie
 * rzeczy bez wpływu na rozgrywkę — katalog odrzuca każdą pozycję spoza {@link #COSMETIC_TYPES}
 * (rangi, KC, przedmioty, waluty i inne przewagi nie przejdą walidacji).
 */
public final class ShopCatalog {

    /** Jedyne dozwolone typy pozycji — czysto wizualne. */
    public static final Set<String> COSMETIC_TYPES = Set.of("TYTUL", "KOLOR_NICKU", "CZASTECZKI");

    public record Item(String id, String type, String name, int priceGrosze, String grants) {
    }

    private final boolean enabled;
    private final Map<String, Item> items;

    private ShopCatalog(boolean enabled, Map<String, Item> items) {
        this.enabled = enabled;
        this.items = items;
    }

    /** Wczytuje i waliduje sekcję {@code sklep}. Pozycja niekosmetyczna = wyjątek (plugin nie włączy sklepu). */
    public static ShopCatalog load(ConfigurationSection section) {
        Map<String, Item> items = new LinkedHashMap<>();
        boolean enabled = section != null && section.getBoolean("wlaczony", false);
        ConfigurationSection list = section == null ? null : section.getConfigurationSection("pozycje");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(id);
                String type = s.getString("typ", "").toUpperCase(Locale.ROOT);
                if (!COSMETIC_TYPES.contains(type)) {
                    throw new IllegalArgumentException("Pozycja sklepu '" + id + "' ma typ '" + type
                            + "' — dozwolone są tylko kosmetyki " + COSMETIC_TYPES + " (EULA: bez przewag za pieniądze)");
                }
                int price = s.getInt("cena-grosze", 0);
                if (price <= 0) {
                    throw new IllegalArgumentException("Pozycja sklepu '" + id + "' musi mieć dodatnią cenę");
                }
                items.put(id, new Item(id, type, s.getString("nazwa", id), price, s.getString("nadaje", "")));
            }
        }
        return new ShopCatalog(enabled, items);
    }

    public boolean enabled() {
        return enabled;
    }

    public Map<String, Item> items() {
        return items;
    }
}
