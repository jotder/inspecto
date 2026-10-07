package com.gamma.etl;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link PartitionWriter} against a REAL S3-compatible object store — scale-out plan §5.4 bullet 1.
 *
 * <p><b>Gating.</b> Needs an endpoint; with none the class SKIPS, exactly like {@code
 * PostgresStateStoreTest}. Point it at the {@code minio} service in {@code dev-infra/docker-compose.yml}:
 *
 * <pre>{@code
 * INSPECTO_TEST_S3_ENDPOINT=127.0.0.1:9000 \
 * INSPECTO_TEST_S3_KEY=minioadmin INSPECTO_TEST_S3_SECRET=minioadmin \
 * mvn -o -B test -pl inspecto-etl -am \
 *     -Dtest=PartitionWriterObjectStoreTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 *
 * <p>⚠ Create the bucket first — DuckDB will not:
 * {@code docker exec minio mc mb -p local/inspecto-lakehouse}.
 *
 * <p>🔴 <b>A skip is not a pass</b>, and this repo has already had a suite skip for months while hiding a
 * broken classpath. This class was RUN, green, against the live MinIO on 2026-09-14 before being
 * committed; if it has only ever skipped for you, it has told you nothing.
 *
 * <p>⛔ These assertions deliberately pin the ways the object-store lane <em>differs</em> from the local
 * one. They are not a copy of the local expectations with a different path, because the behaviour is not
 * the same — see {@code PartitionWriter.writeToObjectStore}.
 */
class PartitionWriterObjectStoreTest {

    private static final String BUCKET = "inspecto-lakehouse";

    private static String endpoint;
    private static String key;
    private static String secret;

    @BeforeAll
    static void discoverEndpoint() {
        endpoint = env("INSPECTO_TEST_S3_ENDPOINT", "inspecto.test.s3.endpoint");
        key      = env("INSPECTO_TEST_S3_KEY", "inspecto.test.s3.key");
        secret   = env("INSPECTO_TEST_S3_SECRET", "inspecto.test.s3.secret");
    }

    private static String env(String envName, String propName) {
        String v = System.getProperty(propName);
        if (v == null || v.isBlank()) v = System.getenv(envName);
        return v == null || v.isBlank() ? null : v;
    }

    /** A connection already configured for the store — credentials are a SESSION concern, not a config one. */
    private Connection open() throws Exception {
        assumeTrue(endpoint != null && key != null && secret != null,
                "no object store configured — set INSPECTO_TEST_S3_ENDPOINT / _KEY / _SECRET "
                        + "(dev-infra/docker-compose.yml brings up MinIO on 127.0.0.1:9000)");
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = c.createStatement()) {
            s.execute("SET s3_endpoint='" + endpoint + "'");
            s.execute("SET s3_use_ssl=" + endpoint.startsWith("https") );
            s.execute("SET s3_url_style='path'");
            s.execute("SET s3_access_key_id='" + key + "'");
            s.execute("SET s3_secret_access_key='" + secret + "'");
        }
        return c;
    }

    private static String uniqueRoot() {
        return "s3://" + BUCKET + "/t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private static void seed(Statement s) throws Exception {
        s.execute("CREATE TABLE src AS SELECT i AS id, 'p' || (i % 3) AS part, "
                + "'v' || i AS v, 0 AS __src_id FROM range(9) tbl(i)");
    }

    /**
     * The contract every caller depends on: one {@link PartitionOutput} per written object, carrying a
     * partition, a reachable key and a size — produced here by discovery rather than by walking a
     * staging tree, because there is no staging tree.
     */
    @Test
    void aPartitionedWriteReportsOneOutputPerObjectWithSizes() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            seed(s);
            String root = uniqueRoot();

            List<PartitionOutput> outs = PartitionWriter.write(
                    c, "src", root, "PARQUET", "SNAPPY", "batch1", List.of("part"));

            assertEquals(3, outs.size(), "one output per partition: " + outs);
            for (PartitionOutput o : outs) {
                assertTrue(o.partition().startsWith("part="),
                        "the Hive partition segment must survive: " + o);
                assertTrue(o.outputFile().startsWith(root + "/"),
                        "the key must be addressable under the root: " + o);
                assertTrue(o.outputFile().endsWith(".parquet"), "format extension: " + o);
                assertTrue(o.bytes() > 0, "size must be measured, not -1: " + o);
            }
            // Every row must be readable back through the reported keys.
            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM read_parquet('" + root + "/**/*.parquet')")) {
                rs.next();
                assertEquals(9, rs.getLong(1), "all seeded rows must be readable back");
            }
        }
    }

    /**
     * 🔴 The difference that is easiest to assume away: locally a re-run overwrites in place and the file
     * count is unchanged. Here a second write ADDS objects, because there is no rename to overwrite
     * through. ⛔ If this ever starts passing with an unchanged count, the lane has silently grown
     * overwrite semantics and the idempotence claim in the plan needs rewriting — it is not a test to
     * "fix" by relaxing.
     */
    @Test
    void aSecondWriteAccumulatesRatherThanReplacing() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            seed(s);
            String root = uniqueRoot();

            List<PartitionOutput> first  = PartitionWriter.write(
                    c, "src", root, "PARQUET", "SNAPPY", "batch1", List.of("part"));
            List<PartitionOutput> second = PartitionWriter.write(
                    c, "src", root, "PARQUET", "SNAPPY", "batch2", List.of("part"));

            assertEquals(3, first.size());
            assertEquals(3, second.size(), "the second write reports only ITS OWN objects");
            assertTrue(second.stream().noneMatch(o -> first.stream()
                            .anyMatch(f -> f.outputFile().equals(o.outputFile()))),
                    "a differently-named batch must not report the first batch's keys");

            try (ResultSet rs = s.executeQuery(
                    "SELECT count(*) FROM glob('" + root + "/**/*.parquet')")) {
                rs.next();
                assertEquals(6, rs.getLong(1),
                        "both batches' objects remain — an object store has no reveal to overwrite through");
            }
        }
    }

    /**
     * ⚠ The E1 unpartitioned shape must hold here too: no partition segment, one object, named outright
     * rather than discovered.
     */
    @Test
    void anUnpartitionedWriteProducesOneNamedObject() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            seed(s);
            String root = uniqueRoot();

            List<PartitionOutput> outs = PartitionWriter.write(
                    c, "src", root, "PARQUET", "SNAPPY", "solo", List.of());

            assertEquals(1, outs.size(), "one object: " + outs);
            assertEquals("", outs.get(0).partition(), "no partition segment for an E1 write");
            assertEquals(root + "/solo_out.parquet", outs.get(0).outputFile());
            assertTrue(outs.get(0).bytes() > 0, "size must be measured: " + outs);
        }
    }

    /**
     * ⛔ The staging directory must NOT be created on an object-store target. This is the assertion that
     * fails if someone "unifies" the two lanes by reusing the local path: the local lane would leave a
     * {@code .staging} key behind, and on a store with no directories that debris is permanent.
     */
    @Test
    void noStagingObjectsAreLeftBehind() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            seed(s);
            String root = uniqueRoot();
            PartitionWriter.write(c, "src", root, "PARQUET", "SNAPPY", "batch1", List.of("part"));

            try (ResultSet rs = s.executeQuery("SELECT file FROM glob('" + root + "/**')")) {
                while (rs.next()) {
                    String f = rs.getString(1);
                    assertFalse(f.contains("/.staging/"), "no staging debris may survive: " + f);
                    assertFalse(f.endsWith(".tmp"), "no temp object may survive: " + f);
                }
            }
        }
    }
}
