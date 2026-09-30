package com.gamma.enrich;

import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-ENRICH-TRANSFORM-SQL-UNSEALED-1 — an authored {@code transformSql} is a single read-only query over
 * the enrichment's own views, on a sealed connection. Each refusal case was RED before the fix: the
 * transform ran verbatim, as a whole statement, on a connection with DuckDB's defaults.
 */
class EnrichmentTransformSandboxTest {

    private static String fwd(Path p) { return p.toAbsolutePath().toString().replace('\\', '/'); }

    /** A one-partition Stage-1-style input tree under {@code root}. */
    private static void seedInput(Path root) throws Exception {
        File db = DuckDbUtil.tempDbFile("seed_");
        try (Connection c = DuckDbUtil.openConnection(db); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT * FROM (VALUES ('2020','C1')) t(day,id)) TO '" + fwd(root)
                    + "' (FORMAT PARQUET, PARTITION_BY (day), OVERWRITE_OR_IGNORE 1)");
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    private static EnrichmentConfig cfg(Path dir, String transform) {
        return new EnrichmentConfig("SANDBOX",
                new EnrichmentConfig.Input(fwd(dir.resolve("in")), "PARQUET", List.of("day")),
                List.of(),
                new EnrichmentConfig.Output(fwd(dir.resolve("out")), "PARQUET", "snappy", List.of("day")),
                transform);
    }

    private static final List<Map<String, Object>> SAMPLE = List.of(Map.of("id", "c1", "day", "2020"));

    /** Runs the transform through both the run path and the preview path; both must refuse. */
    private static void assertRefusedOnBothPaths(Path dir, String transform) throws Exception {
        seedInput(dir.resolve("in"));
        EnrichmentConfig c = cfg(dir, transform);
        Exception run = assertThrows(Exception.class, () -> EnrichmentEngine.runResult(c, null, List.of()));
        assertTrue(String.valueOf(run.getMessage()).contains("refused"), "run: " + run.getMessage());
        Exception prev = assertThrows(Exception.class, () -> EnrichmentEngine.preview(c, SAMPLE, List.of(), 10));
        assertTrue(String.valueOf(prev.getMessage()).contains("refused"), "preview: " + prev.getMessage());
    }

    @Test
    void readingAHostFileIsRefused(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(Files.createDirectories(dir.resolve("elsewhere")).resolve("secret.txt"),
                "TOP-SECRET");
        assertRefusedOnBothPaths(dir, "SELECT '2020' AS day, content FROM read_text('" + fwd(secret) + "')");
    }

    @Test
    void readingAnotherSpacesDataByReplacementScanIsRefused(@TempDir Path dir) throws Exception {
        Path other = Files.createDirectories(dir.resolve("other-space"));
        Files.writeString(other.resolve("customers.csv"), "day,msisdn\n2020,99999\n");
        assertRefusedOnBothPaths(dir, "SELECT '2020' AS day, msisdn FROM '" + fwd(other.resolve("customers.csv")) + "'");
        assertFalse(Files.exists(dir.resolve("out")), "nothing may be written from a refused transform");
    }

    @Test
    void copyToAPathOutsideTheSpaceIsRefused(@TempDir Path dir) throws Exception {
        Path leak = dir.resolve("elsewhere").resolve("leak.csv");
        Files.createDirectories(leak.getParent());
        assertRefusedOnBothPaths(dir, "SELECT '2020' AS day, 1 AS n; COPY (SELECT 42 AS x) TO '" + fwd(leak) + "'");
        assertFalse(Files.exists(leak), "COPY ... TO wrote outside the Space");
    }

    @Test
    void attachIsRefused(@TempDir Path dir) throws Exception {
        Path other = dir.resolve("elsewhere").resolve("other.db");
        Files.createDirectories(other.getParent());
        assertRefusedOnBothPaths(dir, "SELECT '2020' AS day, 1 AS n; ATTACH '" + fwd(other) + "' AS o");
        assertFalse(Files.exists(other), "ATTACH created a database outside the Space");
    }

    @Test
    void installAndLoadAreRefused(@TempDir Path dir) throws Exception {
        assertRefusedOnBothPaths(dir, "SELECT '2020' AS day, 1 AS n; LOAD parquet");
        assertRefusedOnBothPaths(dir, "SELECT '2020' AS day, 1 AS n; INSTALL httpfs");
    }

