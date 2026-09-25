package pl.kudlacze.core.seasons;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Definicje z questy.yml: questy Kwatermistrza (oddaj przedmioty → punkty + KC), wyzwania sezonu
 * (liczniki zdarzeń → kryształy + punkty), sklep kryształów oraz punkty za czas gry.
 */
public final class QuestBook {

    /** Quest dostawy: oddaj {@code amount} przedmiotów z listy {@code items}. */
    public record Quest(String id, String name, List<Material> items, int amount, long points, long kc, int dailyLimit,
                        Material icon) {
        public boolean accepts(Material m) {
            return items.contains(m);
        }
    }

    public enum ChallengeType { KOPANIE, ZABIJANIE, LOWIENIE, ROZMNAZANIE, ZACZAROWANIE, CZAS_GRY }

    /**
     * Wyzwanie: cel {@code goal} zdarzeń danego typu. {@code targets} — materiały (KOPANIE) albo typy mobów
     * (ZABIJANIE, słowo WROGIE = dowolny wrogi mob); pusty zbiór = dowolne.
     */
    public record Challenge(String id, String name, String description, ChallengeType type, Set<String> targets,
                            long goal, long crystals, long points, Material icon) {
        public boolean matches(String target) {
            return targets.isEmpty() || targets.contains(target);
        }
    }

    public record ShopItem(String id, String name, Material material, int amount, long price) {
    }

    private final long weeklyKcLimit;
    private final int playtimeEveryMinutes;
    private final long playtimePoints;
    private final Map<String, Quest> quests;
    private final Map<String, Challenge> challenges;
    private final Map<String, ShopItem> shop;

    public QuestBook(long weeklyKcLimit, int playtimeEveryMinutes, long playtimePoints, Map<String, Quest> quests,
                     Map<String, Challenge> challenges, Map<String, ShopItem> shop) {
        this.weeklyKcLimit = weeklyKcLimit;
        this.playtimeEveryMinutes = playtimeEveryMinutes;
        this.playtimePoints = playtimePoints;
        this.quests = quests;
        this.challenges = challenges;
        this.shop = shop;
    }

    public static QuestBook load(ConfigurationSection root) {
        Map<String, Quest> quests = new LinkedHashMap<>();
        ConfigurationSection qs = root.getConfigurationSection("questy");
        if (qs != null) {
            for (String id : qs.getKeys(false)) {
                ConfigurationSection q = qs.getConfigurationSection(id);
                List<Material> items = materials(q.getStringList("przedmioty"), id);
                if (items.isEmpty()) {
                    throw new IllegalArgumentException("Quest " + id + ": brak przedmiotów");
                }
                quests.put(id, new Quest(id, q.getString("nazwa", id), items, positive(q.getInt("ilosc", 1), id),
                        q.getLong("punkty", 0), q.getLong("kc", 0), q.getInt("limit-dzienny", 0),
                        material(q.getString("ikona", items.getFirst().name()), id)));
            }
        }
        Map<String, Challenge> challenges = new LinkedHashMap<>();
        ConfigurationSection cs = root.getConfigurationSection("wyzwania");
        if (cs != null) {
            for (String id : cs.getKeys(false)) {
                ConfigurationSection c = cs.getConfigurationSection(id);
                ChallengeType type = ChallengeType.valueOf(c.getString("typ", "").toUpperCase(Locale.ROOT));
                Set<String> targets = c.getStringList("cele").stream().map(s -> s.toUpperCase(Locale.ROOT))
                        .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
                validateTargets(type, targets, id);
                challenges.put(id, new Challenge(id, c.getString("nazwa", id), c.getString("opis", ""), type, targets,
                        positive(c.getLong("cel", 1), id), c.getLong("krysztaly", 0), c.getLong("punkty", 0),
                        material(c.getString("ikona", "PAPER"), id)));
            }
        }
        Map<String, ShopItem> shop = new LinkedHashMap<>();
        ConfigurationSection ss = root.getConfigurationSection("sklep-krysztalow");
        if (ss != null) {
            for (String id : ss.getKeys(false)) {
                ConfigurationSection s = ss.getConfigurationSection(id);
                shop.put(id, new ShopItem(id, s.getString("nazwa", id), material(s.getString("przedmiot", ""), id),
                        positive(s.getInt("ilosc", 1), id), positive(s.getLong("cena", 1), id)));
            }
        }
        return new QuestBook(root.getLong("limit-kc-tygodniowo", 10), Math.max(1, root.getInt("czas-gry.co-ile-minut", 5)),
                root.getLong("czas-gry.punkty", 1), quests, challenges, shop);
    }

    private static void validateTargets(ChallengeType type, Set<String> targets, String id) {
        for (String t : targets) {
            switch (type) {
                case KOPANIE -> material(t, id);
                case ZABIJANIE -> {
                    if (!t.equals("WROGIE")) {
                        EntityType.valueOf(t);
                    }
                }
                default -> throw new IllegalArgumentException("Wyzwanie " + id + ": typ " + type + " nie ma celów");
            }
        }
    }

    private static List<Material> materials(List<String> names, String id) {
        List<Material> out = new ArrayList<>();
        for (String n : names) {
            out.add(material(n, id));
        }
        return out;
    }

    private static Material material(String name, String id) {
        Material m = Material.matchMaterial(name);
        if (m == null) {
            throw new IllegalArgumentException("Nieznany materiał '" + name + "' w " + id);
        }
        return m;
    }

    private static <N extends Number> N positive(N n, String id) {
        if (n.longValue() <= 0) {
            throw new IllegalArgumentException("Wartość musi być dodatnia w " + id);
        }
        return n;
    }

    /**
     * Ile KC przyznać za quest przy tygodniowym limicie: nagroda questu przycięta do tego, co zostało
     * z limitu (punkty rankingowe przyznawane są zawsze).
     */
    public static long cappedKc(long questKc, long earnedThisWeek, long weeklyLimit) {
        if (weeklyLimit <= 0) {
            return questKc;
        }
        return Math.max(0, Math.min(questKc, weeklyLimit - earnedThisWeek));
    }

    public long weeklyKcLimit() {
        return weeklyKcLimit;
    }

    public int playtimeEveryMinutes() {
        return playtimeEveryMinutes;
    }

    public long playtimePoints() {
        return playtimePoints;
    }

    public Map<String, Quest> quests() {
        return quests;
    }

    public Map<String, Challenge> challenges() {
        return challenges;
    }

    public Map<String, ShopItem> shop() {
        return shop;
    }

    public List<Challenge> challengesOf(ChallengeType type) {
        return challenges.values().stream().filter(c -> c.type() == type).toList();
    }
}
