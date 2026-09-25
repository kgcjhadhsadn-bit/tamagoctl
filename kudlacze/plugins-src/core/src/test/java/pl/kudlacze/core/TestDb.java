package pl.kudlacze.core;

import pl.kudlacze.core.db.Database;
import pl.kudlacze.core.db.Sql;

import java.util.UUID;
import java.util.logging.Logger;

/** Baza H2 w pamięci w trybie zgodności z MariaDB — te same migracje co na produkcji. */
public final class TestDb {

    private TestDb() {
    }

    public static String url() {
        return "jdbc:h2:mem:kudlacze-" + UUID.randomUUID() + ";MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    }

    public static Sql fresh() {
        Database db = Database.jdbc(url(), Logger.getLogger("test"));
        db.migrate();
        return new Sql(db);
    }
}
