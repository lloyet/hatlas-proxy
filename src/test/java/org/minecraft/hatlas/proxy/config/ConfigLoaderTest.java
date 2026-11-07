package org.minecraft.hatlas.proxy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void firstStartCopiesTheDefaultAndLoadsIt() throws Exception {
        HatlasConfig config = ConfigLoader.load(dir);

        assertTrue(Files.exists(dir.resolve("config.yml")));
        assertEquals("jdbc:postgresql://localhost:5432/hatlas", config.database().jdbcUrl());
        assertEquals("HATLAS_DB_PASSWORD", config.database().passwordEnv());
        assertEquals("hub", config.servers().hub());
        assertEquals("faction", config.servers().faction());
        assertEquals(List.of(10, 1), config.access().warnBeforeMinutes());
        assertEquals(10, config.link().codeLifetimeMinutes());
    }

    @Test
    void missingKeysFallBackToDefaults() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                database:
                  name: hatlas_proxy_dev
                access:
                  warn-before-minutes: [1, 5, 5]
                """);

        HatlasConfig config = ConfigLoader.load(dir);

        assertEquals("jdbc:postgresql://localhost:5432/hatlas_proxy_dev", config.database().jdbcUrl());
        assertEquals("web", config.database().schema());
        assertEquals(List.of(5, 1), config.access().warnBeforeMinutes(), "deduplicated, largest first");
        assertEquals("hub", config.servers().hub());
    }

    @Test
    void invalidValuesAreAllReported() throws Exception {
        Files.writeString(dir.resolve("config.yml"), """
                database:
                  port: 99999
                  schema: "Web; DROP"
                servers:
                  hub: same
                  faction: same
                link:
                  code-lifetime-minutes: 30
                access:
                  warn-before-minutes: [0]
                """);

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(dir));

        String message = e.getMessage();
        assertTrue(message.contains("database.port"), message);
        assertTrue(message.contains("database.schema"), message);
        assertTrue(message.contains("servers.hub and servers.faction"), message);
        assertTrue(message.contains("link.code-lifetime-minutes"), message);
        assertTrue(message.contains("access.warn-before-minutes"), message);
    }

    @Test
    void newerConfigVersionIsRefused() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "config-version: 99\n");

        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(dir));

        assertTrue(e.getMessage().contains("config-version 99"), e.getMessage());
    }
}
