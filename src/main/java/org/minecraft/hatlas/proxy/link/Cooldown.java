package org.minecraft.hatlas.proxy.link;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player wait between two uses ({@code link.cooldown-seconds}). A slot is taken atomically
 * before the database call, so spamming the command cannot issue two codes at once, and given
 * back if the call fails.
 */
public final class Cooldown {

    private final Duration length;
    private final Clock clock;
    private final Map<UUID, Long> until = new ConcurrentHashMap<>();

    public Cooldown(Duration length) {
        this(length, Clock.systemUTC());
    }

    Cooldown(Duration length, Clock clock) {
        this.length = length;
        this.clock = clock;
    }

    /**
     * Takes the player's slot.
     *
     * @return 0 when taken, else the whole seconds (rounded up) left to wait
     */
    public long tryAcquire(UUID player) {
        long now = clock.millis();
        until.values().removeIf(end -> end <= now);
        long[] left = {0};
        until.compute(player, (id, end) -> {
            if (end != null && end > now) {
                left[0] = (end - now + 999) / 1000;
                return end;
            }
            return now + length.toMillis();
        });
        return left[0];
    }

    /** Gives the slot back (the action failed, so the player may retry at once). */
    public void release(UUID player) {
        until.remove(player);
    }
}
