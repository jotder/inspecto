package com.gamma.la.storage;

import com.gamma.la.storage.IndexReader.Edge;
import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-3 step 5 - the sealed per-request reader. The index is generated at test time; nothing is committed. */
class IndexReaderTest {

    private static final String REL = "SELECT * FROM (VALUES "
            + "('A','B','call',TIMESTAMP '2026-01-01 10:00:00',1.5),"
            + "('A','B','call',TIMESTAMP '2026-01-02 10:00:00',2.5),"      // parallel edge
            + "('A','C','sms',CAST(NULL AS TIMESTAMP),CAST(NULL AS DOUBLE)),"
            + "('C','A','call',TIMESTAMP '2026-01-03 10:00:00',4.0),"
            + "('B','B','call',TIMESTAMP '2026-01-04 10:00:00',5.0),"       // self-loop
            + "('D','A','call',TIMESTAMP '2026-01-05 10:00:00',6.0)"
            + ") AS v(s,t,k,ts,w)";

    private static IndexBuilder.Result build(Path root) {
        IndexMapping m = new IndexMapping("s", "t", "k", "ts", null, "w", List.of());
        return IndexBuilder.build(new IndexBuilder.Request("g", m, REL, new IndexStore(root, "g", m.hash()), "fp", null));
    }

    private static IndexReader open(IndexBuilder.Result r) throws Exception {
        return IndexReader.open(r.directory(), r.manifest(), SqlSandboxPolicy.defaultPolicy());
    }

    private static List<String> render(List<Edge> edges) {
        List<String> out = new ArrayList<>();
        for (Edge e : edges) out.add(e.key() + ">" + e.neighbour() + "@" + e.tsMicros() + "/" + e.weight());
        out.sort(null);
        return out;
    }

    @Test
    void outAndInReadsReturnEveryParallelEdgeWithItsTimeAndWeight(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result built = build(tmp);
        try (IndexReader r = open(built)) {
            Map<String, List<Edge>> out = r.edges(List.of("A"), List.of(Side.OUT), null, 100);
            assertEquals(List.of("A>B@1767261600000000/1.5", "A>B@1767348000000000/2.5", "A>C@null/null"), render(out.get("A")));

            Map<String, List<Edge>> in = r.edges(List.of("A"), List.of(Side.IN), null, 100);
            assertEquals(List.of("A>C@1767434400000000/4.0", "A>D@1767607200000000/6.0"), render(in.get("A")), "the in copy: C->A and D->A, seen from A");

            Map<String, List<Edge>> several = r.edges(List.of("B", "D", "nobody"), List.of(Side.OUT), null, 100);
            assertEquals(List.of("B>B@1767520800000000/5.0"), render(several.get("B")), "a self-loop is one edge");
            assertEquals(1, several.get("D").size());
            assertFalse(several.containsKey("nobody"), "a key with no edge is absent");
        }
    }

    @Test
    void thePerKeyLimitAndTheFilterApply(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result built = build(tmp);
        try (IndexReader r = open(built)) {
            assertEquals(1, r.edges(List.of("A"), List.of(Side.OUT), null, 1).get("A").size(), "LIMIT per key");
            Map<String, List<Edge>> sms = r.edges(List.of("A"), List.of(Side.OUT), "kind = 'sms'", 100);
            assertEquals(List.of("A>C@null/null"), render(sms.get("A")));
        }
    }

    @Test
    void theBucketIsComputedInJavaSoAWrongBucketMissesTheRow(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result built = build(tmp);
        try (IndexReader r = open(built)) {
            // probe that WOULD succeed otherwise: the right bucket finds A; the same key under another bucket does not
            int right = BucketFunction.bucketOf("A", built.buckets());
            int wrong = (right + 1) % built.buckets();
            try (Statement st = r.connection().createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM e_out WHERE src = 'A' AND bucket = " + right)) {
                rs.next();
                assertEquals(3, rs.getInt(1));
            }
            try (Statement st = r.connection().createStatement();
                 var rs = st.executeQuery("SELECT count(*) FROM e_out WHERE src = 'A' AND bucket = " + wrong)) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    @Test
    void theConnectionCannotReadOutsideThePinnedVersionDirectory(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result built = build(tmp);
        Path other = tmp.resolve("elsewhere.parquet");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT 1 AS x) TO '" + other.toString().replace('\\', '/') + "' (FORMAT parquet)");
        }
        assertTrue(Files.exists(other), "the probe file exists, so a refusal is the seal and not a missing file");
        try (IndexReader r = open(built)) {
            try (Statement st = r.connection().createStatement()) {
                SQLException e = assertThrows(SQLException.class,
                        () -> st.executeQuery("SELECT * FROM read_parquet('" + other.toString().replace('\\', '/') + "')"));
                String msg = e.getMessage().toLowerCase();
                assertTrue(msg.contains("permission") || msg.contains("allowed") || msg.contains("access"), e.getMessage());
            }
            try (Statement st = r.connection().createStatement()) {
                assertThrows(SQLException.class, () -> st.execute("SET enable_external_access = true"), "the configuration is locked");
            }
            // the positive twin: the index itself is still readable through the same connection
            assertEquals(3, r.edges(List.of("A"), List.of(Side.OUT), null, 10).get("A").size());
        }
    }

    @Test
    void anIndexWithAnotherBucketFunctionOrDeltasIsRefused(@TempDir Path tmp) throws Exception {
        IndexBuilder.Result built = build(tmp);
        IndexManifest m = built.manifest();
        IndexManifest otherFn = new IndexManifest(m.version(), m.builtAt(), m.builder(), m.duckdbVersion(), "hash", m.buckets(), m.rowGroupSize(),
                m.mapping(), m.mappingHash(), m.dataset(), m.relationSqlHash(), m.baseFingerprint(), m.timeColZone(), m.tables(), m.droppedNull(),
                m.deltas(), m.parent());
        assertThrows(IllegalArgumentException.class, () -> IndexReader.open(built.directory(), otherFn, SqlSandboxPolicy.defaultPolicy()));
        IndexManifest withDelta = new IndexManifest(m.version(), m.builtAt(), m.builder(), m.duckdbVersion(), m.bucketFn(), m.buckets(), m.rowGroupSize(),
                m.mapping(), m.mappingHash(), m.dataset(), m.relationSqlHash(), m.baseFingerprint(), m.timeColZone(), m.tables(), m.droppedNull(),
                List.of(new IndexManifest.Delta("d1", 1, 1)), m.parent());
        assertThrows(IllegalArgumentException.class, () -> IndexReader.open(built.directory(), withDelta, SqlSandboxPolicy.defaultPolicy()));
        // the positive twin: the untouched manifest opens
        try (IndexReader ok = IndexReader.open(built.directory(), m, SqlSandboxPolicy.defaultPolicy())) {
            assertTrue(ok.edges(List.of("A"), List.of(Side.OUT), null, 10).containsKey("A"));
        }
    }
}
