package org.minecraft.hatlas.proxy.link;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CooldownTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private long millis = 1_000_000;

        void advance(Duration d) {
            millis += d.toMillis();
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Test
    void secondUseWaitsUntilTheCooldownEnds() {
        ManualClock clock = new ManualClock();
        Cooldown cooldown = new Cooldown(Duration.ofSeconds(30), clock);

        assertEquals(0, cooldown.tryAcquire(ALICE));
        assertEquals(30, cooldown.tryAcquire(ALICE));
        clock.advance(Duration.ofMillis(10_500));
        assertEquals(20, cooldown.tryAcquire(ALICE), "rounded up");
        clock.advance(Duration.ofSeconds(20));
        assertEquals(0, cooldown.tryAcquire(ALICE));
    }

    @Test
    void playersAreIndependent() {
        Cooldown cooldown = new Cooldown(Duration.ofSeconds(30), new ManualClock());

        assertEquals(0, cooldown.tryAcquire(ALICE));
        assertEquals(0, cooldown.tryAcquire(BOB));
    }

    @Test
    void releaseLetsThePlayerRetryAtOnce() {
        Cooldown cooldown = new Cooldown(Duration.ofSeconds(30), new ManualClock());

        assertEquals(0, cooldown.tryAcquire(ALICE));
        cooldown.release(ALICE);
        assertEquals(0, cooldown.tryAcquire(ALICE));
    }

    @Test
    void zeroSecondsMeansNoWait() {
        Cooldown cooldown = new Cooldown(Duration.ZERO, new ManualClock());

        assertEquals(0, cooldown.tryAcquire(ALICE));
        assertEquals(0, cooldown.tryAcquire(ALICE));
    }
}
