package com.gamma.inspector;

import com.gamma.acquire.ConnectionProfile;
import com.gamma.acquire.ConnectionRegistry;
import com.gamma.config.safety.PathJail;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SafetyPolicyTier;
import com.gamma.etl.DuckDbExtension;
import com.gamma.etl.PipelineConfig;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SCALEOUT-OBJSTORE-DATABASE-DIR-1: the ingest lane writes an allow-listed {@code s3://} {@code dirs.database}
 * through the Connection {@code output.connection} names, and refuses every other shape by name. Each refusal
 * differs from the accepted pipeline by ONE key, so a refusal that also fired on the accepted shape fails it.
 *
 * <p>The refusals and the seal need no object store: {@link ConsignmentIngestStrategy#configure} creates the
 * secret and seals without dialling. {@link #aPipelineWritesToTheBucketAndAnExpressionCannotReadOutsideIt}
 * runs the real lane end to end and SKIPS without {@code INSPECTO_TEST_S3_ENDPOINT}/{@code _KEY}/{@code _SECRET}
 * (a MinIO with bucket {@code inspecto-lakehouse}, as {@code PartitionWriterObjectStoreTest}).
 * 🔴 A skip is not a pass.
 */
@SuppressWarnings("unchecked")
class ObjectStoreIngestTest {

    private static final String BUCKET = "inspecto-lakehouse";
    private static final String CONN = "lake_s3";

    private String root;

    @BeforeEach
    void allowListAndConnection() {
        root = "s3://" + BUCKET + "/t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        System.setProperty(PathJail.OBJECT_ROOTS_PROPERTY, root);
        String endpoint = env("INSPECTO_TEST_S3_ENDPOINT", "inspecto.test.s3.endpoint");
        String host = endpoint == null ? "127.0.0.1" : endpoint.replaceFirst("^https?://", "").split(":")[0];
        int port = endpoint == null ? 9 : Integer.parseInt(endpoint.replaceFirst("^https?://", "").split(":")[1]);
        ConnectionRegistry.register(s3(CONN, host, port, null));
    }

    @AfterEach
    void clear() {
        System.clearProperty(PathJail.OBJECT_ROOTS_PROPERTY);
        ConnectionRegistry.remove(CONN);
        ConnectionRegistry.remove("an_sftp");
        ConnectionRegistry.remove("tunnelled");
        ConnectionRegistry.remove("odd_host");
        ConnectionRegistry.remove("no_host");
        ConnectionRegistry.remove("no_secret");
    }

    private static ConnectionProfile s3(String id, String host, int port, ConnectionProfile.Tunnel tunnel) {
        String key = env("INSPECTO_TEST_S3_KEY", "inspecto.test.s3.key");
        String secret = env("INSPECTO_TEST_S3_SECRET", "inspecto.test.s3.secret");
        return new ConnectionProfile(id, "s3", host, port, null, BUCKET, key == null ? "test-key" : key,
                secret == null ? "test-secret" : secret, Map.of("protocol", "http"), tunnel, null);
    }

    // ── configure: every refusal beside its accepted twin ───────────────────────────────

    @Test
    void theAcceptedShapeSealsToItsPrefixAndRedactsTheSecret(@TempDir Path dir) throws Exception {
        assumeHttpfs();
        PipelineConfig cfg = cfg(dir, m -> {});
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "objstore_ok_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of());
            // A sibling prefix in the same bucket, another bucket, and a URL: refused by DuckDB before any dial.
            for (String outside : List.of(root + "X/a.parquet", "s3://" + BUCKET + "/elsewhere/a.parquet",
                    "s3://other-bucket/a.parquet", "http://127.0.0.1:9/a.parquet")) {
                SQLException e = assertThrows(SQLException.class, () -> query(conn,
                        "SELECT * FROM read_parquet('" + outside + "')"), outside);
                assertTrue(e.getMessage().contains("disabled by configuration"), outside + ": " + e.getMessage());
            }
            String secrets = query(conn, "SELECT secret_string FROM duckdb_secrets() WHERE name = '"
                    + ObjectStoreDatabase.STORE_SECRET_NAME + "'");
            assertTrue(secrets.contains("scope=" + root), secrets);
            String secret = env("INSPECTO_TEST_S3_SECRET", "inspecto.test.s3.secret");
            assertFalse(secrets.contains(secret == null ? "test-secret" : secret), "the secret must be redacted");
            assertThrows(SQLException.class, () -> query(conn, "SET allowed_directories=['s3://other-bucket/']"));
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    @Test
    void noOutputConnectionIsRefused(@TempDir Path dir) throws Exception {
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).remove("connection")),
                "output.connection must name");
    }

    @Test
    void anUnregisteredConnectionIsRefused(@TempDir Path dir) throws Exception {
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "nope")),
                "not a Connection registered");
    }

    @Test
    void aNonS3ConnectionIsRefused(@TempDir Path dir) throws Exception {
        ConnectionRegistry.register(new ConnectionProfile("an_sftp", "sftp", "127.0.0.1", 22, null, "/in",
                "u", "p", Map.of(), null, null));
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "an_sftp")),
                "needs an s3 one");
    }

    @Test
    void aTunnelledConnectionIsRefused(@TempDir Path dir) throws Exception {
        ConnectionRegistry.register(s3("tunnelled", "127.0.0.1", 9,
                new ConnectionProfile.Tunnel("bastion", 22, "u", "p")));
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "tunnelled")),
                "tunnel or proxy");
    }

    @Test
    void aHostThatIsNotABareNameIsRefused(@TempDir Path dir) throws Exception {
        // DuckDB dials the authority it parses from ENDPOINT: these would pass an allow-list pattern yet dial
        // another host (verification finding, 2026-11-01).
        for (String host : List.of("evil.test:80/x.example.com", "evil.test/x.example.com",
                "u@evil.test", "evil.test?x.example.com", "evil.test#x", " evil.test")) {
            ConnectionRegistry.register(s3("odd_host", host, 9, null));
            assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "odd_host")),
                    "bare DNS name");
        }
    }

    @Test
    void aNumericHostSpellingIsRefusedAndItsOneCharacterTwinPassesTheHostCheck(@TempDir Path dir) throws Exception {
        // Item (e): libcurl (inet_aton) reads each refused spelling as 127.0.0.1, which no Safety Policy pattern or
        // CIDR compared. Each twin is one character away and is a DNS name or a canonical dotted quad: it passes the
        // host check and stops at the NEXT gate (no secret), which proves the host check alone refused its sibling.
        Map<String, String> refusedToTwin = new LinkedHashMap<>();
        refusedToTwin.put("2130706433", "2130706433x");
        refusedToTwin.put("0177.0.0.1", "177.0.0.1");
        refusedToTwin.put("0x7f000001", "ox7f000001");
        refusedToTwin.put("127.1", "127.l");
        refusedToTwin.put("127.0.0.01", "127.0.0.1");
        refusedToTwin.put("127.0.0.1.", "127.0.0.1");
        refusedToTwin.put("s3.example.test.", "s3.example.test");
        for (var e : refusedToTwin.entrySet()) {
            ConnectionRegistry.register(s3("odd_host", e.getKey(), 9, null));
            assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "odd_host")),
                    "a canonical IPv4 address");
            ConnectionRegistry.register(new ConnectionProfile("odd_host", "s3", e.getValue(), 9, null, BUCKET,
                    "a-key", null, Map.of("protocol", "http"), null, null));
            assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "odd_host")),
                    "no access key and resolvable secret");
        }
        // No accepted twin one character away exists for these: a hex first label, or any ':' (IPv6 / port).
        for (String host : List.of("0x7f.1", "::1", "[::1]", "::ffff:127.0.0.1")) {
            ConnectionRegistry.register(s3("odd_host", host, 9, null));
            assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "odd_host")),
                    "a canonical IPv4 address");
        }
    }

    @Test
    void aConnectionWithNoHostIsRefused(@TempDir Path dir) throws Exception {
        ConnectionRegistry.register(s3("no_host", " ", 9, null));
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "no_host")),
                "has no host");
    }

    @Test
    void aConnectionWithNoSecretIsRefused(@TempDir Path dir) throws Exception {
        ConnectionRegistry.register(new ConnectionProfile("no_secret", "s3", "127.0.0.1", 9, null, BUCKET,
                "a-key", null, Map.of("protocol", "http"), null, null));
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("connection", "no_secret")),
                "no access key and resolvable secret");
    }

    @Test
    void aHostTheSafetyPolicyForbidsIsRefused(@TempDir Path dir) throws Exception {
        // network: false in the run's pinned tier; the accepted shape (same pipeline, no pin) seals fine above.
        SafetyPolicyTier noNetwork = new SafetyPolicyTier(null, false, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        PipelineConfig cfg = cfg(dir, m -> {});
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "objstore_egress_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> SafetyPolicy.runWithPinned(
                    new SafetyPolicy.Pin("default", SafetyPolicy.withRoots(dir), noNetwork), () -> {
                        try {
                            ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of());
                            return null;
                        } catch (SQLException x) {
                            throw new IllegalStateException(x);
                        }
                    }));
            assertTrue(e.getMessage().contains("egress refused"), e.getMessage());
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    @Test
    void aDatabaseOutsideTheAllowListIsRefused(@TempDir Path dir) throws Exception {
        assertRefused(cfg(dir, m -> ((Map<String, Object>) m.get("dirs")).put("database", root + "X/db")),
                "outside the allowed object-store prefixes");
    }

    @Test
    void aSinksListIsRefused(@TempDir Path dir) throws Exception {
        assertRefused(cfg(dir, m -> m.put("sinks", List.of(Map.of("database", root + "/db"),
                Map.of("database", dir.resolve("second").toString())))), "single output: destination");
    }

    @Test
    void aReferencePipelineIsRefused(@TempDir Path dir) throws Exception {
        assertRefused(cfg(dir, m -> {
            m.put("produces", "reference");
            m.put("reference", Map.of("load", "replace", "refresh_seconds", 86400));
        }), "produces: reference");
    }

    // ── DuckLake registration of the bucket output ─────────────────────────────────────────

    private PipelineConfig withLake(Path dir) throws Exception {
        return cfg(dir, m -> ((Map<String, Object>) m.get("output")).put("ducklake",
                Map.of("enabled", true, "catalog_url", dir.resolve("lake.ducklake").toString(),
                        "data_path", dir.resolve("lake").toString(), "table", "t")));
    }

    @Test
    void duckLakeRegistrationIsAcceptedOnTheWriteSide(@TempDir Path dir) throws Exception {
        assumeHttpfs();
        PipelineConfig cfg = withLake(dir);
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "objstore_lake_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of());
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    @Test
    void theRegistrarConnectionGetsThePrefixScopedSecret(@TempDir Path dir) throws Exception {
        assumeHttpfs();
        var setup = ConsignmentIngestor.objectStoreSetup(withLake(dir), List.of(root + "/db/p=1/a.parquet"));
        assertNotNull(setup);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            setup.apply(c);
            String secrets = query(c, "SELECT secret_string FROM duckdb_secrets() WHERE name = '"
                    + ObjectStoreDatabase.STORE_SECRET_NAME + "'");
            assertTrue(secrets.contains("scope=" + root + "/db/"), secrets);
            // DuckDB matches a scope as a string prefix: a sibling prefix must not get the credentials.
            assertEquals(ObjectStoreDatabase.STORE_SECRET_NAME + "\n",
                    query(c, "SELECT name FROM which_secret('" + root + "/db/a.parquet', 's3')"));
            assertEquals("", query(c, "SELECT name FROM which_secret('" + root + "/dbX/a.parquet', 's3')"));
        }
    }

    @Test
    void noRegistrarSetupWithoutAnEnabledLake(@TempDir Path dir) throws Exception {
        assertNull(ConsignmentIngestor.objectStoreSetup(cfg(dir, m -> {}), List.of(root + "/db/a.parquet")));
    }

    /** ⛔ The registrar's connection is unsealed: a file outside the prefix is refused before it is read. */
    @Test
    void aWrittenFileOutsideThePrefixIsNotRegistered(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = withLake(dir);
        assertNotNull(ConsignmentIngestor.objectStoreSetup(cfg, List.of(root + "/db/a.parquet")), "accepted twin");
        // Operator 2026-11-02: a partition value outside the containment rule's key character set still registers.
        assertNotNull(ConsignmentIngestor.objectStoreSetup(cfg, List.of(
                root + "/db/ts=2026-11-01%2010%3A00%3A00/a.parquet", root + "/db/city=S%C3%A3o%20Paulo/a.parquet")), "timestamp twin");
        for (String outside : List.of(root + "X/a.parquet", root + "/dbX/a.parquet", root + "/dbXY/a.parquet", root + "/db/../../elsewhere/a.parquet",
                root + "/db/./a.parquet", root + "/db//a.parquet",
                root + "/db/a\\..\\..\\elsewhere\\a.parquet", root + "/db/*/a.parquet", root + "/db/[ab]/a.parquet",
                "s3://other-bucket/a.parquet", dir.resolve("a.parquet").toString())) {
            java.io.IOException e = assertThrows(java.io.IOException.class, () -> ConsignmentIngestor.objectStoreSetup(
                    cfg, List.of(root + "/db/a.parquet", outside)), outside);
            assertTrue(e.getMessage().contains("not registering in DuckLake"), outside + ": " + e.getMessage());
        }
    }

    // ── the live lane ──────────────────────────────────────────────────────────

    @Test
    void aPipelineWritesToTheBucketAndAnExpressionCannotReadOutsideIt(@TempDir Path dir) throws Exception {
        String endpoint = env("INSPECTO_TEST_S3_ENDPOINT", "inspecto.test.s3.endpoint");
        assumeTrue(endpoint != null && env("INSPECTO_TEST_S3_KEY", "inspecto.test.s3.key") != null,
                "no object store configured — set INSPECTO_TEST_S3_ENDPOINT / _KEY / _SECRET");
        // Plant an object OUTSIDE the pipeline's prefix, in the same bucket, for the expression to try to read.
        String planted = "s3://" + BUCKET + "/planted-" + UUID.randomUUID().toString().substring(0, 8) + ".csv";
        try (Connection c = store(endpoint); Statement s = c.createStatement()) {
            s.execute("COPY (SELECT 'host-file-marker-7f3a9c' AS k) TO '" + planted + "' (FORMAT CSV)");
        }

        PipelineConfig ok = pipeline(dir.resolve("ok"), "upper(CUSTOMER)");
        CollectorProcessor.run(ok);
        try (Connection c = store(endpoint); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*), max(LEAK) FROM read_parquet('" + root
                     + "/ok/**/*.parquet')")) {
            rs.next();
            assertEquals(2, rs.getLong(1), "both rows landed in the bucket");
            assertEquals("GLOBEX", rs.getString(2));
        }

        PipelineConfig leak = pipeline(dir.resolve("leak"), "(SELECT max(k) FROM read_csv('" + planted + "'))");
        try {
            CollectorProcessor.run(leak);
        } catch (Exception ignored) {
            // a refused batch may fail the run; the assertion is on what landed
        }
        try (Connection c = store(endpoint); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM glob('" + root + "/leak/**')")) {
            rs.next();
            assertEquals(0, rs.getLong(1), "an expression reading outside the prefix must land nothing");
        }
    }

    @Test
    void aPipelineRegistersItsBucketOutputInDuckLake(@TempDir Path dir) throws Exception {
        String endpoint = env("INSPECTO_TEST_S3_ENDPOINT", "inspecto.test.s3.endpoint");
        assumeTrue(endpoint != null && env("INSPECTO_TEST_S3_KEY", "inspecto.test.s3.key") != null,
                "no object store configured — set INSPECTO_TEST_S3_ENDPOINT / _KEY / _SECRET");
        Path lake = dir.resolve("lake");
        String catalog = lake.resolve("cat.ducklake").toString().replace('\\', '/');
        String data = Files.createDirectories(lake.resolve("data")).toString().replace('\\', '/');
        PipelineConfig cfg = pipeline(dir.resolve("reg"), "upper(CUSTOMER)", """
                  ducklake:
                    enabled: true
                    catalog_url: "%s"
                    data_path: "%s"
                    table: orders
                """.formatted(catalog, data));
        com.gamma.etl.EditionFeatures.overrideForTest(java.util.Set.of(com.gamma.etl.EditionFeatures.SINK_DUCKLAKE));
        try {
            CollectorProcessor.run(cfg);
        } finally {
            com.gamma.etl.EditionFeatures.overrideForTest(null);
        }
        // Registration is non-fatal on a single topology, so only the lake's own contents prove it ran.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            DuckDbExtension.ensureLoaded(c, "ducklake", "test");
            s.execute("ATTACH 'ducklake:" + catalog + "' AS lake (DATA_PATH '" + data + "')");
            try (ResultSet rs = s.executeQuery("SELECT count(*), max(LEAK) FROM lake.main.orders")) {
                rs.next();
                assertEquals(2, rs.getLong(1), "both bucket rows are registered in the lake");
                assertEquals("GLOBEX", rs.getString(2));
            }
        }
    }

    // ── fixture ─────────────────────────────────────────────────────────────

    private static void assumeHttpfs() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            assumeTrue(DuckDbExtension.tryLoad(c, "httpfs"), "httpfs is not available on this host");
        }
    }

    private static void assertRefused(PipelineConfig cfg, String why) throws Exception {
        File tempDb = ConsignmentIngestStrategy.openTempDb(cfg, "objstore_no_");
        try (Connection conn = DuckDbUtil.openConnection(tempDb)) {
            SQLException e = assertThrows(SQLException.class,
                    () -> ConsignmentIngestStrategy.configure(conn, cfg, tempDb, List.of()));
            assertTrue(e.getMessage().contains(why), e.getMessage());
        } finally {
            DuckDbUtil.deleteTempDb(tempDb);
        }
    }

    /** The accepted pipeline, then {@code change} — one key away from it. */
    @SuppressWarnings("unchecked")
    private PipelineConfig cfg(Path dir, Consumer<Map<String, Object>> change) throws Exception {
        Files.createDirectories(dir);
        Map<String, Object> dirs = new HashMap<>(Map.of("poll", dir.resolve("in").toString(),
                "database", root + "/db", "temp", dir.resolve("temp").toString()));
        Map<String, Object> output = new HashMap<>(Map.of("format", "PARQUET", "connection", CONN));
        Map<String, Object> m = new LinkedHashMap<>(Map.of("name", "ORDERS", "dirs", dirs, "output", output,
                "processing", Map.of("threads", 1)));
        change.accept(m);
        return PipelineConfig.fromMap(m);
    }

    private static String query(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            StringBuilder out = new StringBuilder();
            while (rs.next()) out.append(rs.getString(1)).append('\n');
            return out.toString();
        }
    }

    /** An unsealed connection with the store's credentials — the test's own view of the bucket. */
    private static Connection store(String endpoint) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            s.execute("CREATE SECRET (TYPE s3, KEY_ID '" + env("INSPECTO_TEST_S3_KEY", "inspecto.test.s3.key")
                    + "', SECRET '" + env("INSPECTO_TEST_S3_SECRET", "inspecto.test.s3.secret")
                    + "', ENDPOINT '" + endpoint.replaceFirst("^https?://", "") + "', URL_STYLE 'path', USE_SSL "
                    + endpoint.startsWith("https") + ")");
        }
        return c;
    }

    private static String env(String envName, String propName) {
        String v = System.getProperty(propName);
        if (v == null || v.isBlank()) v = System.getenv(envName);
        return v == null || v.isBlank() ? null : v;
    }

    /** A CSV pipeline over the bucket ({@code <root>/<dir name>}) whose LEAK column is {@code expression}. */
    private PipelineConfig pipeline(Path dir, String expression) throws Exception {
        return pipeline(dir, expression, "");
    }

    /** As above, with {@code outputExtra} appended under {@code output:}. */
    private PipelineConfig pipeline(Path dir, String expression, String outputExtra) throws Exception {
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
                """.formatted(expression.replace("\"", "\\\"")), StandardCharsets.UTF_8);
        String d = dir.resolve("space").toString().replace('\\', '/');
        Path toon = conf.resolve("orders_pipeline.toon");
        Files.writeString(toon, """
                name: orders
                active: false
                dirs:
                  poll:       %1$s/data/inbox/orders
                  database:   %2$s/%3$s
                  backup:     %1$s/data/orders/backup
                  temp:       %1$s/data/orders/temp
                  errors:     %1$s/data/orders/errors
                  quarantine: %1$s/data/orders/quarantine
                  status_dir: %1$s/data/orders/status
                output:
                  connection: %4$s
                %5$sprocessing:
                  file_pattern: "glob:**/*.csv"
                  schema_file: orders_schema.toon
                  csv_settings:
                    delimiter: ","
                """.formatted(d, root, dir.getFileName(), CONN, outputExtra), StandardCharsets.UTF_8);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Files.writeString(inbox.resolve("orders_1.csv"), "ORDER_ID,CUSTOMER\n1001,acme\n1002,globex\n",
                StandardCharsets.UTF_8);
        return cfg;
    }
}
