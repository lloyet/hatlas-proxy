package org.minecraft.hatlas.proxy.link;

import org.minecraft.hatlas.proxy.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Writes {@code /link} codes to {@code <schema>.link_codes}. One live code per player: a new
 * code replaces the previous one. Expiry is computed by PostgreSQL ({@code now()}), the same
 * clock the website compares against.
 */
public final class LinkCodeStore {

    /** Attempts before giving up when a random code is already held by another player. */
    static final int MAX_ATTEMPTS = 5;
    private static final String UNIQUE_VIOLATION = "23505";

    private final Database database;
    private final LinkCodeGenerator generator;
    private final String deleteExpired;
    private final String upsert;

    public LinkCodeStore(Database database, LinkCodeGenerator generator) {
        this.database = database;
        this.generator = generator;
        String table = database.schema() + ".link_codes";
        this.deleteExpired = "DELETE FROM " + table + " WHERE expires_at <= now()";
        this.upsert = "INSERT INTO " + table + " (code, uuid, username, created_at, expires_at)"
                + " VALUES (?, ?, ?, now(), now() + make_interval(mins => ?))"
                + " ON CONFLICT (uuid) DO UPDATE SET code = EXCLUDED.code, username = EXCLUDED.username,"
                + " created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at";
    }

    /** Issues a fresh code for the player, off the proxy's threads. Completes with the code. */
    public CompletableFuture<String> issue(UUID player, String username, int lifetimeMinutes) {
        return database.supply(c -> {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                String code = generator.next();
                if (tryStore(c, player, username, code, lifetimeMinutes)) {
                    return code;
                }
            }
            throw new SQLException("no free link code after " + MAX_ATTEMPTS + " attempts");
        });
    }

    /** One transaction: drop expired codes, then store this one. False if another player holds it. */
    private boolean tryStore(Connection c, UUID player, String username, String code, int minutes)
            throws SQLException {
        c.setAutoCommit(false);
        try (PreparedStatement delete = c.prepareStatement(deleteExpired);
             PreparedStatement insert = c.prepareStatement(upsert)) {
            delete.executeUpdate();
            insert.setString(1, code);
            insert.setObject(2, player);
            insert.setString(3, username);
            insert.setInt(4, minutes);
            insert.executeUpdate();
            c.commit();
            return true;
        } catch (SQLException e) {
            c.rollback();
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }
}
