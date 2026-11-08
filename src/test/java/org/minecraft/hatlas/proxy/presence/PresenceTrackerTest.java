package org.minecraft.hatlas.proxy.presence;

import org.junit.jupiter.api.Test;
import org.minecraft.hatlas.proxy.testutil.RecordingLogger;
import org.slf4j.event.Level;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceTrackerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    /** Records the writes; can be told to fail. */
    private static final class FakeStore implements PresenceStore {
        final List<String> calls = new ArrayList<>();
        boolean failing;

        @Override
        public void upsert(UUID player, String username, String server) throws SQLException {
            record("upsert " + username + " " + server);
        }

        @Override
        public void delete(UUID player) throws SQLException {
            record("delete " + (player.equals(ALICE) ? "Alice" : "Bob"));
        }

        @Override
        public void clear() throws SQLException {
            record("clear");
        }

        private void record(String call) throws SQLException {
            if (failing) throw new SQLException("connection refused");
            calls.add(call);
        }
    }

    /** Runs each task at once on the caller's thread, so tests read results right away. */
    private static final class DirectExecutor extends AbstractExecutorService {
        private boolean shut;

        @Override
        public void execute(Runnable r) {
            if (shut) throw new java.util.concurrent.RejectedExecutionException();
            r.run();
        }

        @Override
        public void shutdown() {
            shut = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shut = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shut;
        }

        @Override
        public boolean isTerminated() {
            return shut;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private final FakeStore store = new FakeStore();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final RecordingLogger log = new RecordingLogger();
    private final PresenceTracker tracker = new PresenceTracker(store, new DirectExecutor(), enabled::get, log);

    @Test
    void joinSwitchAndQuitWriteTheRow() {
        tracker.connected(ALICE, "Alice", "hub");
        tracker.connected(ALICE, "Alice", "faction");
        tracker.disconnected(ALICE);

        assertEquals(List.of("upsert Alice hub", "upsert Alice faction", "delete Alice"), store.calls);
    }

    @Test
    void nothingIsWrittenWhileTheTableIsNotUsable() {
        enabled.set(false);

        tracker.connected(ALICE, "Alice", "hub");
        tracker.disconnected(ALICE);
        tracker.resync(List::of);

        assertTrue(store.calls.isEmpty());
    }

    @Test
    void resyncEmptiesTheTableThenWritesWhoIsOnline() {
        tracker.resync(() -> List.of(
                new PresenceTracker.Online(ALICE, "Alice", "hub"),
                new PresenceTracker.Online(BOB, "Bob", "faction")));

        assertEquals(List.of("clear", "upsert Alice hub", "upsert Bob faction"), store.calls);
    }

    @Test
    void aFailedWriteIsLoggedOnceAndNeverThrown() {
        store.failing = true;

        tracker.connected(ALICE, "Alice", "hub");   // must not throw into the event thread
        tracker.connected(BOB, "Bob", "hub");
        tracker.disconnected(ALICE);

        assertEquals(1, log.at(Level.WARN).size(), "one warning per streak: " + log.at(Level.WARN));
        assertTrue(log.at(Level.WARN).getFirst().contains("upsert Alice"));

        store.failing = false;
        tracker.connected(BOB, "Bob", "faction");
        assertEquals(List.of("upsert Bob faction"), store.calls);
        assertTrue(log.at(Level.INFO).stream().anyMatch(m -> m.contains("work again")));
    }

    @Test
    void cleanShutdownEmptiesTheTableAndDropsLaterEvents() {
        tracker.connected(ALICE, "Alice", "hub");

        tracker.shutdown(Duration.ofSeconds(1));
        tracker.connected(BOB, "Bob", "hub");

        assertEquals(List.of("upsert Alice hub", "clear"), store.calls);
    }

    @Test
    void shutdownLeavesAnUnusableTableAlone() {
        enabled.set(false);

        tracker.shutdown(Duration.ofSeconds(1));

        assertTrue(store.calls.isEmpty());
    }
}
