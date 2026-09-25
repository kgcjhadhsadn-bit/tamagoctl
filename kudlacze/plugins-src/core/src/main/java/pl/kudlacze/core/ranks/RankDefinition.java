package pl.kudlacze.core.ranks;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Ranga zdobywana w grze za Kłaki Czarodzieja (definicja z rangi.yml). */
public record RankDefinition(String id, String displayName, String group, long cost, String requires, int days,
                             Material icon, List<String> perks) {

    /** Wczytuje rangi z sekcji {@code rangi} (kolejność z pliku = kolejność w menu). */
    public static Map<String, RankDefinition> load(ConfigurationSection root) {
        Map<String, RankDefinition> out = new LinkedHashMap<>();
        if (root == null) {
            return out;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Material icon = Optional.ofNullable(Material.matchMaterial(s.getString("ikona", "PAPER")))
                    .orElse(Material.PAPER);
            String req = s.getString("wymaga", "");
            out.put(id, new RankDefinition(id, s.getString("nazwa", id), s.getString("grupa", id),
                    s.getLong("koszt", 80), req == null || req.isBlank() ? null : req,
                    s.getInt("dni", 30), icon, new ArrayList<>(s.getStringList("perki"))));
        }
        // walidacja łańcucha wymagań
        for (RankDefinition r : out.values()) {
            if (r.requires() != null && !out.containsKey(r.requires())) {
                throw new IllegalArgumentException("Ranga " + r.id() + " wymaga nieznanej rangi " + r.requires());
            }
            if (r.cost() <= 0 || r.days() <= 0) {
                throw new IllegalArgumentException("Ranga " + r.id() + " musi mieć dodatni koszt i czas trwania");
            }
        }
        return out;
    }
}
