package org.minecraft.hatlas.proxy.presence;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.List;

/** Feeds {@link PresenceTracker} from Velocity events. Handlers only queue work: they never wait. */
public final class PresenceListener {

    private final ProxyServer server;
    private final PresenceTracker tracker;

    public PresenceListener(ProxyServer server, PresenceTracker tracker) {
        this.server = server;
        this.tracker = tracker;
    }

    /** First join and every server switch. */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player p = event.getPlayer();
        tracker.connected(p.getUniqueId(), p.getUsername(), event.getServer().getServerInfo().getName());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        tracker.disconnected(event.getPlayer().getUniqueId());
    }

    /** Players online now, with the backend they are on (those still connecting are skipped). */
    public List<PresenceTracker.Online> online() {
        return server.getAllPlayers().stream()
                .flatMap(p -> p.getCurrentServer().stream()
                        .map(s -> new PresenceTracker.Online(p.getUniqueId(), p.getUsername(),
                                s.getServerInfo().getName())))
                .toList();
    }
}