    @Test
    void legitimateTransformStillRunsAndPreviews(@TempDir Path dir) throws Exception {
        seedInput(dir.resolve("in"));
        EnrichmentConfig c = cfg(dir, "SELECT day, UPPER(id) AS id_upper FROM input");
        assertEquals(1L, EnrichmentEngine.runResult(c, null, List.of()).totalRows());
        assertEquals(1, EnrichmentEngine.preview(c, SAMPLE, List.of(), 10).rows().size());
    }

    /**
     * The allowlist filter: an enrichment whose {@code output.database} is the Space root, or whose {@code path:}
     * reference is the Pending Change key itself, would hand the sealed connection {@code config.secrets/}. Both
     * are refused before the seal, on the run and the preview path, and the key never reaches a result.
     */
    @Test
    void theSpaceRootOrItsSecretsCanNeverBeAllowlisted(@TempDir Path dir) throws Exception {
        Path space = Files.createDirectories(dir.resolve("space"));
        Files.createDirectories(space.resolve("config"));
        Path key = Files.writeString(Files.createDirectories(space.resolve("config.secrets"))
                .resolve(".pending-changes.key"), "day,k\n2020,HMAC-KEY-BYTES\n");
        seedInput(Files.createDirectories(space.resolve("data")).resolve("in"));
        EnrichmentConfig rootOut = new EnrichmentConfig("LEAK",
                new EnrichmentConfig.Input(fwd(space.resolve("data").resolve("in")), "PARQUET", List.of("day")),
                List.of(), new EnrichmentConfig.Output(fwd(space), "PARQUET", "snappy", List.of("day")),
                "SELECT '2020' AS day, content FROM read_text('" + fwd(key) + "')");
        EnrichmentConfig keyRef = new EnrichmentConfig("LEAK",
                new EnrichmentConfig.Input(fwd(space.resolve("data").resolve("in")), "PARQUET", List.of("day")),
                List.of(new EnrichmentConfig.Reference("k", fwd(key), "CSV")),
                new EnrichmentConfig.Output(fwd(space.resolve("data").resolve("out")), "PARQUET", "snappy", List.of("day")),
                "SELECT day, k FROM k");
        // The same output-at-root config with a transform the guard passes: the FILTER alone must refuse it.
        EnrichmentConfig rootOutPlain = new EnrichmentConfig("LEAK", rootOut.input(), List.of(), rootOut.output(),
                "SELECT day, id FROM input");
        for (EnrichmentConfig c : List.of(rootOut, keyRef, rootOutPlain)) {
            Exception run = assertThrows(Exception.class, () -> EnrichmentEngine.runResult(c, null, List.of()));
            assertTrue(run.getMessage().contains("refused") && !run.getMessage().contains("HMAC-KEY-BYTES"), run.getMessage());
            Exception prev = assertThrows(Exception.class, () -> EnrichmentEngine.preview(c, SAMPLE, List.of(), 10));
            assertTrue(prev.getMessage().contains("refused") && !prev.getMessage().contains("HMAC-KEY-BYTES"), prev.getMessage());
        }
        assertTrue(assertThrows(Exception.class, () -> EnrichmentEngine.runResult(keyRef, null, List.of()))
                .getMessage().contains("secrets"), "the key reference is refused by the filter, not by chance");
        assertTrue(assertThrows(Exception.class, () -> EnrichmentEngine.runResult(rootOutPlain, null, List.of()))
                .getMessage().contains("Space root"), "the root output is refused by the filter");
    }

