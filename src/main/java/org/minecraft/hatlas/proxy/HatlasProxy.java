package org.minecraft.hatlas.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.minecraft.hatlas.proxy.access.TicketGate;
import org.minecraft.hatlas.proxy.access.TicketStore;
import org.minecraft.hatlas.proxy.config.ConfigException;
import org.minecraft.hatlas.proxy.config.ConfigLoader;
import org.minecraft.hatlas.proxy.config.HatlasConfig;
import org.minecraft.hatlas.proxy.db.Database;
import org.minecraft.hatlas.proxy.db.DatabaseException;
import org.minecraft.hatlas.proxy.link.Cooldown;
import org.minecraft.hatlas.proxy.link.LinkCodeGenerator;
import org.minecraft.hatlas.proxy.link.LinkCodeStore;
import org.minecraft.hatlas.proxy.link.LinkCommand;
import org.minecraft.hatlas.proxy.message.Messages;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * hatlas-proxy: the Velocity plugin in front of the hub and the Hatlas faction server
 * (milestone M2 of ../hatlas). Planned responsibilities:
 * <ul>
 *   <li>{@code /link}: single-use code in {@code web.link_codes} for the SvelteKit site (done);</li>
 *   <li>ticket check when moving hub → faction server ({@code web.tickets}, tied to the UUID) (done);</li>
 *   <li>mid-session ticket expiry: warn, then send back to the hub (done);</li>
 *   <li>presence table (who is online, on which server).</li>
 * </ul>
 * No game data is written to the plugin by the website; writes go through PostgreSQL.
 */
@Plugin(
        id = "hatlas-proxy",
        name = "hatlas.proxy",
        version = "1.0-SNAPSHOT",
        description = "Hub link, ticket gate and presence for the Hatlas network",
        authors = {"lloyet"}
)
public final class HatlasProxy {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private HatlasConfig config;
    private Database database;

    @Inject
    public HatlasProxy(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            config = ConfigLoader.load(dataDirectory);
        } catch (ConfigException e) {
            // Without a valid config no feature can run safely: stay loaded but inactive.
            logger.error("hatlas.proxy disabled: {}", e.getMessage());
            return;
        }
        for (String name : new String[]{config.servers().hub(), config.servers().faction()}) {
            if (server.getServer(name).isEmpty()) {
                logger.warn("config.yml names server '{}', which is not declared in velocity.toml", name);
            }
        }
        try {
            database = Database.open(config.database());
            database.applySchema();
        } catch (DatabaseException e) {
            // Every feature needs the database: stay loaded but inactive.
            logger.error("hatlas.proxy disabled: {}", e.getMessage());
            closeDatabase();
            return;
        }

        Messages messages = new Messages(Map.of(
                "link_url", config.link().url(),
                "renew_url", config.access().renewUrl()));
        LinkCodeStore linkCodes = new LinkCodeStore(database,
                new LinkCodeGenerator(config.link().codeAlphabet(), config.link().codeLength()));
        var commands = server.getCommandManager();
        commands.register(commands.metaBuilder("link").plugin(this).build(),
                new LinkCommand(config, messages, linkCodes,
                        new Cooldown(Duration.ofSeconds(config.link().cooldownSeconds())), logger));

        TicketGate gate = new TicketGate(server, config, messages, new TicketStore(database), logger);
        server.getEventManager().register(this, gate);
        gate.schedule(this);

        logger.info("hatlas.proxy enabled (hub '{}', faction '{}', database {}, schema {})",
                config.servers().hub(), config.servers().faction(), config.database().jdbcUrl(),
                config.database().schema());
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    /** The loaded config, or null when loading failed. */
    public HatlasConfig config() {
        return config;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        closeDatabase();
        logger.info("hatlas.proxy disabled");
    }
}
