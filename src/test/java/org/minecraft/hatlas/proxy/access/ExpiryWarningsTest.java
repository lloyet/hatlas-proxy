package org.minecraft.hatlas.proxy.access;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ExpiryWarningsTest {

    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final UUID P = UUID.randomUUID();

    /** The same ticket (fixed valid_until) seen when {@code seconds} are left: time moves, not the ticket. */
    private static Ticket left(long seconds) {
        return new Ticket(NOW, NOW.minusSeconds(seconds));
    }

    /** A renewed ticket: a later valid_until. */
    private static Ticket renewedLeft(long seconds) {
        Instant later = NOW.plusSeconds(3600);
        return new Ticket(later, later.minusSeconds(seconds));
    }

    @Test
    void farFromExpiryDoesNothing() {
        var w = new ExpiryWarnings(List.of(10, 1));
        assertInstanceOf(ExpiryWarnings.Action.None.class, w.decide(P, left(3600)));
    }

    @Test
    void warnsOncePerMark() {
        var w = new ExpiryWarnings(List.of(10, 1));
        var first = assertInstanceOf(ExpiryWarnings.Action.Warn.class, w.decide(P, left(9 * 60 + 30)));
        assertEquals(10, first.minutes());
        assertInstanceOf(ExpiryWarnings.Action.None.class, w.decide(P, left(5 * 60)));
        var second = assertInstanceOf(ExpiryWarnings.Action.Warn.class, w.decide(P, left(50)));
        assertEquals(1, second.minutes());
        assertInstanceOf(ExpiryWarnings.Action.None.class, w.decide(P, left(10)));
    }

    @Test
    void joiningLateGivesOneWarningNotOnePerMark() {
        var w = new ExpiryWarnings(List.of(1, 10));
        assertInstanceOf(ExpiryWarnings.Action.Warn.class, w.decide(P, left(30)));
        assertInstanceOf(ExpiryWarnings.Action.None.class, w.decide(P, left(20)));
    }

    @Test
    void expiredOrMissingSendsToHub() {
        var w = new ExpiryWarnings(List.of(1));
        assertInstanceOf(ExpiryWarnings.Action.SendToHub.class, w.decide(P, left(0)));
        assertInstanceOf(ExpiryWarnings.Action.SendToHub.class, w.decide(P, new Ticket(null, NOW)));
    }

    @Test
    void renewedTicketStartsOver() {
        var w = new ExpiryWarnings(List.of(1));
        assertInstanceOf(ExpiryWarnings.Action.Warn.class, w.decide(P, left(30)));
        assertInstanceOf(ExpiryWarnings.Action.None.class, w.decide(P, left(20)));
        // Renewed (a new valid_until), then close to expiry again: warned again.
        assertInstanceOf(ExpiryWarnings.Action.Warn.class, w.decide(P, renewedLeft(40)));
    }
}
