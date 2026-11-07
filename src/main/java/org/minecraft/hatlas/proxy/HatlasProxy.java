package org.minecraft.hatlas.proxy;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.minecraft.hatlas.proxy.config.ConfigException;
import org.minecraft.hatlas.proxy.config.ConfigLoader;
import org.minecraft.hatlas.proxy.config.HatlasConfig;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * hatlas-proxy: the Velocity plugin in front of the hub and the Hatlas faction server
 * (milestone M2 of ../hatlas). Planned responsibilities:
 * <ul>
 *   <li>{@code /link}: single-use code in {@code web.link_codes} for the SvelteKit site;</li>
 *   <li>ticket check when moving hub → faction server ({@code web.tickets}, tied to the UUID);</li>
 *   <li>mid-session ticket expiry: warn, then send back to the hub;</li>
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
        logger.info("hatlas.proxy enabled (hub '{}', faction '{}', database {})",
                config.servers().hub(), config.servers().faction(), config.database().jdbcUrl());
    }

    /** The loaded config, or null when loading failed. */
    public HatlasConfig config() {
        return config;
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("hatlas.proxy disabled");
    }
}
