package pl.kudlacze.core.seasons;

import pl.kudlacze.core.db.KeyValueStore;
import pl.kudlacze.core.db.Sql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Dane sezonu Seasons: numer sezonu, punkty, kryształy, czas gry, log questów, wyzwania, archiwum.
 * Wspólne dla serwerów (ranking na hologramie w lobby czyta te same tabele).
 */
public final class SeasonService {

    public static final String KEY_SEASON = "seasons.sezon";
    public static final String KEY_START = "seasons.start";

    public record Entry(UUID uuid, String name, long points, long crystals, long playtimeSeconds) {
    }

    public record Archived(int season, int place, UUID uuid, String name, long points) {
    }

    private final Sql sql;
    private final KeyValueStore kv;

    public SeasonService(Sql sql, KeyValueStore kv) {
        this.sql = sql;
        this.kv = kv;
    }

    public int currentSeason() {
        return (int) kv.getLong(KEY_SEASON, 1);
    }

    public long seasonStart(long fallback) {
        return kv.getLong(KEY_START, fallback);
    }

    private void ensureRow(int season, UUID uuid, String name) {
        int updated = sql.update("UPDATE sezon_gracze SET nick = ? WHERE sezon = ? AND uuid = ?", name, season, uuid);
        if (updated == 0) {
            try {
                sql.update("INSERT INTO sezon_gracze (sezon, uuid, nick, punkty, krysztaly, czas_gry_s) "
                        + "VALUES (?, ?, ?, 0, 0, 0)", season, uuid, name);
            } catch (Sql.DataAccessException raceWithOtherThread) {
                // wiersz założony równolegle — dalsze UPDATE-y zadziałają
            }
        }
    }

    public void addPoints(UUID uuid, String name, long points, long crystals) {
        int season = currentSeason();
        ensureRow(season, uuid, name);
        sql.update("UPDATE sezon_gracze SET punkty = punkty + ?, krysztaly = krysztaly + ? WHERE sezon = ? AND uuid = ?",
                points, crystals, season, uuid);
    }

    /** Dodaje czas gry; zwraca łączny czas gry w sezonie (sekundy). */
    public long addPlaytime(UUID uuid, String name, long seconds) {
        int season = currentSeason();
        ensureRow(season, uuid, name);
        sql.update("UPDATE sezon_gracze SET czas_gry_s = czas_gry_s + ? WHERE sezon = ? AND uuid = ?", seconds, season, uuid);
        return sql.one("SELECT czas_gry_s FROM sezon_gracze WHERE sezon = ? AND uuid = ?", rs -> rs.getLong(1),
                season, uuid).orElse(seconds);
    }

    /** Zdejmuje kryształy (sklep kryształów). Fałsz = za mało. */
    public boolean spendCrystals(UUID uuid, long crystals) {
        return sql.update("UPDATE sezon_gracze SET krysztaly = krysztaly - ? WHERE sezon = ? AND uuid = ? AND krysztaly >= ?",
                crystals, currentSeason(), uuid, crystals) > 0;
    }

    public Optional<Entry> entry(UUID uuid) {
        return sql.one("SELECT uuid, nick, punkty, krysztaly, czas_gry_s FROM sezon_gracze WHERE sezon = ? AND uuid = ?",
                SeasonService::map, currentSeason(), uuid);
    }

    /** Ranking: punkty malejąco, remis — więcej kryształów, potem krótszy czas gry, potem nick. */
    public List<Entry> top(int limit) {
        return sql.query("SELECT uuid, nick, punkty, krysztaly, czas_gry_s FROM sezon_gracze WHERE sezon = ? AND punkty > 0 "
                        + "ORDER BY punkty DESC, krysztaly DESC, czas_gry_s ASC, nick ASC LIMIT " + Math.max(1, limit),
                SeasonService::map, currentSeason());
    }

    /** Miejsce gracza w rankingu (1 = pierwszy), puste gdy nie ma punktów. */
    public Optional<Integer> place(UUID uuid) {
        Optional<Entry> me = entry(uuid);
        if (me.isEmpty() || me.get().points() <= 0) {
            return Optional.empty();
        }
        Entry e = me.get();
        long better = sql.one("SELECT COUNT(*) FROM sezon_gracze WHERE sezon = ? AND punkty > 0 AND ("
                        + "punkty > ? OR (punkty = ? AND krysztaly > ?) "
                        + "OR (punkty = ? AND krysztaly = ? AND czas_gry_s < ?) "
                        + "OR (punkty = ? AND krysztaly = ? AND czas_gry_s = ? AND nick < ?))",
                rs -> rs.getLong(1), currentSeason(), e.points(), e.points(), e.crystals(), e.points(), e.crystals(),
                e.playtimeSeconds(), e.points(), e.crystals(), e.playtimeSeconds(), e.name()).orElse(0L);
        return Optional.of((int) better + 1);
    }

    // ---- questy -----------------------------------------------------------------------

    public void logQuest(UUID uuid, String quest, int amount, long points, long kc, long now) {
        sql.update("INSERT INTO sezon_questy_log (sezon, uuid, quest, ilosc, punkty, kc, czas) VALUES (?, ?, ?, ?, ?, ?, ?)",
                currentSeason(), uuid, quest, amount, points, kc, now);
    }

    /** Suma KC z questów od danego momentu (limit tygodniowy). */
    public long questKcSince(UUID uuid, long since) {
        return sql.one("SELECT COALESCE(SUM(kc), 0) FROM sezon_questy_log WHERE uuid = ? AND czas >= ?",
                rs -> rs.getLong(1), uuid, since).orElse(0L);
    }

