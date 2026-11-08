package org.minecraft.hatlas.proxy.config;

import java.util.List;

/**
 * Typed view of {@code plugins/hatlas-proxy/config.yml}. Every value an admin may want to change
 * lives here (Open Question 2); nothing tunable is hard-coded elsewhere.
 */
public record HatlasConfig(
        Database database,
        Servers servers,
        Access access,
        Link link,
        Messages messages
) {

    public static final int CURRENT_VERSION = 1;

    /** PostgreSQL connection. The password comes from the environment variable {@code passwordEnv}. */
    public record Database(String host, int port, String name, String user, String passwordEnv,
                           String schema, int poolSize, int connectionTimeoutSeconds,
                           int recheckSeconds) {

        public String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + name;
        }
    }

    /** Backend names as declared in velocity.toml. */
    public record Servers(String hub, String faction) {
    }

    /** Ticket gate and mid-session expiry. */
    public record Access(String renewUrl, List<Integer> warnBeforeMinutes, int checkIntervalSeconds) {
    }

    /** {@code /link} codes. */
    public record Link(String url, int codeLifetimeMinutes, int codeLength, String codeAlphabet,
                       int cooldownSeconds) {
    }

    /** MiniMessage templates. */
    public record Messages(String accessExpired, String accessExpiring, String sentToHub,
                           String linkCode, String linkHubOnly, String linkCooldown,
                           String linkPlayersOnly, String serviceUnavailable) {
    }
}
