package org.minecraft.hatlas.proxy.access;

import org.minecraft.hatlas.proxy.db.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Reads {@code <schema>.tickets}, owned and written by the website. This plugin never creates nor
 * writes the table (unlike {@code link_codes}): a missing table is an error, and callers fail
 * closed. Rule: access while {@code max(valid_until) > now()} (database clock).
 */
public final class TicketStore {

    private final Database database;
    private final String one;
    private final String many;

    public TicketStore(Database database) {
        this.database = database;
        String table = database.schema() + ".tickets";
        this.one = "SELECT max(valid_until), now() FROM " + table + " WHERE uuid = ?";
        this.many = "SELECT u.uuid, max(t.valid_until), now() FROM unnest(?::uuid[]) AS u(uuid)"
                + " LEFT JOIN " + table + " t ON t.uuid = u.uuid GROUP BY u.uuid";
    }

    /** The player's ticket, off the proxy's threads. Completes exceptionally on any SQL error. */
    public CompletableFuture<Ticket> check(UUID player) {
        return database.supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(one)) {
                ps.setObject(1, player);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Ticket(instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2)));
                }
            }
        });
    }

    /** Tickets of several players in one query (every requested player is in the result). */
    public CompletableFuture<Map<UUID, Ticket>> checkAll(Collection<UUID> players) {
        if (players.isEmpty()) return CompletableFuture.completedFuture(Map.of());
        return database.supply(c -> {
            Map<UUID, Ticket> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(many)) {
                ps.setArray(1, c.createArrayOf("uuid", players.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getObject(1, UUID.class),
                                new Ticket(instant(rs.getTimestamp(2)), instant(rs.getTimestamp(3))));
                    }
                }
            }
            return out;
        });
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
