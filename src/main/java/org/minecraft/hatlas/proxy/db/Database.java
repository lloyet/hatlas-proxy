package org.minecraft.hatlas.proxy.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.minecraft.hatlas.proxy.config.HatlasConfig;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PostgreSQL access: a HikariCP pool and an executor for blocking JDBC work, so that no query
 * ever runs on a Velocity event or netty thread (CLAUDE.md §3).
 */
public final class Database implements AutoCloseable {

    private static final String SCHEMA_FILE = "db/web-schema.sql";

    private final HikariDataSource dataSource;
    private final ExecutorService executor;
    private final String schema;

    private Database(HikariDataSource dataSource, String schema) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("hatlas-proxy-db-", 0).factory());
    }

    /**
     * Opens the pool. It does not wait for PostgreSQL: if the database is down at start, queries
     * fail until it comes back (the ticket gate then fails closed, {@link WebTables} retries).
     *
     * @throws DatabaseException when the password variable is unset or the pool cannot be built
     */
    public static Database open(HatlasConfig.Database config) throws DatabaseException {
        String password = System.getenv(config.passwordEnv());
        if (password == null || password.isEmpty()) {
            throw new DatabaseException("environment variable " + config.passwordEnv()
                    + " (database.password-env) is not set", null);
        }

        // The driver is created directly rather than looked up by class name, so relocation
        // inside the shaded jar cannot break it.
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setServerNames(new String[]{config.host()});
        pg.setPortNumbers(new int[]{config.port()});
        pg.setDatabaseName(config.name());
        pg.setUser(config.user());
        pg.setPassword(password);
        pg.setApplicationName("hatlas-proxy");

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("hatlas-proxy");
        hikari.setDataSource(pg);
        hikari.setMaximumPoolSize(config.poolSize());
        hikari.setMinimumIdle(Math.min(1, config.poolSize()));
        hikari.setConnectionTimeout(config.connectionTimeoutSeconds() * 1000L);
        hikari.setInitializationFailTimeout(-1); // start even if PostgreSQL is not up yet
        try {
            return new Database(new HikariDataSource(hikari), config.schema());
        } catch (RuntimeException e) {
            throw new DatabaseException("cannot connect to " + config.jdbcUrl() + " as " + config.user()
                    + ": " + rootMessage(e), e);
        }
    }

    /** The fallback schema file ({@value #SCHEMA_FILE}) from the jar. */
    public static String schemaFile() throws DatabaseException {
        try (InputStream in = Database.class.getClassLoader().getResourceAsStream(SCHEMA_FILE)) {
            if (in == null) {
                throw new DatabaseException(SCHEMA_FILE + " missing from the plugin jar", null);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatabaseException("cannot read " + SCHEMA_FILE + ": " + e.getMessage(), e);
        }
    }

    /** Splits the schema file into statements, comments removed and {@code {schema}} replaced. */
    static java.util.List<String> statements(String sql, String schema) {
        String noComments = sql.lines()
                .map(line -> line.replaceFirst("--.*$", ""))
                .collect(java.util.stream.Collectors.joining("\n"));
        return java.util.Arrays.stream(noComments.replace("{schema}", schema).split(";"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Runs blocking JDBC work off the proxy's threads. */
    public <T> CompletableFuture<T> supply(SqlWork<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call(work);
            } catch (SQLException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    /** Runs JDBC work on the CURRENT thread (only from an executor or at shutdown, never an event thread). */
    public <T> T call(SqlWork<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return work.run(c);
        }
    }

    /** The executor of {@link #supply}, for other blocking work (virtual threads). */
    public java.util.concurrent.Executor executor() {
        return executor;
    }

    /** The schema holding the web tables ({@code database.schema}), a checked plain identifier. */
    public String schema() {
        return schema;
    }

    @Override
    public void close() {
        executor.shutdown();
        dataSource.close();
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    /** JDBC work on a pooled connection. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
