package pl.kudlacze.core.titles;

import pl.kudlacze.core.db.Sql;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tytuły kosmetyczne (np. „Smokobójca”, „Mistrz Sezonu”) — zdobywane wyłącznie w grze, bez wpływu
 * na rozgrywkę. Gracz może mieć wiele tytułów i wybrać jeden aktywny (czat, TAB).
 */
public final class TitleService {

    public record Owned(String id, String source, long granted) {
    }

    private final Sql sql;

    public TitleService(Sql sql) {
        this.sql = sql;
    }

    /** Nadaje tytuł (ponowne nadanie aktualizuje źródło i datę). */
    public void grant(UUID uuid, String titleId, String source, long now) {
        int updated = sql.update("UPDATE core_tytuly SET zrodlo = ?, nadano = ? WHERE uuid = ? AND tytul = ?",
                source, now, uuid, titleId);
        if (updated == 0) {
            sql.update("INSERT INTO core_tytuly (uuid, tytul, zrodlo, nadano) VALUES (?, ?, ?, ?)",
                    uuid, titleId, source, now);
        }
    }

    public boolean revoke(UUID uuid, String titleId) {
        sql.update("DELETE FROM core_aktywny_tytul WHERE uuid = ? AND tytul = ?", uuid, titleId);
        return sql.update("DELETE FROM core_tytuly WHERE uuid = ? AND tytul = ?", uuid, titleId) > 0;
    }

    public List<Owned> owned(UUID uuid) {
        return sql.query("SELECT tytul, zrodlo, nadano FROM core_tytuly WHERE uuid = ? ORDER BY nadano DESC",
                rs -> new Owned(rs.getString(1), rs.getString(2), rs.getLong(3)), uuid);
    }

    public Optional<String> active(UUID uuid) {
        return sql.one("SELECT a.tytul FROM core_aktywny_tytul a JOIN core_tytuly t ON t.uuid = a.uuid AND t.tytul = a.tytul "
                + "WHERE a.uuid = ?", rs -> rs.getString(1), uuid);
    }

    /** Ustawia aktywny tytuł. Fałsz, gdy gracz go nie posiada. */
    public boolean setActive(UUID uuid, String titleId) {
        boolean owns = sql.one("SELECT 1 FROM core_tytuly WHERE uuid = ? AND tytul = ?", rs -> 1, uuid, titleId).isPresent();
        if (!owns) {
            return false;
        }
        if (sql.update("UPDATE core_aktywny_tytul SET tytul = ? WHERE uuid = ?", titleId, uuid) == 0) {
            sql.update("INSERT INTO core_aktywny_tytul (uuid, tytul) VALUES (?, ?)", uuid, titleId);
        }
        return true;
    }

    public void clearActive(UUID uuid) {
        sql.update("DELETE FROM core_aktywny_tytul WHERE uuid = ?", uuid);
    }
}
