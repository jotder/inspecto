package com.gamma.inspector;

import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SEC-INGEST-EXPR-EXTERNAL-ACCESS-1: a schema mapping's {@code fn: custom} expression is spliced verbatim
 * into ingest SQL, so the ingest DuckDB connection itself must refuse anything outside this Pipeline's own
 * directories. Every case runs the real path — {@link CollectorProcessor#run} → {@code ConsignmentIngestor}.
 */
class IngestExpressionSandboxTest {

    private static final String MARKER = "host-file-marker-7f3a9c";

    @Test
    void readTextOfAHostFileIsRefusedAndNothingLands(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(Files.createDirectories(dir.resolve("host")).resolve("secret.txt"), MARKER);
        PipelineConfig cfg = pipeline(dir, "(SELECT content FROM read_text('" + sql(secret) + "'))");
        runQuietly(cfg);
        assertNothingLanded(cfg);
    }

    @Test
    void readCsvOfAnotherSpacesDataIsRefused(@TempDir Path dir) throws Exception {
        Path other = Files.createDirectories(dir.resolve("other-space/data"));
        Files.writeString(other.resolve("theirs.csv"), "k\n" + MARKER + "\n");
        PipelineConfig cfg = pipeline(dir, "(SELECT max(k) FROM read_csv('" + sql(other.resolve("theirs.csv")) + "'))");
        runQuietly(cfg);
        assertNothingLanded(cfg);
    }

    @Test
    void readParquetOfAnotherSpacesDataIsRefused(@TempDir Path dir) throws Exception {
        Path other = Files.createDirectories(dir.resolve("other-space/data"));
        Path pq = other.resolve("theirs.parquet");
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT '" + MARKER + "' AS k) TO '" + sql(pq) + "' (FORMAT PARQUET)");
        }
        PipelineConfig cfg = pipeline(dir, "(SELECT max(k) FROM read_parquet('" + sql(pq) + "'))");
        runQuietly(cfg);
        assertNothingLanded(cfg);
    }

    @Test
    void anHttpUrlIsRefusedOnTheIngestConnection(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, "upper(CUSTOMER)");
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "sec_http_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of());
            SQLException e = assertThrows(SQLException.class, () -> {
                try (Statement st = conn.createStatement()) {
                    st.executeQuery("SELECT content FROM read_text('http://127.0.0.1:9/x')");
                }
            });
            assertTrue(e.getMessage().toLowerCase().contains("disabled"),
                    "refused by the sealed configuration, not by a failed fetch: " + e.getMessage());
            // and the seal cannot be undone by the untrusted SQL
            assertThrows(SQLException.class, () -> {
                try (Statement st = conn.createStatement()) { st.execute("SET enable_external_access=true"); }
            });
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    @Test
    void aNormalIngestAndScalarExpressionsStillWork(@TempDir Path dir) throws Exception {
        // the payment-fraud PAN tripwire shape: regexp + error() + a lambda over list_transform
        PipelineConfig cfg = pipeline(dir, "CASE WHEN regexp_matches(CUSTOMER, '^[0-9]{16}$') THEN error('PAN') "
                + "ELSE upper(CUSTOMER) || list_transform([1, 2], x -> x + 1)[1]::VARCHAR END");
        CollectorProcessor.run(cfg);
        Path db = Path.of(cfg.dirs().database());
        assertEquals("2", TypedOutputThroughRecordTransformerTest.scalar(db, "COUNT(*)::VARCHAR"));
        assertEquals("ACME2", TypedOutputThroughRecordTransformerTest.scalar(db,
                "max(LEAK) FILTER (WHERE ORDER_ID = 1001)"));
    }

    // ── the data-home variant (adversarial verification of 68a1896f8) ──────────────────

    /** A Space with its Pending Change key where the real layout keeps it. */
    private static Path plantKey(Path dir) throws Exception {
        Path key = Files.createDirectories(dir.resolve("space/config.secrets")).resolve(".pending-changes.key");
        Files.writeString(key, MARKER);
        return key;
    }

    @Test
    void configureRefusesAnAllowlistThatIsTheSpaceRoot(@TempDir Path dir) throws Exception {
        plantKey(dir);
        PipelineConfig cfg = pipeline(dir, "upper(CUSTOMER)", sql(dir.resolve("space")));
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "sec_root_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            SQLException e = assertThrows(SQLException.class,
                    () -> ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of()));
            assertTrue(e.getMessage().contains("Space root"), e.getMessage());
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    @Test
    void anErrorsDirAtTheSpaceRootCannotReadThePendingChangeKey(@TempDir Path dir) throws Exception {
        Path key = plantKey(dir);
        PipelineConfig cfg = pipeline(dir, "(SELECT content FROM read_text('" + sql(key) + "'))",
                sql(dir.resolve("space")));
        runQuietly(cfg);
        assertNothingLanded(cfg);
    }

    @Test
    void theSealedConnectionRefusesTheKeyGlobAttachAndAnyReconfiguration(@TempDir Path dir) throws Exception {
        Path key = plantKey(dir);
        PipelineConfig cfg = pipeline(dir, "upper(CUSTOMER)");
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "sec_lock_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of());
            String space = sql(dir.resolve("space"));
            for (String q : List.of("SELECT content FROM read_text('" + sql(key) + "')",
                    "SELECT * FROM glob('" + space + "/**')",
                    "ATTACH '" + space + "/stolen.db' AS stolen",
                    // lock-only refusals: nothing but lock_configuration stops these
                    "SET threads=3",
                    "SET memory_limit='3GB'",
                    "SET allowed_directories=['" + space + "/']")) {
                assertThrows(SQLException.class, () -> {
                    try (Statement st = conn.createStatement()) { st.execute(q); }
                }, q);
            }
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    /** Round 3: with no dirs.temp the temp DB lands in java.io.tmpdir — which must never join the allowlist. */
    @Test
    void theAllowlistNeverContainsTheSystemTempDirectory(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, "upper(CUSTOMER)");
        Path toon = Path.of(cfg.dirs().poll()).getParent().getParent().getParent().resolve("config/orders/orders_pipeline.toon");
        Files.writeString(toon, Files.readString(toon).replaceAll("(?m)^\\s*temp:.*\\R", ""));
        PipelineConfig noTemp = PipelineConfig.load(toon.toString());
        assertNull(noTemp.dirs().temp(), "fixture: no dirs.temp");
        File tempDb = ConsignmentIngestStrategy.openTempDb(noTemp, "sec_tmp_");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            ConsignmentIngestStrategy.configure(conn, noTemp, tempDb, List.of());
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT unnest(string_split(current_setting('allowed_directories')::VARCHAR, ','))")) {
                while (rs.next()) {
                    String entry = rs.getString(1).replaceAll("[\\[\\]' ]", "").replace('\\', '/');
                    assertFalse(tmp.toString().replace('\\', '/').equalsIgnoreCase(entry.replaceAll("/$", "")),
                            "java.io.tmpdir itself is on the allowlist: " + entry);
                }
            }
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
        for (Path p : ConsignmentIngestStrategy.ingestAllowedDirs(noTemp, List.of()))
            assertNull(com.gamma.config.safety.PathJail.readAllowlistRefusal(p), p.toString());
        assertNotNull(com.gamma.config.safety.PathJail.readAllowlistRefusal(tmp), "the filter refuses tmpdir");
    }

    /** Round 3, at the run-time layer (the loader does not jail): a config/ dir spelled `config.` or in another
     *  case would have been allowlisted, and read_text of a Pipeline file under it then succeeded. */
    @Test
    void configureRefusesAConfigDirHoweverItIsSpelled(@TempDir Path dir) throws Exception {
        List<String> spellings = new java.util.ArrayList<>(List.of("config", "config.", "config./orders",
                "config..", "config. .", "config/new/child", "config\\orders"));
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) spellings.addAll(List.of("CONFIG", "Config/orders"));
        for (String v : spellings) {
            PipelineConfig cfg = pipeline(dir.resolve(v.replaceAll("[^a-zA-Z]", "_")), "upper(CUSTOMER)",
                    null);
            Path space = Path.of(cfg.dirs().poll()).getParent().getParent().getParent();
            PipelineConfig bad = pipeline(space.getParent(), "upper(CUSTOMER)", sql(space) + "/" + v);
            File tempDb = ConsignmentIngestStrategy.openTempDb(bad, "sec_cfg_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                SQLException e = assertThrows(SQLException.class,
                        () -> ConsignmentIngestStrategy.configure(conn, bad, tempDb, List.of()), v);
                assertTrue(e.getMessage().contains("refusing to ingest"), e.getMessage());
            } finally {
                DuckDbUtil.deleteTempDb(tempDb);
            }
        }
    }

    @Test
    void configureRefusesALongPathPrefixedConfigAndAcceptsAUnicodeLookalike(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = pipeline(dir, "upper(CUSTOMER)");
        Path space = dir.resolve("space");
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            PipelineConfig bad = pipeline(dir.resolve("lp"), "upper(CUSTOMER)",
                    "\\\\?\\" + dir.resolve("space") + "\\config");
            File tempDb = ConsignmentIngestStrategy.openTempDb(bad, "sec_lp_");
            try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
                assertThrows(Exception.class, () -> ConsignmentIngestStrategy.configure(conn, bad, tempDb, List.of()));
            } finally {
                DuckDbUtil.deleteTempDb(tempDb);
            }
        }
        // a Cyrillic lookalike is its own directory: allowed, and it cannot read the real config/ tree
        PipelineConfig look = pipeline(dir.resolve("uc"), "upper(CUSTOMER)",
                sql(dir.resolve("uc/space")) + "/\u0441onfig");
        Path pipelineFile = dir.resolve("uc/space/config/orders/orders_pipeline.toon");
        File tempDb = ConsignmentIngestStrategy.openTempDb(look, "sec_uc_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            ConsignmentIngestStrategy.configure(conn, look, tempDb, List.of());
            assertThrows(SQLException.class, () -> {
                try (Statement st = conn.createStatement()) {
                    st.executeQuery("SELECT content FROM read_text('" + sql(pipelineFile) + "')");
                }
            });
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static void runQuietly(PipelineConfig cfg) {
        try {
            CollectorProcessor.run(cfg);
        } catch (Exception ignored) {
            // a refused batch may fail the run; the assertion is on what landed
        }
    }

    private static void assertNothingLanded(PipelineConfig cfg) throws Exception {
        Path db = Path.of(cfg.dirs().database());
        if (!Files.isDirectory(db)) return;
        try (var files = Files.walk(db)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertFalse(f.toString().endsWith(".parquet"), "a refused expression landed output: " + f);
                assertFalse(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1).contains(MARKER),
                        "the secret reached the store: " + f);
            }
        }
    }

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static PipelineConfig pipeline(Path dir, String expression) throws Exception {
        return pipeline(dir, expression, null);
    }

    private static PipelineConfig pipeline(Path dir, String expression, String errorsDir) throws Exception {
        Path conf = Files.createDirectories(dir.resolve("space/config/orders"));
        Files.writeString(conf.resolve("orders_schema.toon"), """
                raw:
                  name: orders
                  format: CSV
                  fields[2]{name,selector,type}:
                    ORDER_ID,0,BIGINT
                    CUSTOMER,1,VARCHAR
                mapping:
                  fields[3]:
                    - name: ORDER_ID
                      from: ORDER_ID
                      fn: keep
                    - name: CUSTOMER
                      from: CUSTOMER
                      fn: keep
                    - name: LEAK
                      from: ""
                      fn: custom
                      args:
                        expression: "%s"
                """.formatted(expression.replace("\\", "\\\\").replace("\"", "\\\"")), StandardCharsets.UTF_8);
        String d = dir.resolve("space").toString().replace('\\', '/');
        Path toon = conf.resolve("orders_pipeline.toon");
        Files.writeString(toon, """
                name: orders
                active: false
                dirs:
                  poll:       %3$s
                  database:   %1$s/data/orders/database
                  backup:     %1$s/data/orders/backup
                  temp:       %1$s/data/orders/temp
                  errors:     %2$s
                  quarantine: %1$s/data/orders/quarantine
                  status_dir: %1$s/data/orders/status
                processing:
                  file_pattern: "glob:**/*.csv"
                  schema_file: orders_schema.toon
                  csv_settings:
                    delimiter: ","
                """.formatted(d, errorsDir != null ? errorsDir : d + "/data/orders/errors",
                // an errors dir that holds the inbox would hide the batch from discovery
                errorsDir != null ? dir.toString().replace(File.separatorChar, '/') + "/outside-inbox/orders" : d + "/data/inbox/orders"), StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("orders_1.csv"), "ORDER_ID,CUSTOMER\n1001,acme\n1002,globex\n",
                StandardCharsets.UTF_8);
        return cfg;
    }
}
