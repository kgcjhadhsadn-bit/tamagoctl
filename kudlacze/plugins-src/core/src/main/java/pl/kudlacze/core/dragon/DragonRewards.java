package pl.kudlacze.core.dragon;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tablica obrażeń Smoka Kudłatego i podział nagród (czysta logika — testy jednostkowe). */
public final class DragonRewards {

    public record Hit(UUID uuid, String name, double damage) {
    }

    public record Reward(int place, UUID uuid, String name, double damage, long points, long kc, boolean title) {
    }

    private final Map<UUID, Hit> hits = new ConcurrentHashMap<>();

    /** Dolicza obrażenia gracza (nick aktualizowany przy każdym trafieniu). */
    public void record(UUID uuid, String name, double damage) {
        if (damage <= 0) {
            return;
        }
        hits.merge(uuid, new Hit(uuid, name, damage), (a, b) -> new Hit(uuid, name, a.damage() + b.damage()));
    }

    public Collection<Hit> hits() {
        return List.copyOf(hits.values());
    }

    public void clear() {
        hits.clear();
    }

    /** Ranking obrażeń: malejąco, remis — nick alfabetycznie. */
    public List<Hit> ranking() {
        List<Hit> list = new ArrayList<>(hits.values());
        list.sort(Comparator.comparingDouble(Hit::damage).reversed().thenComparing(Hit::name));
        return list;
    }

    /**
     * Nagrody za miejsca: {@code points.get(i)}/{@code kc.get(i)} dla miejsca i+1, tytuł dla pierwszych
     * {@code titlePlaces}. Gracze poniżej {@code minDamage} nie są klasyfikowani (ochrona przed „jednym
     * strzałem z drugiego konta”).
     */
    public static List<Reward> compute(List<Hit> ranking, List<Long> points, List<Long> kc, int titlePlaces,
                                       double minDamage) {
        int places = Math.max(points.size(), Math.max(kc.size(), titlePlaces));
        List<Reward> out = new ArrayList<>();
        int place = 0;
        for (Hit h : ranking) {
            if (h.damage() < minDamage || place >= places) {
                continue;
            }
            long p = place < points.size() ? points.get(place) : 0;
            long k = place < kc.size() ? kc.get(place) : 0;
            out.add(new Reward(place + 1, h.uuid(), h.name(), h.damage(), p, k, place < titlePlaces));
            place++;
        }
        return out;
    }
}
