package org.minecraft.hatlas.proxy.access;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.minecraft.hatlas.proxy.config.HatlasConfig;
import org.minecraft.hatlas.proxy.message.Messages;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Paid access on the proxy (the real gate; Hatlas only re-checks at login):
 * <ul>
 *   <li>moving to the faction server needs a valid ticket; without one the player stays where
 *       they are with {@code access-expired}. A failed query refuses the move with
 *       {@code service-unavailable} (fails closed). A player joining the network straight at the
 *       faction server is sent to the hub instead of being disconnected;</li>
 *   <li>players on the faction server are re-checked every {@code access.check-interval-seconds}:
 *       warned at each {@code access.warn-before-minutes} mark, then sent back to the hub
 *       ({@code sent-to-hub}). A failed query here only logs: a database blip must not kick
 *       everyone;</li>
 *   <li>permission {@value #BYPASS} skips both (needs a permission plugin on the proxy).</li>
 * </ul>
 */
public final class TicketGate {

    public static final String BYPASS = "hatlas.proxy.access.bypass";

    private final ProxyServer server;
    private final HatlasConfig config;
    private final Messages messages;
    private final TicketStore tickets;
    private final ExpiryWarnings warnings;
    private final Logger logger;

    public TicketGate(ProxyServer server, HatlasConfig config, Messages messages, TicketStore tickets,
                      Logger logger) {
        this.server = server;
        this.config = config;
        this.messages = messages;
        this.tickets = tickets;
        this.warnings = new ExpiryWarnings(config.access().warnBeforeMinutes());
        this.logger = logger;
    }

    /** Starts the periodic re-check of players on the faction server. */
    public void schedule(Object plugin) {
        Duration every = Duration.ofSeconds(config.access().checkIntervalSeconds());
        server.getScheduler().buildTask(plugin, this::recheck).delay(every).repeat(every).schedule();
    }

    @Subscribe
    public EventTask onPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null || !isFaction(target)) return null;
        Player player = event.getPlayer();
        if (player.hasPermission(BYPASS)) return null;

        boolean joining = player.getCurrentServer().isEmpty();
        return EventTask.resumeWhenComplete(tickets.check(player.getUniqueId()).handle((ticket, error) -> {
            if (error != null) {
                logger.warn("Ticket check failed for {} ({}), move to {} refused: {}", player.getUsername(),
                        player.getUniqueId(), target.getServerInfo().getName(), rootMessage(error));
                refuse(event, player, joining, config.messages().serviceUnavailable());
            } else if (!ticket.valid()) {
                logger.info("{} ({}) has no valid ticket ({}), stays off {}", player.getUsername(),
                        player.getUniqueId(), ticket.status(), target.getServerInfo().getName());
                refuse(event, player, joining, config.messages().accessExpired());
            }
            return null;
        }));
    }

    /** Keeps the player where they are, or sends a joining player to the hub. */
    private void refuse(ServerPreConnectEvent event, Player player, boolean joining, String message) {
        RegisteredServer hub = server.getServer(config.servers().hub()).orElse(null);
        if (joining && hub != null) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(hub));
        } else {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
        }
        player.sendMessage(messages.render(message));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        warnings.forget(event.getPlayer().getUniqueId());
    }

    private void recheck() {
        List<Player> onFaction = server.getAllPlayers().stream()
                .filter(p -> p.getCurrentServer().map(s -> isFaction(s.getServer())).orElse(false))
                .filter(p -> !p.hasPermission(BYPASS))
                .toList();
        if (onFaction.isEmpty()) return;
        tickets.checkAll(onFaction.stream().map(Player::getUniqueId).toList()).whenComplete((all, error) -> {
            if (error != null) {
                logger.warn("Periodic ticket check failed, nobody sent back this time: {}", rootMessage(error));
                return;
            }
            for (Player p : onFaction) apply(p, all.get(p.getUniqueId()));
        });
    }

    private void apply(Player player, Ticket ticket) {
        if (ticket == null || !player.isActive()) return;
        switch (warnings.decide(player.getUniqueId(), ticket)) {
            case ExpiryWarnings.Action.None none -> { }
            case ExpiryWarnings.Action.Warn warn -> player.sendMessage(messages.render(
                    config.messages().accessExpiring(), Map.of("minutes", Long.toString(warn.minutes()))));
            case ExpiryWarnings.Action.SendToHub send -> sendToHub(player);
        }
    }

    private void sendToHub(Player player) {
        logger.info("Ticket of {} ({}) expired: sending back to the hub", player.getUsername(), player.getUniqueId());
        RegisteredServer hub = server.getServer(config.servers().hub()).orElse(null);
        if (hub == null) {
            player.disconnect(messages.render(config.messages().sentToHub()));
            return;
        }
        player.createConnectionRequest(hub).connect().whenComplete((result, error) -> {
            if (error == null && result.isSuccessful()) {
                player.sendMessage(messages.render(config.messages().sentToHub()));
            } else {
                // The hub is down or refused: the player must not stay on the faction server.
                player.disconnect(messages.render(config.messages().sentToHub()));
            }
        });
    }

    private boolean isFaction(RegisteredServer s) {
        return s.getServerInfo().getName().equals(config.servers().faction());
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage();
    }
}
