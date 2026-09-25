package pl.kudlacze.core.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Drobne pomocniki JDBC — zapytania z parametrami bez powtarzania try-with-resources. */
public final class Sql {

    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    public interface ConnectionWork<T> {
        T run(Connection c) throws SQLException;
    }

    private final Database db;

    public Sql(Database db) {
        this.db = db;
    }

    public Database database() {
        return db;
    }

    public int update(String sql, Object... params) {
        try (Connection c = db.connection()) {
            return update(c, sql, params);
        } catch (SQLException e) {
            throw new DataAccessException(sql, e);
        }
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection c = db.connection()) {
            return query(c, sql, mapper, params);
        } catch (SQLException e) {
            throw new DataAccessException(sql, e);
        }
    }

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        }
    }

    public <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... params) {
        List<T> list = query(sql, mapper, params);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
    }

    /** Praca w jednej transakcji — commit po sukcesie, rollback po wyjątku. */
    public <T> T transaction(ConnectionWork<T> work) {
        try (Connection c = db.connection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("transakcja", e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof java.util.UUID u) {
                ps.setString(i + 1, u.toString());
            } else if (p instanceof Enum<?> e) {
                ps.setString(i + 1, e.name());
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    /** Błąd dostępu do bazy (unchecked, żeby dało się go przepchnąć przez CompletableFuture). */
    public static final class DataAccessException extends RuntimeException {
        public DataAccessException(String sql, SQLException cause) {
            super("Błąd bazy danych (" + sql + "): " + cause.getMessage(), cause);
        }
    }
}
