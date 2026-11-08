package org.minecraft.hatlas.proxy.db;

import org.junit.jupiter.api.Test;
import org.minecraft.hatlas.proxy.db.WebTables.Table;
import org.minecraft.hatlas.proxy.testutil.RecordingLogger;
import org.slf4j.event.Level;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTablesTest {

    private static final String SQL = read();

    /** A database where the test decides what exists, what is granted and whether CREATE works. */
    private static final class FakeProbe implements WebTables.Probe {
        boolean schema = true;
        final Set<String> tables = new HashSet<>();
        final Set<String> granted = new HashSet<>();
        /** SQLSTATE thrown by create, or null for "create works" (then the tables appear). */
        String createFails = WebTables.INSUFFICIENT_PRIVILEGE;
        boolean down;
        final List<List<String>> created = new ArrayList<>();

        @Override
        public boolean schemaExists() throws SQLException {
            failIfDown();
            return schema;
        }

        @Override
        public boolean tableExists(String table) throws SQLException {
            failIfDown();
            return tables.contains(table);
        }

        @Override
        public boolean tableUsable(String table) throws SQLException {
            failIfDown();
            return tables.contains(table) && granted.contains(table);
        }

        @Override
        public void create(List<String> statements) throws SQLException {
            failIfDown();
            if (createFails != null) throw new SQLException("permission denied for schema web", createFails);
            created.add(statements);
            for (String s : statements) {
                if (s.contains("web.link_codes (")) appear("web.link_codes");
                if (s.contains("web.presence (")) appear("web.presence");
                if (s.startsWith("CREATE SCHEMA")) schema = true;
            }
        }

        void appear(String table) {
            tables.add(table);
            granted.add(table);
        }

        private void failIfDown() throws SQLException {
            if (down) throw new SQLException("Connection refused", "08001");
        }
    }

    private final FakeProbe probe = new FakeProbe();
    private final RecordingLogger log = new RecordingLogger();
    private final WebTables tables = new WebTables(probe, "web", SQL, log);

    @Test
    void tablesCreatedByTheWebsiteAreUsedWithoutCreatingAnything() {
        probe.appear("web.link_codes");
        probe.appear("web.presence");
        AtomicInteger presenceOn = new AtomicInteger();
        tables.onReady(Table.PRESENCE, presenceOn::incrementAndGet);

        tables.check();

        assertTrue(tables.isReady(Table.LINK_CODES));
        assertTrue(tables.isReady(Table.PRESENCE));
        assertTrue(tables.allReady());
        assertTrue(probe.created.isEmpty(), "no CREATE when the objects exist (no CREATE right needed)");
        assertEquals(1, presenceOn.get());
        assertTrue(log.at(Level.WARN).isEmpty());
    }

    @Test
    void noCreatePrivilegeDisablesWithOneWarningThenEnablesWhenTheTablesAppear() {
        AtomicInteger presenceOn = new AtomicInteger();
        tables.onReady(Table.PRESENCE, presenceOn::incrementAndGet);

        tables.check();
        tables.check();
        tables.check();

        assertFalse(tables.isReady(Table.LINK_CODES));
        assertFalse(tables.isReady(Table.PRESENCE));
        List<String> warnings = log.at(Level.WARN);
        assertEquals(2, warnings.size(), "one warning per table, not one per check: " + warnings);
        assertTrue(warnings.get(0).contains("/link disabled"), warnings.get(0));
        assertTrue(warnings.get(0).contains("may not create it"), warnings.get(0));
        assertEquals(0, presenceOn.get());

        // The website's migrations run: tables created and granted. No restart.
        probe.appear("web.link_codes");
        probe.appear("web.presence");
        tables.check();

        assertTrue(tables.isReady(Table.LINK_CODES));
        assertTrue(tables.isReady(Table.PRESENCE));
        assertEquals(1, presenceOn.get());
        assertEquals(2, log.at(Level.WARN).size(), "no new warning");

        tables.check();
        assertEquals(1, presenceOn.get(), "enabled once, not on every check");
    }

    @Test
    void aRoleThatMayCreateGetsTheMissingTablesFromTheFallbackOnly() {
        probe.createFails = null;
        probe.appear("web.link_codes"); // the website created this one already

        tables.check();

        assertTrue(tables.allReady());
        assertEquals(1, probe.created.size());
        List<String> ran = probe.created.getFirst();
        assertEquals(1, ran.size(), "only the presence table, schema exists: " + ran);
        assertTrue(ran.getFirst().startsWith("CREATE TABLE IF NOT EXISTS web.presence"));
    }

    @Test
    void theSchemaIsCreatedOnlyWhenItIsMissing() {
        probe.createFails = null;
        probe.schema = false;
        probe.appear("web.presence");

        tables.check();

        List<String> ran = probe.created.getFirst();
        assertTrue(ran.getFirst().startsWith("CREATE SCHEMA IF NOT EXISTS web"), ran.toString());
        assertEquals(3, ran.size(), "schema + link_codes table + its index: " + ran);
        assertTrue(tables.allReady());
    }

    @Test
    void anExistingTableWithoutGrantsStaysOffAndIsNotRecreated() {
        probe.tables.add("web.link_codes"); // exists, but no DML granted to this role
        probe.appear("web.presence");

        tables.check();

        assertFalse(tables.isReady(Table.LINK_CODES));
        assertTrue(tables.isReady(Table.PRESENCE));
        assertTrue(probe.created.isEmpty());
        assertTrue(log.at(Level.WARN).getFirst().contains("lacks SELECT/INSERT/UPDATE/DELETE"));

        probe.granted.add("web.link_codes");
        tables.check();
        assertTrue(tables.isReady(Table.LINK_CODES));
    }

    @Test
    void databaseDownNeverThrowsAndRecovers() {
        probe.down = true;

        tables.check();

        assertFalse(tables.isReady(Table.LINK_CODES));
        assertTrue(log.at(Level.WARN).getFirst().contains("Connection refused"));

        probe.down = false;
        probe.appear("web.link_codes");
        probe.appear("web.presence");
        tables.check();
        assertTrue(tables.allReady());
    }

    @Test
    void fallbackStatementsAreGroupedByTable() {
        Map<String, List<String>> groups = WebTables.groups(SQL, "web");

        assertEquals(List.of("", "link_codes", "presence"), List.copyOf(groups.keySet()));
        assertEquals(2, groups.get("link_codes").size(), "table + index");
        assertEquals(1, groups.get("presence").size());
        assertTrue(groups.get("presence").getFirst().startsWith("CREATE TABLE IF NOT EXISTS web.presence"));
    }

    private static String read() {
        try (InputStream in = WebTablesTest.class.getClassLoader().getResourceAsStream("db/web-schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
