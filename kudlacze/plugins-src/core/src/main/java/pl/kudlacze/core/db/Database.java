package pl.kudlacze.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Pula połączeń do MariaDB (HikariCP) i wersjonowane migracje SQL z zasobów
 * {@code db/migration/V<n>__opis.sql}. Te same migracje działają na H2 w trybie MariaDB (testy).
 */
public final class Database implements AutoCloseable {

    /** Lista migracji w kolejności — każdy plik stosowany dokładnie raz. */
    public static final List<String> MIGRATIONS = List.of(
            "V1__waluta_rangi.sql",
            "V2__sezony_edycje.sql",
            "V3__konkurs_tytuly_parkour.sql"
    );

    private final DataSource dataSource;
    private final Logger logger;

    public Database(DataSource dataSource, Logger logger) {
        this.dataSource = dataSource;
        this.logger = logger;
    }

    public static Database mariadb(String host, int port, String database, String user, String password,
                                   int poolSize, Logger logger) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("kudlacze-core");
        cfg.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=utf8&useServerPrepStmts=true");
        cfg.setDriverClassName("org.mariadb.jdbc.Driver");
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(Math.min(2, poolSize));
        cfg.setMaxLifetime(1_500_000);
        cfg.setKeepaliveTime(300_000);
        cfg.setConnectionTimeout(10_000);
        return new Database(new HikariDataSource(cfg), logger);
    }

    /** Dowolny URL JDBC (testy: H2 w trybie MariaDB). */
    public static Database jdbc(String url, Logger logger) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("kudlacze-core-test");
        cfg.setJdbcUrl(url);
        cfg.setMaximumPoolSize(4);
        return new Database(new HikariDataSource(cfg), logger);
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Stosuje brakujące migracje. Tabela {@code core_migracje} trzyma listę zastosowanych plików. */
    public void migrate() {
        try (Connection c = connection()) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS core_migracje ("
                        + "nazwa VARCHAR(128) NOT NULL PRIMARY KEY, zastosowano BIGINT NOT NULL)");
            }
            List<String> applied = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT nazwa FROM core_migracje")) {
                while (rs.next()) {
                    applied.add(rs.getString(1));
                }
            }
            for (String name : MIGRATIONS) {
                if (applied.contains(name)) {
                    continue;
                }
                String sql = readResource("db/migration/" + name);
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    for (String stmt : splitStatements(sql)) {
                        st.executeUpdate(stmt);
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO core_migracje (nazwa, zastosowano) VALUES (?, ?)")) {
                        ps.setString(1, name);
                        ps.setLong(2, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                    c.commit();
                    logger.info("Zastosowano migrację bazy: " + name);
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Nie udało się przygotować bazy danych: " + e.getMessage(), e);
        }
    }

    static List<String> splitStatements(String sql) {
        String noComments = sql.lines()
                .filter(l -> !l.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        List<String> out = new ArrayList<>();
        for (String part : noComments.split(";")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String readResource(String path) throws IOException {
        InputStream in = Database.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("Brak zasobu " + path);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return r.lines().collect(Collectors.joining("\n"));
        }
    }

    @Override
    public void close() {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // zamykanie przy wyłączaniu serwera
            }
        }
    }
}
