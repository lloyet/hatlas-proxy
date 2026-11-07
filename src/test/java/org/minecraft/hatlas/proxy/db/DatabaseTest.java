package org.minecraft.hatlas.proxy.db;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void schemaFileSplitsIntoRepeatableStatementsForTheConfiguredSchema() throws Exception {
        String sql;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("db/web-schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<String> statements = Database.statements(sql, "web_test");

        assertEquals(3, statements.size(), statements.toString());
        for (String s : statements) {
            assertTrue(s.contains("IF NOT EXISTS"), "must be safe to run on every start: " + s);
            assertFalse(s.contains("{schema}") || s.contains("--"), s);
        }
        assertTrue(statements.get(1).startsWith("CREATE TABLE IF NOT EXISTS web_test.link_codes"));
    }
}
