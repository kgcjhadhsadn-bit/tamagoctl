package pl.kudlacze.core.vip;

import pl.kudlacze.core.db.Sql;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/** Cooldowny graczy w bazie (wspólne dla serwerów sieci), np. codzienna nagroda. */
public final class Cooldowns {

    private final Sql sql;

    public Cooldowns(Sql sql) {
        this.sql = sql;
    }

    public OptionalLong until(UUID uuid, String key) {
        return sql.one("SELECT do_kiedy FROM core_cooldowny WHERE uuid = ? AND klucz = ?", rs -> rs.getLong(1), uuid, key)
                .map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    /**
     * Atomowo zajmuje cooldown: jeśli wolny (brak wpisu albo minął), ustawia nowy termin i zwraca pusty
     * wynik; jeśli trwa — zwraca jego koniec i niczego nie zmienia.
     */
    public OptionalLong tryAcquire(UUID uuid, String key, long now, long until) {
        return sql.transaction(c -> {
            List<Long> rows = Sql.query(c, "SELECT do_kiedy FROM core_cooldowny WHERE uuid = ? AND klucz = ? FOR UPDATE",
                    rs -> rs.getLong(1), uuid, key);
            if (!rows.isEmpty() && rows.getFirst() > now) {
                return OptionalLong.of(rows.getFirst());
            }
            if (rows.isEmpty()) {
                Sql.update(c, "INSERT INTO core_cooldowny (uuid, klucz, do_kiedy) VALUES (?, ?, ?)", uuid, key, until);
            } else {
                Sql.update(c, "UPDATE core_cooldowny SET do_kiedy = ? WHERE uuid = ? AND klucz = ?", until, uuid, key);
            }
            return OptionalLong.empty();
        });
    }

    public void clear(UUID uuid, String key) {
        sql.update("DELETE FROM core_cooldowny WHERE uuid = ? AND klucz = ?", uuid, key);
    }
}