    /** The same refusal for the spellings PathJail.canonical folds: a trailing dot and case variants. */
    @Test
    void canonicalSpellingsOfConfigAndSecretsAreRefusedAtTheSeal(@TempDir Path dir) throws Exception {
        Path space = Files.createDirectories(dir.resolve("space"));
        Files.createDirectories(space.resolve("config"));
        Path secrets = Files.createDirectories(space.resolve("config.secrets"));
        Files.writeString(secrets.resolve(".pending-changes.key"), "day,k\n2020,HMAC-KEY-BYTES\n");
        Path in = Files.createDirectories(space.resolve("data")).resolve("in");
        seedInput(in);
        List<EnrichmentConfig> bad = new java.util.ArrayList<>();
        for (String out : List.of(fwd(space) + "/config.", fwd(space) + "/config./x", fwd(space) + "/CONFIG"))
            bad.add(new EnrichmentConfig("LEAK", new EnrichmentConfig.Input(fwd(in), "PARQUET", List.of("day")),
                    List.of(), new EnrichmentConfig.Output(out, "PARQUET", "snappy", List.of("day")),
                    "SELECT day, id FROM input"));
        bad.add(new EnrichmentConfig("LEAK", new EnrichmentConfig.Input(fwd(in), "PARQUET", List.of("day")),
                List.of(new EnrichmentConfig.Reference("k", fwd(space) + "/Config.Secrets/.pending-changes.key", "CSV")),
                new EnrichmentConfig.Output(fwd(space.resolve("data").resolve("out")), "PARQUET", "snappy", List.of("day")),
                "SELECT day, k FROM k"));
        for (EnrichmentConfig c : bad) {
            Exception run = assertThrows(Exception.class, () -> EnrichmentEngine.runResult(c, null, List.of()));
            assertTrue(run.getMessage().contains("its sealed connection would be allowed to read")
                    && !run.getMessage().contains("HMAC-KEY-BYTES"), c.output().database() + ": " + run.getMessage());
            Exception prev = assertThrows(Exception.class, () -> EnrichmentEngine.preview(c, SAMPLE, List.of(), 10));
            assertTrue(prev.getMessage().contains("its sealed connection would be allowed to read"), prev.getMessage());
        }
    }

    /** Every shipped Space enrichment config's transform clears the guard (the only one: the demo Space's). */
    @Test
    void shippedSpaceEnrichmentTransformsPassTheGuard() throws Exception {
        EnrichmentConfig shipped = EnrichmentConfig.load(
                Path.of("..", "spaces", "demo", "config", "orders", "orders_daily_enrich.toon").toString());
        assertEquals(shipped.transformSql().strip(), EnrichmentEngine.guardedTransform(shipped));
    }

    /** The seal's allowlist is the enrichment's own dirs — never a shared parent of them. */
    @Test
    void allowlistCoversTheEnrichmentsOwnDirsAndNothingElse(@TempDir Path dir) {
        Path ref = dir.resolve("refs").resolve("dim.csv");
        EnrichmentConfig c = new EnrichmentConfig("SANDBOX",
                new EnrichmentConfig.Input(fwd(dir.resolve("in")), "PARQUET", List.of()),
                List.of(new EnrichmentConfig.Reference("dim", fwd(ref), "CSV")),
                new EnrichmentConfig.Output(fwd(dir.resolve("out")), "PARQUET", "snappy", List.of()),
                "SELECT 1");
        List<Path> dirs = EnrichmentEngine.allowedDirs(c, List.of(), dir.resolve("scratch").resolve("x.db").toFile());
        for (String d : List.of("in", "out", "out_quarantine", "refs", "scratch"))
            assertTrue(dirs.contains(dir.resolve(d).toAbsolutePath().normalize()), d + " missing from " + dirs);
        assertFalse(dirs.contains(dir.toAbsolutePath().normalize()), "the parent must never be allowed wholesale");
    }

    /** The seal is the second layer: a path the guard cannot see (a path: reference) outside the allowlist fails. */
    @Test
    void sealedConnectionRefusesAFileOutsideTheAllowlist(@TempDir Path dir) throws Exception {
        Path outside = Files.writeString(Files.createDirectories(dir.resolve("elsewhere")).resolve("x.csv"), "a\n1\n");
        File db = DuckDbUtil.tempDbFile("seal_", dir.resolve("scratch"));
        try (Connection conn = DuckDbUtil.openConnection(db); Statement st = conn.createStatement()) {
            com.gamma.sql.SqlSandbox.sealAllowing(conn, EnrichmentEngine.allowedDirs(cfg(dir, "SELECT 1"), List.of(), db));
            assertThrows(java.sql.SQLException.class,
                    () -> st.executeQuery("SELECT * FROM read_csv('" + fwd(outside) + "')").close());
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }
}
