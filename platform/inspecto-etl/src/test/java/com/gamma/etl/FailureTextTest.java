package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Never store values (2026-10-03): {@link FailureText} strips the value out of REAL DuckDB error texts generically —
 * it is never told the value — and fingerprints it under a per-Space salt.
 */
class FailureTextTest {

    private static final String PAN = "4111111111111111";

    /** Each statement fails with a DuckDB error that quotes the planted value in its own shape. */
    private static List<String> duckDbErrors(Path dir) throws Exception {
        Path csv = Files.writeString(dir.resolve("bad.csv"), "A,B\n1,2\nSECRETVAL " + PAN + ",3\n");
        String[] sqls = {
                "SELECT CAST('SECRETVAL " + PAN + "' AS INTEGER)",                        // 'string' to INT32
                "SELECT CAST('SECRETVAL " + PAN + "' AS DATE)",                           // date conversion
                "SELECT strptime('SECRETVAL " + PAN + "', '%Y-%m-%d')",                   // "string" in DOUBLE quotes
                "SELECT CAST('SECRETVAL " + PAN + "' AS DECIMAL(10,2))",
                "SELECT * FROM read_csv('" + csv.toString().replace('\\', '/') + "', header=true, delim=',', quote='\"',"
                        + " columns={'A':'INTEGER','B':'INTEGER'}, strict_mode=true)",       // Original Line: …
                "SELECT CAST(x AS INTEGER) FROM (VALUES ('it''s SECRETVAL " + PAN + "')) t(x)", // doubled quote
        };
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            for (String sql : sqls) {
                try (Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
                    while (rs.next()) { /* drain */ }
                    fail("expected a failure: " + sql);
                } catch (SQLException e) {
                    out.add(e.getMessage());
                }
            }
        }
        return out;
    }

    @Test
    void everyDuckDbErrorShapeLosesTheValueAndKeepsItsContext(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(dir, "").toString());
        for (String raw : duckDbErrors(dir)) {
            assertTrue(raw.contains("SECRETVAL"), "the probe would otherwise leak — the raw text quotes it: " + raw);
            String safe = FailureText.scrub(raw, cfg);
            assertFalse(safe.contains("SECRETVAL"), safe);
            assertFalse(safe.contains(PAN), safe);
            assertTrue(safe.contains("fp:"), "the value is replaced by its fingerprint: " + safe);
        }
    }

    @Test
    void theFingerprintIsStablePerSpaceSaltedAndNotTheValue(@TempDir Path dir) throws Exception {
        PipelineConfig a = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("a")), "").toString());
        PipelineConfig b = PipelineConfig.load(PipelineConfigBatchTest.writePipeline(Files.createDirectories(dir.resolve("b")), "").toString());
        String fa = FailureText.fingerprint(a, PAN);
        assertEquals(fa, FailureText.fingerprint(a, PAN), "stable within a Space");
        assertNotEquals(fa, FailureText.fingerprint(b, PAN), "a different Space salts differently");
        assertNotEquals(fa, FailureText.fingerprint(a, PAN + "0"));
        assertTrue(fa.matches("fp:[0-9a-f]{16}"), fa);
        // An unsalted SHA-256 of the value (a dictionary attacker's first guess) is not it.
        String plain = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(PAN.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        assertNotEquals("fp:" + plain, fa);
        Path salt = FailureText.spaceRoot(a).resolve(FailureText.SALT_FILE);
        assertEquals(32, Files.size(salt), "the salt is kept in the Space, 32 random bytes");
    }

    @Test
    void unterminatedAndNullAreSafe() {
        assertNull(FailureText.scrub(null, null));
        String cut = FailureText.scrub("Conversion Error: Could not convert string 'SECRETVAL 41111", null);
        assertFalse(cut.contains("SECRETVAL"), cut);
        assertTrue(cut.startsWith("Conversion Error: Could not convert string "), cut);
        assertEquals("plain context stays", FailureText.scrub("plain context stays", null));
        String binder = "Binder Error: Referenced column \"NO_SUCH_COLUMN\" not found in FROM clause!";
        assertEquals(binder, FailureText.scrub(binder, null), "authored-SQL errors keep their identifiers");
        assertFalse(FailureText.scrub("Binder Error: x\nConversion Error: Could not convert string 'SECRETVAL'", null)
                .contains("SECRETVAL"), "a data-shaped error in the same text is still scrubbed");
        assertEquals("NullPointerException", FailureText.render(new NullPointerException(), null));
    }
}
