package pl.kudlacze.core.player;

import pl.kudlacze.core.db.Sql;

import java.util.Optional;
import java.util.UUID;

/** Nick ↔ UUID wszystkich graczy, którzy kiedykolwiek weszli do sieci (wspólne dla serwerów). */
public final class PlayerDirectory {

    public record Known(UUID uuid, String name) {
    }

    private final Sql sql;

    public PlayerDirectory(Sql sql) {
        this.sql = sql;
    }

    /** Zapis wejścia gracza. Zwraca true, jeśli to jego pierwsze wejście do sieci. */
    public boolean recordJoin(UUID uuid, String name, long now) {
        int updated = sql.update("UPDATE core_gracze SET nick = ?, ostatnie_wejscie = ? WHERE uuid = ?", name, now, uuid);
        if (updated > 0) {
            return false;
        }
        try {
            sql.update("INSERT INTO core_gracze (uuid, nick, pierwsze_wejscie, ostatnie_wejscie) VALUES (?, ?, ?, ?)",
                    uuid, name, now, now);
            return true;
        } catch (Sql.DataAccessException raceWithOtherServer) {
            sql.update("UPDATE core_gracze SET nick = ?, ostatnie_wejscie = ? WHERE uuid = ?", name, now, uuid);
            return false;
        }
    }

    public Optional<Known> byName(String name) {
        return sql.one("SELECT uuid, nick FROM core_gracze WHERE LOWER(nick) = LOWER(?) ORDER BY ostatnie_wejscie DESC LIMIT 1",
                rs -> new Known(UUID.fromString(rs.getString(1)), rs.getString(2)), name);
    }

    public Optional<String> nameOf(UUID uuid) {
        return sql.one("SELECT nick FROM core_gracze WHERE uuid = ?", rs -> rs.getString(1), uuid);
    }
}
