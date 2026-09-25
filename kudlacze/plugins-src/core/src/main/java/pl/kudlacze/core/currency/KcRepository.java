package pl.kudlacze.core.currency;

import pl.kudlacze.core.db.Sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Trwałość salda Kłaków Czarodzieja i partii fizycznych przedmiotów KC. */
public final class KcRepository {

    public record Transaction(long id, UUID uuid, long change, long balanceAfter, String type, String source,
                              String server, long time) {
    }

    private final Sql sql;

    public KcRepository(Sql sql) {
        this.sql = sql;
    }

    public long balance(UUID uuid) {
        return sql.one("SELECT saldo FROM kc_saldo WHERE uuid = ?", rs -> rs.getLong(1), uuid).orElse(0L);
    }

    /**
     * Atomowa zmiana salda z wpisem do logu. Zwraca nowe saldo albo pusty wynik,
     * jeśli po zmianie saldo byłoby ujemne (brak środków).
     */
    public OptionalLong apply(UUID uuid, long delta, String type, String source, String server, long now) {
        return sql.transaction(c -> {
            long current = lockBalance(c, uuid);
            long next = current + delta;
            if (next < 0) {
                return OptionalLong.empty();
            }
            Sql.update(c, "UPDATE kc_saldo SET saldo = ? WHERE uuid = ?", next, uuid);
            log(c, uuid, delta, next, type, source, server, now);
            return OptionalLong.of(next);
        });
    }

    /** Ustawienie salda (korekta admina) — też logowane jako różnica. */
    public long set(UUID uuid, long value, String source, String server, long now) {
        return sql.transaction(c -> {
            long current = lockBalance(c, uuid);
            Sql.update(c, "UPDATE kc_saldo SET saldo = ? WHERE uuid = ?", value, uuid);
            log(c, uuid, value - current, value, "ADMIN_USTAW", source, server, now);
            return value;
        });
    }

    /** Przelew między graczami w jednej transakcji. Fałsz = brak środków. */
    public boolean transfer(UUID from, UUID to, long amount, String server, long now) {
        return sql.transaction(c -> {
            // blokujemy wiersze w stałej kolejności, żeby uniknąć zakleszczeń
            boolean fromFirst = from.toString().compareTo(to.toString()) < 0;
            long fromBal = fromFirst ? lockBalance(c, from) : 0;
            long toBal = lockBalance(c, to);
            if (!fromFirst) {
                fromBal = lockBalance(c, from);
            }
            if (fromBal < amount) {
                return false;
            }
            Sql.update(c, "UPDATE kc_saldo SET saldo = ? WHERE uuid = ?", fromBal - amount, from);
            Sql.update(c, "UPDATE kc_saldo SET saldo = ? WHERE uuid = ?", toBal + amount, to);
            log(c, from, -amount, fromBal - amount, "PRZELEW_WYCHODZACY", to.toString(), server, now);
            log(c, to, amount, toBal + amount, "PRZELEW_PRZYCHODZACY", from.toString(), server, now);
            return true;
        });
    }

    public List<Transaction> history(UUID uuid, int limit) {
        return sql.query("SELECT id, uuid, zmiana, saldo_po, typ, zrodlo, serwer, czas FROM kc_transakcje "
                        + "WHERE uuid = ? ORDER BY czas DESC, id DESC LIMIT " + Math.max(1, Math.min(limit, 100)),
                rs -> new Transaction(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getLong(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)), uuid);
    }

    // ---- partie przedmiotów -------------------------------------------------------------

    public void createBatch(UUID batchId, long amount, UUID owner, long now) {
        sql.update("INSERT INTO kc_przedmioty (id, ilosc, pozostalo, wydano_dla, wydano) VALUES (?, ?, ?, ?, ?)",
                batchId, amount, amount, owner, now);
    }

    /**
     * Wypłata z konta na przedmioty: zdejmuje saldo i zakłada partię w jednej transakcji.
     * Pusty wynik = brak środków.
     */
    public OptionalLong withdrawToBatch(UUID uuid, long amount, UUID batchId, String server, long now) {
        return sql.transaction(c -> {
            long current = lockBalance(c, uuid);
            if (current < amount) {
                return OptionalLong.empty();
            }
            long next = current - amount;
            Sql.update(c, "UPDATE kc_saldo SET saldo = ? WHERE uuid = ?", next, uuid);
            Sql.update(c, "INSERT INTO kc_przedmioty (id, ilosc, pozostalo, wydano_dla, wydano) VALUES (?, ?, ?, ?, ?)",
                    batchId, amount, amount, uuid, now);
            log(c, uuid, -amount, next, "WYPLATA", "partia " + batchId, server, now);
            return OptionalLong.of(next);
        });
    }

    /** Wynik wpłaty: ile KC uznano (reszta partii była już wyczerpana — duplikat) i saldo po wpłacie. */
    public record Deposit(long credited, long balanceAfter) {
    }

    /**
     * Wpłata przedmiotów z partii: uznaje co najwyżej tyle KC, ile w partii zostało do wpłacenia.
     * Nadwyżka to zduplikowane przedmioty. Nieznana albo wyczerpana partia = pusty wynik.
     */
    public Optional<Deposit> depositFromBatch(UUID uuid, UUID batchId, long amount, String server, long now) {
        return sql.transaction(c -> {
            List<Long> rows = Sql.query(c, "SELECT pozostalo FROM kc_przedmioty WHERE id = ? FOR UPDATE",
                    rs -> rs.getLong(1), batchId);
            if (rows.isEmpty() || rows.getFirst() <= 0) {
                return Optional.<Deposit>empty();
            }
            long credit = Math.min(amount, rows.getFirst());
            Sql.update(c, "UPDATE kc_przedmioty SET pozostalo = pozostalo - ? WHERE id = ?", credit, batchId);
            long current = lockBalance(c, uuid);
            long next = current + credit;
            Sql.update(c, "UPDATE kc_saldo SET saldo = ? WHERE uuid = ?", next, uuid);
            log(c, uuid, credit, next, "WPLATA", "partia " + batchId, server, now);
            return Optional.of(new Deposit(credit, next));
        });
    }

    public Optional<Long> batchRemaining(UUID batchId) {
        return sql.one("SELECT pozostalo FROM kc_przedmioty WHERE id = ?", rs -> rs.getLong(1), batchId);
    }

    // ---- wewnętrzne ---------------------------------------------------------------------

    private static long lockBalance(Connection c, UUID uuid) throws SQLException {
        List<Long> rows = Sql.query(c, "SELECT saldo FROM kc_saldo WHERE uuid = ? FOR UPDATE",
                rs -> rs.getLong(1), uuid);
        if (!rows.isEmpty()) {
            return rows.getFirst();
        }
        try {
            Sql.update(c, "INSERT INTO kc_saldo (uuid, saldo) VALUES (?, 0)", uuid);
        } catch (SQLException duplicate) {
            // równoległe założenie wiersza przez inny serwer — wystarczy go zablokować
        }
        return Sql.query(c, "SELECT saldo FROM kc_saldo WHERE uuid = ? FOR UPDATE",
                rs -> rs.getLong(1), uuid).getFirst();
    }

    private static void log(Connection c, UUID uuid, long change, long after, String type, String source,
                            String server, long now) throws SQLException {
        Sql.update(c, "INSERT INTO kc_transakcje (uuid, zmiana, saldo_po, typ, zrodlo, serwer, czas) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", uuid, change, after, type, truncate(source, 128), server, now);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
