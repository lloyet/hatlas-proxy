package org.minecraft.hatlas.proxy.db;

import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Availability of the {@code web} tables this plugin writes ({@code link_codes}, {@code presence}).
 * <p>
 * The website's migrations create them and grant the proxy's role access (Open Question 8). The
 * proxy may start first, or run as a role without {@code CREATE}: {@link #check()} then finds a
 * table missing or not granted, logs <b>one</b> warning, keeps the feature off, and the next check
 * (every {@code database.recheck-seconds}) turns it on as soon as the table is usable, without a
 * restart. When a table is missing it first tries the fallback statements of {@code web-schema.sql}
 * for that object only; {@code 42501 insufficient_privilege} is expected in production and only
 * logged. Nothing here throws: a database error just means "not ready yet".
 */
public final class WebTables {

    /** A table the plugin writes, and the feature it gates. */
    public enum Table {
        LINK_CODES("link_codes", "/link"),
        PRESENCE("presence", "presence");

        final String table;
        final String feature;

        Table(String table, String feature) {
            this.table = table;
            this.feature = feature;
        }

        public String tableName() {
            return table;
        }
    }

    /** Database lookups, behind an interface so the state logic is unit-tested without PostgreSQL. */
    public interface Probe {
        boolean schemaExists() throws SQLException;

        boolean tableExists(String table) throws SQLException;

        /** The table exists and the current role may SELECT, INSERT, UPDATE and DELETE it. */
        boolean tableUsable(String table) throws SQLException;

        /** Runs the statements in one transaction. */
        void create(List<String> statements) throws SQLException;
    }

    static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String SCHEMA_GROUP = "";
    private static final Pattern TABLE_REF = Pattern.compile("\\{schema}\\.(\\w+)");

    private final Probe probe;
    private final String schema;
    private final Map<String, List<String>> fallback;
    private final Logger logger;
    /** Read lock-free from event threads (/link, presence events); written only under {@link #checking}. */
    private final Map<Table, java.util.concurrent.atomic.AtomicBoolean> ready = new EnumMap<>(Table.class);
    /** Serialises checks; held during database calls, so never taken on an event thread. */
    private final Object checking = new Object();
    /** Last reason logged per table, so the same problem is warned about once, not every minute. */
    private final Map<Table, String> warned = new EnumMap<>(Table.class);
    private final Map<Table, List<Runnable>> onReady = new EnumMap<>(Table.class);

    /**
     * @param schemaSql the raw {@code web-schema.sql} (with {@code {schema}} placeholders)
     */
    public WebTables(Probe probe, String schema, String schemaSql, Logger logger) {
        this.probe = probe;
        this.schema = schema;
        this.fallback = groups(schemaSql, schema);
        this.logger = logger;
        for (Table t : Table.values()) {
            ready.put(t, new java.util.concurrent.atomic.AtomicBoolean());
            onReady.put(t, new java.util.concurrent.CopyOnWriteArrayList<>());
        }
    }

    /** Runs {@code action} each time {@code table} becomes usable (on the checking thread). */
    public void onReady(Table table, Runnable action) {
        onReady.get(table).add(action);
    }

    /** Lock-free: safe on an event thread. */
    public boolean isReady(Table table) {
        return ready.get(table).get();
    }

    /** Lock-free: safe on an event thread. */
    public boolean allReady() {
        return ready.values().stream().allMatch(java.util.concurrent.atomic.AtomicBoolean::get);
    }

    /**
     * Looks every table that is not ready yet up again, creating it from the fallback if missing.
     * Blocking: call it from a database executor, never an event or netty thread.
     */
    public void check() {
        List<Runnable> toRun = new ArrayList<>();
        synchronized (checking) {
            for (Table t : Table.values()) {
                if (ready.get(t).get()) continue;
                String problem = problem(t);
                if (problem == null) {
                    ready.get(t).set(true);
                    warned.remove(t);
                    logger.info("{}.{} is ready: {} enabled", schema, t.table, t.feature);
                    toRun.addAll(onReady.get(t));
                } else if (!problem.equals(warned.get(t))) {
                    warned.put(t, problem);
                    logger.warn("{} disabled: {}. Checking again every few seconds; it turns on by itself "
                            + "once the website's migrations have created and granted {}.{}.",
                            t.feature, problem, schema, t.table);
                }
            }
        }
        for (Runnable r : toRun) {
            try {
                r.run();
            } catch (RuntimeException e) {
                logger.warn("enabling a feature failed: {}", e.toString());
            }
        }
    }

    /** Null when the table is usable, else why not. */
    private String problem(Table t) {
        String qualified = schema + "." + t.table;
        try {
            if (!probe.tableExists(qualified)) {
                String created = createFromFallback(t);
                if (created != null) return created;
            }
            if (!probe.tableExists(qualified)) {
                return qualified + " does not exist";
            }
            if (!probe.tableUsable(qualified)) {
                return "the database role lacks SELECT/INSERT/UPDATE/DELETE on " + qualified;
            }
            return null;
        } catch (SQLException e) {
            return "cannot look " + qualified + " up (" + e.getMessage() + ")";
        }
    }

    /** Creates the schema (if missing) and the table from the fallback; null on success, else why not. */
    private String createFromFallback(Table t) throws SQLException {
        List<String> statements = new ArrayList<>();
        if (!probe.schemaExists()) statements.addAll(fallback.getOrDefault(SCHEMA_GROUP, List.of()));
        statements.addAll(fallback.getOrDefault(t.table, List.of()));
        if (statements.isEmpty()) return null;
        try {
            probe.create(statements);
            logger.info("created {}.{} from the plugin's fallback schema (the website's migrations normally do it)",
                    schema, t.table);
            return null;
        } catch (SQLException e) {
            if (INSUFFICIENT_PRIVILEGE.equals(e.getSQLState())) {
                return schema + "." + t.table + " does not exist and this role may not create it (normal in "
                        + "production: the website creates it)";
            }
            return "creating " + schema + "." + t.table + " failed (" + e.getMessage() + ")";
        }
    }

    /** The fallback statements grouped by the table they name ({@code ""} = the schema itself). */
    static Map<String, List<String>> groups(String schemaSql, String schema) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String statement : Database.statements(schemaSql, "{schema}")) {
            Matcher m = TABLE_REF.matcher(statement);
            String group = m.find() ? m.group(1) : SCHEMA_GROUP;
            out.computeIfAbsent(group, g -> new ArrayList<>()).add(statement.replace("{schema}", schema));
        }
        return out;
    }
}
