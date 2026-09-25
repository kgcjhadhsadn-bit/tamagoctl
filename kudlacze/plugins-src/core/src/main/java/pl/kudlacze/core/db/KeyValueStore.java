package pl.kudlacze.core.db;

import java.util.Optional;

/**
 * Proste ustawienia sieci w tabeli {@code core_kv} (np. numer edycji, numer sezonu,
 * hash wykonanego bootstrapu, pozycja spawnu). Współdzielone przez wszystkie serwery.
 */
public final class KeyValueStore {

    private final Sql sql;

    public KeyValueStore(Sql sql) {
        this.sql = sql;
    }

    public Optional<String> get(String key) {
        return sql.one("SELECT v FROM core_kv WHERE k = ?", rs -> rs.getString(1), key);
    }

    public long getLong(String key, long def) {
        return get(key).map(Long::parseLong).orElse(def);
    }

    public void put(String key, String value) {
        int updated = sql.update("UPDATE core_kv SET v = ? WHERE k = ?", value, key);
        if (updated == 0) {
            sql.update("INSERT INTO core_kv (k, v) VALUES (?, ?)", key, value);
        }
    }

    public void delete(String key) {
        sql.update("DELETE FROM core_kv WHERE k = ?", key);
    }
}
