package org.minecraft.hatlas.proxy.access;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, for a player on the faction server, whether to warn about the coming expiry (once per
 * {@code access.warn-before-minutes} value) or to send them back to the hub. Pure logic plus a
 * per-player memory of the warnings already sent; a renewed ticket (new {@code valid_until})
 * starts over.
 */
public final class ExpiryWarnings {

    /** What to do for one player at one check. */
    public sealed interface Action {
        record None() implements Action {}
        /** Warn: about {@code minutes} minutes left (rounded up). */
        record Warn(long minutes) implements Action {}
        record SendToHub() implements Action {}
    }

    private record Sent(Instant validUntil, int smallestWarned) {}

    private final List<Integer> warnMinutes;
    private final Map<UUID, Sent> sent = new ConcurrentHashMap<>();

    /** @param warnMinutes the configured warning marks, in minutes (any order) */
    public ExpiryWarnings(List<Integer> warnMinutes) {
        this.warnMinutes = warnMinutes.stream().filter(m -> m > 0).sorted().toList();
    }

    public Action decide(UUID player, Ticket ticket) {
        if (!ticket.valid()) {
            sent.remove(player);
            return new Action.SendToHub();
        }
        Duration left = ticket.remaining();
        Sent previous = sent.get(player);
        if (previous != null && !previous.validUntil().equals(ticket.validUntil())) {
            sent.remove(player);
            previous = null;
        }
        // The smallest mark already reached; marks above it are covered by the same warning, so
        // joining late gives one warning, not one per mark.
        Optional<Integer> reached = warnMinutes.stream()
                .filter(m -> left.compareTo(Duration.ofMinutes(m)) <= 0)
                .findFirst();
        if (reached.isEmpty()) return new Action.None();
        int mark = reached.get();
        if (previous != null && previous.smallestWarned() <= mark) return new Action.None();
        sent.put(player, new Sent(ticket.validUntil(), mark));
        long minutes = Math.max(1, (left.toSeconds() + 59) / 60);
        return new Action.Warn(minutes);
    }

    public void forget(UUID player) {
        sent.remove(player);
    }
}
