package org.minecraft.hatlas.proxy.presence;

import java.sql.SQLException;
import java.util.UUID;

/** Writes to {@code <schema>.presence}. Blocking; called only from {@link PresenceTracker}'s writer thread. */
public interface PresenceStore {

    /** Inserts or replaces the player's row, {@code since = now()}. */
    void upsert(UUID player, String username, String server) throws SQLException;

    void delete(UUID player) throws SQLException;

    /** Empties the table (leftovers of a crash; this assumes a single proxy). */
    void clear() throws SQLException;
}
