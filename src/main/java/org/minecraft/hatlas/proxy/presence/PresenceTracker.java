package org.minecraft.hatlas.proxy.presence;

import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Keeps {@code web.presence} in line with who is online (the website only reads it).
 * <p>
 * Every write goes to one writer thread, in event order, so a quick join-then-quit can never leave
 * a stale row behind. Nothing here blocks or delays a player: callers only queue work, and a failed
 * write is logged (once per streak of failures) and dropped. While the table is not usable
 * ({@code enabled} false) events are ignored; {@link #resync} rebuilds the table from the players
 * online when it becomes usable, which also empties leftovers of a crash.
 */
public final class PresenceTracker {

    /** A player as presence stores them. */
    public record Online(UUID uuid, String username, String server) {
    }

    private final PresenceStore store;
    private final ExecutorService writer;
    private final BooleanSupplier enabled;
    private final Logger logger;
    /** True while writes fail; only the first failure of a streak is logged. Writer thread only. */
    private boolean failing;

    public PresenceTracker(PresenceStore store, ExecutorService writer, BooleanSupplier enabled, Logger logger) {
        this.store = store;
        this.writer = writer;
        this.enabled = enabled;
        this.logger = logger;
    }

    /** First join or server switch. */
    public void connected(UUID player, String username, String server) {
        submit("upsert " + username, () -> store.upsert(player, username, server));
    }

    /** Quit. */
    public void disconnected(UUID player) {
        submit("delete " + player, () -> store.delete(player));
    }

    /**
     * Empties the table, then writes every player online now. Called when the table becomes usable
     * (at start: drops a crash's leftovers; later: catches up the joins missed while it was off).
     * {@code online} is read on the writer thread, after earlier queued writes.
     */
    public void resync(Supplier<List<Online>> online) {
        submit("resync", () -> {
            store.clear();
            for (Online o : online.get()) store.upsert(o.uuid(), o.username(), o.server());
        });
    }

    /**
     * Clean shutdown: lets queued writes finish (up to {@code wait}), then empties the table.
     * Blocking; called from the proxy shutdown event.
     */
    public void shutdown(Duration wait) {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                logger.warn("presence: queued writes still running at shutdown, dropped");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!enabled.getAsBoolean()) return;
        try {
            store.clear();
        } catch (Exception e) {
            logger.warn("presence: could not empty the table at shutdown ({}); the next start does it", e.getMessage());
        }
    }

    private void submit(String what, Write write) {
        if (!enabled.getAsBoolean()) return;
        try {
            writer.execute(() -> run(what, write));
        } catch (RejectedExecutionException e) {
            // Shutting down: the table is emptied anyway.
        }
    }

    private void run(String what, Write write) {
        try {
            write.run();
            if (failing) {
                failing = false;
                logger.info("presence: writes work again");
            }
        } catch (Exception e) {
            if (!failing) {
                failing = true;
                logger.warn("presence: write failed ({}): {}. Further failures are not logged until one succeeds.",
                        what, e.getMessage());
            }
        }
    }

    @FunctionalInterface
    private interface Write {
        void run() throws Exception;
    }

    /** The single writer thread used in production. */
    public static ExecutorService newWriter() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("hatlas-proxy-presence").factory());
    }
}
