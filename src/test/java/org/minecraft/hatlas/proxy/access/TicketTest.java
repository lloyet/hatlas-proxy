package org.minecraft.hatlas.proxy.access;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

    @Test
    void noRowMeansNoTicket() {
        Ticket t = new Ticket(null, NOW);
        assertEquals(Ticket.Status.NONE, t.status());
        assertFalse(t.valid());
        assertEquals(Duration.ZERO, t.remaining());
    }

    @Test
    void futureValidUntilIsValid() {
        Ticket t = new Ticket(NOW.plusSeconds(90), NOW);
        assertTrue(t.valid());
        assertEquals(Duration.ofSeconds(90), t.remaining());
    }

    @Test
    void validUntilEqualToNowIsExpired() {
        // Same rule as Hatlas: access while max(valid_until) > now().
        assertEquals(Ticket.Status.EXPIRED, new Ticket(NOW, NOW).status());
        assertEquals(Ticket.Status.EXPIRED, new Ticket(NOW.minusSeconds(1), NOW).status());
    }
}
