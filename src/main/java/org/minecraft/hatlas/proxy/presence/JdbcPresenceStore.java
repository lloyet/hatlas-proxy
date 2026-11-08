package org.minecraft.hatlas.proxy.presence;

import org.minecraft.hatlas.proxy.db.Database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** {@link PresenceStore} on PostgreSQL. Layout agreed with hatlas-webapp (its Decision 7). */
public final class JdbcPresenceStore implements PresenceStore {

    private final Database database;
    private final String upsert;
    private final String delete;
    private final String clear;

    public JdbcPresenceStore(Database database) {
        this.database = database;
        String table = database.schema() + ".presence";
        this.upsert = "INSERT INTO " + table + " (uuid, username, server, since) VALUES (?, ?, ?, now())"
                + " ON CONFLICT (uuid) DO UPDATE SET username = EXCLUDED.username, server = EXCLUDED.server,"
                + " since = EXCLUDED.since";
        this.delete = "DELETE FROM " + table + " WHERE uuid = ?";
        this.clear = "DELETE FROM " + table;
    }

    @Override
    public void upsert(UUID player, String username, String server) throws SQLException {
        database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                ps.setObject(1, player);
                ps.setString(2, username);
                ps.setString(3, server);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void delete(UUID player) throws SQLException {
        database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(delete)) {
                ps.setObject(1, player);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void clear() throws SQLException {
        database.call(c -> {
            try (PreparedStatement ps = c.prepareStatement(clear)) {
                return ps.executeUpdate();
            }
        });
    }
}
