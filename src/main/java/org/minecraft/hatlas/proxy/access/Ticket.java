package org.minecraft.hatlas.proxy.access;

import java.time.Duration;
import java.time.Instant;

/**
 * A player's ticket as read from {@code web.tickets} (contract: {@code ../hatlas/docs/access.md}).
 * {@code validUntil} is the latest {@code valid_until} of the player's rows (null = no row) and
 * {@code now} the database clock at the time of the query: the comparison never uses the proxy's
 * clock, exactly like Hatlas.
 */
public record Ticket(Instant validUntil, Instant now) {

    public enum Status { VALID, EXPIRED, NONE }

    public Status status() {
        if (validUntil == null) return Status.NONE;
        return validUntil.isAfter(now) ? Status.VALID : Status.EXPIRED;
    }

    public boolean valid() {
        return status() == Status.VALID;
    }

    /** Time left (zero or negative when expired, zero when there is no ticket). */
    public Duration remaining() {
        return validUntil == null ? Duration.ZERO : Duration.between(now, validUntil);
    }
}
