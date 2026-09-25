package pl.kudlacze.core.vip;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tabela codziennych nagród Skarbnika (losowanie z wagami). Nagroda odnawia się o północy
 * czasu {@code Europe/Warsaw}. Wartość oczekiwana KC na dzień jest liczona i sprawdzana w testach
 * (docs/EKONOMIA.md zakłada ~5 KC tygodniowo z nagród codziennych).
 */
public final class DailyReward {

    public record Entry(int weight, long kc, double money, List<String> items, String description) {
    }

    private final List<Entry> entries;
    private final int totalWeight;

    public DailyReward(List<Entry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Pusta tabela nagród");
        }
        this.entries = List.copyOf(entries);
        this.totalWeight = entries.stream().mapToInt(Entry::weight).sum();
        if (totalWeight <= 0) {
            throw new IllegalArgumentException("Suma wag nagród musi być dodatnia");
        }
    }

    @SuppressWarnings("unchecked")
    public static DailyReward fromConfig(List<Map<?, ?>> list) {
        List<Entry> out = new ArrayList<>();
        for (Map<?, ?> raw : list) {
            Map<String, Object> m = (Map<String, Object>) raw;
            int weight = ((Number) m.getOrDefault("waga", 1)).intValue();
            long kc = ((Number) m.getOrDefault("kc", 0)).longValue();
            double money = ((Number) m.getOrDefault("pieniadze", 0)).doubleValue();
            List<String> items = m.get("przedmioty") instanceof List<?> l ? (List<String>) l : List.of();
            String desc = String.valueOf(m.getOrDefault("opis", ""));
            out.add(new Entry(weight, kc, money, items, desc));
        }
        return new DailyReward(out);
    }

    public static DailyReward fromSection(ConfigurationSection root, String path) {
        return fromConfig(root.getMapList(path));
    }

    public List<Entry> entries() {
        return entries;
    }

    public Entry roll(Random random) {
        int r = random.nextInt(totalWeight);
        for (Entry e : entries) {
            r -= e.weight();
            if (r < 0) {
                return e;
            }
        }
        return entries.getLast();
    }

    /** Oczekiwana liczba KC z jednej nagrody. */
    public double expectedKc() {
        return entries.stream().mapToDouble(e -> e.kc() * (double) e.weight() / totalWeight).sum();
    }

    /** Następna północ w danej strefie czasowej (moment odnowienia nagrody). */
    public static Instant nextReset(Instant now, ZoneId zone) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return today.plusDays(1).atStartOfDay(zone).toInstant();
    }
}
