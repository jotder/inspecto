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
                  poll:       %1$s/data/inbox/orders
                  database:   %1$s/data/orders/database
                  backup:     %1$s/data/orders/backup
                  temp:       %1$s/data/orders/temp
                  errors:     %1$s/data/orders/errors
                  quarantine: %1$s/data/orders/quarantine
                  status_dir: %1$s/data/orders/status
                processing:
                  file_pattern: "glob:**/*.csv"
                  schema_file: orders_schema.toon
                  csv_settings:
                    delimiter: ","
                """.formatted(d), StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("orders_1.csv"), "ORDER_ID,CUSTOMER\n1001,acme\n1002,globex\n",
                StandardCharsets.UTF_8);
        return cfg;
    }
}
