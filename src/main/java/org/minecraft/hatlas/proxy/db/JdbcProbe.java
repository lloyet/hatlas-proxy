package org.minecraft.hatlas.proxy.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** {@link WebTables.Probe} over PostgreSQL catalog functions. Blocking. */
public final class JdbcProbe implements WebTables.Probe {

    private final Database database;

    public JdbcProbe(Database database) {
        this.database = database;
    }

    @Override
    public boolean schemaExists() throws SQLException {
        return bool("SELECT to_regnamespace(?) IS NOT NULL", database.schema());
    }

    @Override
    public boolean tableExists(String table) throws SQLException {
        return bool("SELECT to_regclass(?) IS NOT NULL", table);
    }

    @Override
    public boolean tableUsable(String table) throws SQLException {
        // has_table_privilege with a list is true if ANY is held, so each right is tested on its own.
        return bool("SELECT has_schema_privilege(split_part(?, '.', 1), 'USAGE')"
                + " AND has_table_privilege(to_regclass(?), 'SELECT')"
                + " AND has_table_privilege(to_regclass(?), 'INSERT')"
                + " AND has_table_privilege(to_regclass(?), 'UPDATE')"
                + " AND has_table_privilege(to_regclass(?), 'DELETE')",
                table, table, table, table, table);
    }

    @Override
    public void create(List<String> statements) throws SQLException {
        database.call(c -> {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (String s : statements) st.execute(s);
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
            return null;
        });
    }

    private boolean bool(String sql, String... params) throws SQLException {
        return database.call(c -> query(c, sql, params));
    }

    private static boolean query(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
