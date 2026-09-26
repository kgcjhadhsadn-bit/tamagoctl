package pl.kudlacze.core.contest;

import pl.kudlacze.core.db.Sql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Zgłoszenia do konkursu Kudłate Chaty (jedno na gracza na edycję) i oceny personelu. */
public final class ContestService {

    public record Entry(int edition, UUID uuid, String name, String world, double x, double y, double z,
                        String description, long submitted, Integer rating, String rater) {
    }

    private static final String COLUMNS = "edycja, uuid, nick, swiat, x, y, z, opis, zgloszono, ocena, oceniajacy";

    private final Sql sql;

    public ContestService(Sql sql) {
        this.sql = sql;
    }

    /** Zgłasza budowlę. Fałsz, gdy gracz ma już zgłoszenie w tej edycji. */
    public boolean submit(int edition, UUID uuid, String name, String world, double x, double y, double z,
                          String description, long now) {
        if (entry(edition, uuid).isPresent()) {
            return false;
        }
        try {
            sql.update("INSERT INTO konkurs_zgloszenia (edycja, uuid, nick, swiat, x, y, z, opis, zgloszono) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", edition, uuid, name, world, x, y, z, description, now);
            return true;
        } catch (Sql.DataAccessException duplicate) {
            return false;
        }
    }

    public boolean withdraw(int edition, UUID uuid) {
        return sql.update("DELETE FROM konkurs_zgloszenia WHERE edycja = ? AND uuid = ?", edition, uuid) > 0;
    }

    public Optional<Entry> entry(int edition, UUID uuid) {
        return sql.one("SELECT " + COLUMNS + " FROM konkurs_zgloszenia WHERE edycja = ? AND uuid = ?",
                ContestService::map, edition, uuid);
    }

    public Optional<Entry> byName(int edition, String name) {
        return sql.one("SELECT " + COLUMNS + " FROM konkurs_zgloszenia WHERE edycja = ? AND LOWER(nick) = LOWER(?)",
                ContestService::map, edition, name);
    }

    /** Wszystkie zgłoszenia: najpierw ocenione (od najwyższej oceny), remis — wcześniejsze zgłoszenie. */
    public List<Entry> list(int edition) {
        return sql.query("SELECT " + COLUMNS + " FROM konkurs_zgloszenia WHERE edycja = ? "
                + "ORDER BY CASE WHEN ocena IS NULL THEN 1 ELSE 0 END, ocena DESC, zgloszono ASC", ContestService::map, edition);
    }

    public boolean rate(int edition, UUID uuid, int rating, UUID rater) {
        if (rating < 1 || rating > 10) {
            throw new IllegalArgumentException("Ocena musi być w skali 1–10");
        }
        return sql.update("UPDATE konkurs_zgloszenia SET ocena = ?, oceniajacy = ? WHERE edycja = ? AND uuid = ?",
                rating, rater, edition, uuid) > 0;
    }

    /** Zwycięzcy: tylko ocenione zgłoszenia, najwyżej {@code places}. */
    public List<Entry> winners(int edition, int places) {
        return list(edition).stream().filter(e -> e.rating() != null).limit(places).toList();
    }

    private static Entry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int value = rs.getInt(10);
        Integer rating = rs.wasNull() ? null : value; // wasNull() dotyczy ostatnio odczytanej kolumny
        return new Entry(rs.getInt(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4),
                rs.getDouble(5), rs.getDouble(6), rs.getDouble(7), rs.getString(8), rs.getLong(9),
                rating, rs.getString(11));
    }
}