    /** Ile razy gracz oddał dany quest od podanego momentu (limit dzienny). */
    public int questCountSince(UUID uuid, String quest, long since) {
        return sql.one("SELECT COUNT(*) FROM sezon_questy_log WHERE uuid = ? AND quest = ? AND czas >= ?",
                rs -> rs.getInt(1), uuid, quest, since).orElse(0);
    }

    /** Liczba oddanych questów od podanego momentu, dla każdego questu (menu Kwatermistrza). */
    public java.util.Map<String, Integer> questCountsSince(UUID uuid, long since) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        sql.query("SELECT quest, COUNT(*) FROM sezon_questy_log WHERE uuid = ? AND czas >= ? GROUP BY quest",
                rs -> out.put(rs.getString(1), rs.getInt(2)), uuid, since);
        return out;
    }

    // ---- wyzwania ---------------------------------------------------------------------

    /**
     * Dodaje postęp wyzwania. Zwraca true dokładnie raz — gdy postęp właśnie osiągnął cel
     * (wyzwanie ukończone w tym sezonie).
     */
    public boolean progressChallenge(UUID uuid, String challenge, long delta, long goal, long now) {
        int season = currentSeason();
        return sql.transaction(c -> {
            var rows = Sql.query(c, "SELECT postep, ukonczono FROM sezon_wyzwania WHERE sezon = ? AND uuid = ? AND wyzwanie = ? FOR UPDATE",
                    rs -> new long[]{rs.getLong(1), rs.getObject(2) == null ? 0 : 1}, season, uuid, challenge);
            if (rows.isEmpty()) {
                Sql.update(c, "INSERT INTO sezon_wyzwania (sezon, uuid, wyzwanie, postep) VALUES (?, ?, ?, 0)", season, uuid, challenge);
                rows = List.of(new long[]{0, 0});
            }
            long[] row = rows.getFirst();
            if (row[1] == 1) {
                return false;
            }
            long next = Math.min(goal, row[0] + delta);
            boolean done = next >= goal;
            if (done) {
                Sql.update(c, "UPDATE sezon_wyzwania SET postep = ?, ukonczono = ? WHERE sezon = ? AND uuid = ? AND wyzwanie = ?",
                        next, now, season, uuid, challenge);
            } else {
                Sql.update(c, "UPDATE sezon_wyzwania SET postep = ? WHERE sezon = ? AND uuid = ? AND wyzwanie = ?",
                        next, season, uuid, challenge);
            }
            return done;
        });
    }

    public long challengeProgress(UUID uuid, String challenge) {
        return sql.one("SELECT postep FROM sezon_wyzwania WHERE sezon = ? AND uuid = ? AND wyzwanie = ?",
                rs -> rs.getLong(1), currentSeason(), uuid, challenge).orElse(0L);
    }

    /** Stan wyzwań gracza w bieżącym sezonie: id → [postęp, 1 gdy ukończone]. */
    public java.util.Map<String, long[]> challengeStates(UUID uuid) {
        java.util.Map<String, long[]> out = new java.util.HashMap<>();
        sql.query("SELECT wyzwanie, postep, ukonczono FROM sezon_wyzwania WHERE sezon = ? AND uuid = ?",
                rs -> out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getObject(3) == null ? 0 : 1}),
                currentSeason(), uuid);
        return out;
    }

    // ---- koniec sezonu ---------------------------------------------------------------

    /**
     * Zamyka sezon: zapisuje top {@code archiveSize} do archiwum, zwraca je i przechodzi do
     * następnego sezonu. Idempotentne względem numeru sezonu (drugi raz nic nie archiwizuje).
     */
    public List<Archived> closeSeason(int season, int archiveSize, long now) {
        return sql.transaction(c -> {
            long current = Sql.query(c, "SELECT v FROM core_kv WHERE k = ? FOR UPDATE",
                    rs -> Long.parseLong(rs.getString(1)), KEY_SEASON).stream().findFirst().orElse(1L);
            if (current != season) {
                return List.<Archived>of();
            }
            List<Entry> top = Sql.query(c, "SELECT uuid, nick, punkty, krysztaly, czas_gry_s FROM sezon_gracze "
                            + "WHERE sezon = ? AND punkty > 0 ORDER BY punkty DESC, krysztaly DESC, czas_gry_s ASC, nick ASC LIMIT "
                            + Math.max(1, archiveSize), SeasonService::map, season);
            List<Archived> out = new java.util.ArrayList<>();
            for (int i = 0; i < top.size(); i++) {
                Entry e = top.get(i);
                Sql.update(c, "INSERT INTO sezon_archiwum (sezon, miejsce, uuid, nick, punkty, zakonczono) VALUES (?, ?, ?, ?, ?, ?)",
                        season, i + 1, e.uuid(), e.name(), e.points(), now);
                out.add(new Archived(season, i + 1, e.uuid(), e.name(), e.points()));
            }
            upsert(c, KEY_SEASON, String.valueOf(season + 1));
            upsert(c, KEY_START, String.valueOf(now));
            return out;
        });
    }

    public List<Archived> archive(int season) {
        return sql.query("SELECT sezon, miejsce, uuid, nick, punkty FROM sezon_archiwum WHERE sezon = ? ORDER BY miejsce",
                rs -> new Archived(rs.getInt(1), rs.getInt(2), UUID.fromString(rs.getString(3)), rs.getString(4), rs.getLong(5)),
                season);
    }

    private static void upsert(java.sql.Connection c, String k, String v) throws java.sql.SQLException {
        if (Sql.update(c, "UPDATE core_kv SET v = ? WHERE k = ?", v, k) == 0) {
            Sql.update(c, "INSERT INTO core_kv (k, v) VALUES (?, ?)", k, v);
        }
    }

    private static Entry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
    }
}
