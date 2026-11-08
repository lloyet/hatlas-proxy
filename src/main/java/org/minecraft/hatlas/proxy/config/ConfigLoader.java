package org.minecraft.hatlas.proxy.config;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Loads {@code config.yml} (YAML, Configurate, shipped by the Velocity API). The first start copies
 * the commented default from the jar. Values missing from the admin's file fall back to the
 * default file, so a new key never breaks an older config. Invalid values fail with a
 * {@link ConfigException} that names every problem.
 */
public final class ConfigLoader {

    public static final String FILE_NAME = "config.yml";

    private ConfigLoader() {
    }

    /** Copies the default config into {@code dataDirectory} if missing, then loads it. */
    public static HatlasConfig load(Path dataDirectory) throws ConfigException {
        Path file = dataDirectory.resolve(FILE_NAME);
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(dataDirectory);
                try (InputStream in = defaultStream()) {
                    Files.copy(in, file);
                }
            }
        } catch (IOException e) {
            throw new ConfigException("cannot write the default " + file + ": " + e.getMessage(), e);
        }
        return load(file, defaults());
    }

    /** Loads {@code file}, with {@code defaults} filling absent keys. */
    static HatlasConfig load(Path file, ConfigurationNode defaults) throws ConfigException {
        CommentedConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).nodeStyle(NodeStyle.BLOCK).build().load();
        } catch (ConfigurateException e) {
            throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
        }
        root.mergeFrom(defaults);
        return parse(root);
    }

    /** The default config shipped in the jar. */
    static ConfigurationNode defaults() throws ConfigException {
        try (InputStream in = defaultStream()) {
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return YamlConfigurationLoader.builder()
                    .source(() -> new java.io.BufferedReader(new java.io.StringReader(text)))
                    .build().load();
        } catch (IOException e) {
            throw new ConfigException("cannot read the default config from the jar: " + e.getMessage(), e);
        }
    }

    private static InputStream defaultStream() throws IOException {
        InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(FILE_NAME);
        if (in == null) {
            throw new IOException(FILE_NAME + " missing from the plugin jar");
        }
        return in;
    }

    static HatlasConfig parse(ConfigurationNode root) throws ConfigException {
        List<String> errors = new ArrayList<>();
        Reader r = new Reader(root, errors);

        int version = r.integer("config-version", 1, Integer.MAX_VALUE);
        if (version > HatlasConfig.CURRENT_VERSION) {
            errors.add("config-version " + version + " is newer than this plugin supports ("
                    + HatlasConfig.CURRENT_VERSION + ")");
        }

        var database = new HatlasConfig.Database(
                r.text("database.host"),
                r.integer("database.port", 1, 65535),
                r.text("database.name"),
                r.text("database.user"),
                r.text("database.password-env"),
                r.identifier("database.schema"),
                r.integer("database.pool-size", 1, 50),
                r.integer("database.connection-timeout-seconds", 1, 60),
                r.integer("database.recheck-seconds", 5, 3600));

        var servers = new HatlasConfig.Servers(r.text("servers.hub"), r.text("servers.faction"));
        if (servers.hub() != null && servers.hub().equals(servers.faction())) {
            errors.add("servers.hub and servers.faction must be different servers");
        }

        var access = new HatlasConfig.Access(
                r.text("access.renew-url"),
                r.positiveIntegers("access.warn-before-minutes"),
                r.integer("access.check-interval-seconds", 5, 3600));

        var link = new HatlasConfig.Link(
                r.text("link.url"),
                r.integer("link.code-lifetime-minutes", 5, 10),
                r.integer("link.code-length", 4, 16),
                r.alphabet("link.code-alphabet"),
                r.integer("link.cooldown-seconds", 0, 3600));

        var messages = new HatlasConfig.Messages(
                r.text("messages.access-expired"),
                r.text("messages.access-expiring"),
                r.text("messages.sent-to-hub"),
                r.text("messages.link-code"),
                r.text("messages.link-hub-only"),
                r.text("messages.link-cooldown"),
                r.text("messages.link-players-only"),
                r.text("messages.service-unavailable"));

        if (!errors.isEmpty()) {
            throw new ConfigException("invalid " + FILE_NAME + ":\n  - " + String.join("\n  - ", errors), null);
        }
        return new HatlasConfig(database, servers, access, link, messages);
    }

    /** Reads dotted paths and records every problem instead of stopping at the first one. */
    private record Reader(ConfigurationNode root, List<String> errors) {

        private ConfigurationNode node(String path) {
            return root.node((Object[]) path.split("\\."));
        }

        String text(String path) {
            ConfigurationNode n = node(path);
            String value = n.isMap() || n.isList() ? null : n.getString();
            if (value == null || value.isBlank()) {
                errors.add(path + " is missing or empty");
                return null;
            }
            return value.trim();
        }

        String identifier(String path) {
            String value = text(path);
            if (value != null && !value.matches("[a-z_][a-z0-9_]*")) {
                errors.add(path + " must be a lowercase SQL identifier (got '" + value + "')");
            }
            return value;
        }

        String alphabet(String path) {
            String value = text(path);
            if (value == null) {
                return null;
            }
            if (value.chars().anyMatch(Character::isWhitespace) || value.chars().distinct().count() != value.length()
                    || value.length() < 10) {
                errors.add(path + " must be at least 10 different characters, no spaces (got '" + value + "')");
            }
            return value;
        }

        int integer(String path, int min, int max) {
            ConfigurationNode n = node(path);
            Object raw = n.raw();
            if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()) {
                errors.add(path + " must be a whole number between " + min + " and " + max
                        + (raw == null ? " (missing)" : " (got '" + raw + "')"));
                return min;
            }
            int value = number.intValue();
            if (value < min || value > max) {
                errors.add(path + " must be between " + min + " and " + max + " (got " + value + ")");
            }
            return value;
        }

        List<Integer> positiveIntegers(String path) {
            ConfigurationNode n = node(path);
            try {
                List<Integer> values = n.getList(Integer.class);
                if (values == null || values.isEmpty() || values.stream().anyMatch(v -> Objects.requireNonNull(v) <= 0)) {
                    errors.add(path + " must be a non-empty list of positive whole numbers");
                    return List.of();
                }
                return values.stream().distinct().sorted(java.util.Comparator.reverseOrder()).toList();
            } catch (SerializationException | NullPointerException e) {
                errors.add(path + " must be a list of whole numbers, e.g. [10, 1]");
                return List.of();
            }
        }
    }
}
