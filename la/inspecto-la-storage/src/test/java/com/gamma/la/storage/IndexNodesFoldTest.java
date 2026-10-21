package com.gamma.la.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-INDEX-SCALE-MEASURE-1: the node table is folded ONE BUCKET AT A TIME from the written {@code out} and {@code in}
 * ({@code IndexBuilder.copyNodes}), where it used to be one statement over the whole edge relation. The statement it replaced is
 * kept below, verbatim, as {@link #REFERENCE_NODES}: over the same corpus the new build must give the same rows, in the same
 * buckets, in id order, with the same column types - and the same rows again after an APPEND and a COMPACT.
 *
 * <p>Corpora are generated at test time (a D-S5-style skew over few nodes, so ids repeat, parallel and self edges abound, and
 * a share of the kinds, times and endpoints is NULL); a bucket count above the node count leaves buckets with no node, and with
 * few nodes many buckets hold only the source or only the target side of an edge.
 */
class IndexNodesFoldTest {

    private static final String DS = "fold";
    private static final IndexMapping M = new IndexMapping("s", "t", "k", "ts", null, null, List.of());

    /** The pre-change nodes statement, with the edge view named {@code __ref} and the bucket count {@code %2$d}. */
    private static final String REFERENCE_NODES = "COPY (SELECT %1$s AS bucket, id, coalesce(o.oe, 0) AS out_edges, coalesce(i.ie, 0) AS in_edges,"
            + " coalesce(o.ol, 0) AS out_links, coalesce(i.il, 0) AS in_links, least(o.f, i.f) AS first_ts, greatest(o.l, i.l) AS last_ts FROM"
            + " (SELECT src AS id, count(*) AS oe, count(DISTINCT struct_pack(n := dst, k := kind)) AS ol, min(ts) AS f, max(ts) AS l FROM __ref GROUP BY src) o"
            + " FULL JOIN (SELECT dst AS id, count(*) AS ie, count(DISTINCT struct_pack(n := src, k := kind)) AS il, min(ts) AS f, max(ts) AS l FROM __ref GROUP BY dst) i USING (id)"
            + " ORDER BY bucket, id) TO '%3$s' (FORMAT parquet, PARTITION_BY (bucket), ROW_GROUP_SIZE 100000, COMPRESSION zstd)";

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    private static Connection mem() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
        }
        return c;
    }

    /** Rows [from, to) of the generated corpus as one parquet file {@code <name>.parquet}; ids repeat, some endpoints / kinds / times are NULL. */
    static void plantFile(Path dir, String name, long from, long to, long nodes) throws Exception {
        Files.createDirectories(dir);
        try (Connection c = mem(); Statement st = c.createStatement()) {
            st.execute("COPY (SELECT"
                    + " CASE WHEN i % 97 = 5 THEN NULL ELSE 'n' || CAST(floor(" + nodes + " * pow((hash(i * 2) % 1000000) / 1e6, 3)) AS BIGINT) END AS s,"
                    + " CASE WHEN i % 89 = 7 THEN NULL ELSE 'n' || CAST(floor(" + nodes + " * pow((hash(i * 2 + 1) % 1000000) / 1e6, 2)) AS BIGINT) END AS t,"
                    + " CASE WHEN i % 5 = 0 THEN NULL WHEN i % 5 IN (1, 2) THEN 'call' ELSE 'sms' END AS k,"
                    + " CASE WHEN i % 11 = 0 THEN NULL ELSE TIMESTAMP '2026-01-01' + to_seconds(i * 37 % 100000) END AS ts"
                    + " FROM range(" + from + ", " + to + ") r(i)) TO '" + sql(dir.resolve(name)) + "' (FORMAT parquet)");
        }
    }

    private static String relation(Path dir, List<String> files) {
        StringBuilder sb = new StringBuilder("SELECT * FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sql(dir.resolve(files.get(i)))).append('\'');
        return sb.append("])").toString();
    }

    private static List<IndexManifest.InputFile> stamps(Path dir, List<String> files) throws Exception {
        List<IndexManifest.InputFile> out = new ArrayList<>();
        for (String f : files) {
            Path p = dir.resolve(f);
            out.add(new IndexManifest.InputFile(f, Files.size(p), Files.getLastModifiedTime(p).toMillis()));
        }
        return out;
    }

    private static IndexBuilder.Result build(Path idx, Path data, IndexBuilder.Mode mode, List<String> files, Integer buckets) throws Exception {
        return IndexBuilder.build(new IndexBuilder.Request(DS, M, relation(data, files), new IndexStore(idx, DS, M.hash()), "fp-" + files.size(),
                new IndexBuilder.Options(buckets, null, null, null, null), stamps(data, files), mode, added -> relation(data, added), List.of(data)));
    }

    private static List<List<String>> rows(Connection c, String sql) throws Exception {
        List<List<String>> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) row.add(rs.getString(i));
                out.add(row);
            }
        }
        return out;
    }

    private static String nodes(Path versionDir) {
        return "read_parquet('" + sql(versionDir) + "/nodes/*/*.parquet', hive_partitioning = true, filename = true, file_row_number = true)";
    }

    private static Set<Integer> bucketDirs(Path table) throws Exception {
        Set<Integer> out = new TreeSet<>();
        if (!Files.isDirectory(table)) return out;
        try (Stream<Path> s = Files.list(table)) {
            s.forEach(d -> out.add(Integer.parseInt(d.getFileName().toString().substring("bucket=".length()))));
        }
        return out;
    }

    /** Both ways {@code EXCEPT ALL} is empty (multiset equality), the column types match, and the row counts match. */
    private static void assertSameNodes(Connection c, String left, String right, String what) throws Exception {
        String cols = "bucket, id, out_edges, in_edges, out_links, in_links, first_ts, last_ts";
        assertEquals(List.of(), rows(c, "SELECT " + cols + " FROM " + left + " EXCEPT ALL SELECT " + cols + " FROM " + right), what + ": rows only in the first");
        assertEquals(List.of(), rows(c, "SELECT " + cols + " FROM " + right + " EXCEPT ALL SELECT " + cols + " FROM " + left), what + ": rows only in the second");
        assertEquals(rows(c, "SELECT count(*) FROM " + left), rows(c, "SELECT count(*) FROM " + right), what + ": row count");
        assertEquals(rows(c, "SELECT column_name, column_type FROM (DESCRIBE SELECT " + cols + " FROM " + left + ")"),
                rows(c, "SELECT column_name, column_type FROM (DESCRIBE SELECT " + cols + " FROM " + right + ")"), what + ": column types");
    }

    /**
     * edges, nodes, buckets. 1 edge / 64 buckets: nearly every bucket empty; 5 / 64: one-sided buckets; 400 with 1 bucket: one file;
     * 400 with 7 buckets: a bucket count that is not a power of two; 40,000 with 64: many parallel edges per node pair.
     */
    @ParameterizedTest(name = "{0} edges over {1} nodes in {2} buckets")
    @CsvSource({"1, 2, 64", "5, 3, 64", "400, 80, 1", "400, 80, 7", "5000, 1000, 16", "40000, 8000, 64"})
    void theFoldedNodesEqualTheReferenceStatementOverTheSameCorpus(long edges, long nodes, int buckets, @TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        for (int k = 0; k < 3; k++) {   // three files: the relation is a real list, as in production
            long from = edges * k / 3, to = edges * (k + 1) / 3;
            plantFile(data, "part" + k + ".parquet", from, to, nodes);
        }
        List<String> files = List.of("part0.parquet", "part1.parquet", "part2.parquet");
        IndexBuilder.Result r = build(tmp.resolve("idx"), data, IndexBuilder.Mode.FULL, files, buckets);
        assertEquals(buckets, r.buckets());
        Path v = r.directory();

        Path ref = Files.createDirectories(tmp.resolve("ref"));
        try (Connection c = mem(); Statement st = c.createStatement()) {
            st.execute("CREATE TEMP VIEW __ref AS SELECT CAST(s AS VARCHAR) AS src, CAST(t AS VARCHAR) AS dst, CAST(k AS VARCHAR) AS kind,"
                    + " CAST(timezone('UTC', timezone('UTC', CAST(ts AS TIMESTAMP))) AS TIMESTAMP) AS ts FROM (" + relation(data, files) + ") WHERE s IS NOT NULL AND t IS NOT NULL");
            st.execute(String.format(REFERENCE_NODES, BucketFunction.sql("id", buckets), buckets, sql(ref.resolve("nodes"))));

            String fresh = nodes(v), old = "read_parquet('" + sql(ref) + "/nodes/*/*.parquet', hive_partitioning = true, filename = true, file_row_number = true)";
            assertSameNodes(c, fresh, old, "folded vs reference");
            assertTrue(((Number) rs1(c, "SELECT count(*) FROM " + fresh)).longValue() > 0, "the corpus has nodes");

            // placement: every row sits in the bucket the one definition names
            assertEquals(0L, ((Number) rs1(c, "SELECT count(*) FROM " + fresh + " WHERE bucket <> " + BucketFunction.sql("id", buckets))).longValue());
            // order: within a file, ids strictly ascend (the reference's ORDER BY bucket, id)
            assertEquals(0L, ((Number) rs1(c, "SELECT count(*) FROM (SELECT id, lag(id) OVER (PARTITION BY filename ORDER BY file_row_number) AS p FROM " + fresh
                    + ") WHERE p IS NOT NULL AND p >= id")).longValue(), "ids ascend within a file");
            // layout: one non-empty file per bucket that holds a node, no directory for a bucket that holds none
            assertEquals(((Number) rs1(c, "SELECT count(DISTINCT bucket) FROM " + fresh)).longValue(), bucketDirs(v.resolve("nodes")).size());
            assertEquals(((Number) rs1(c, "SELECT count(DISTINCT filename) FROM " + fresh)).longValue(), bucketDirs(v.resolve("nodes")).size(), "one file per bucket");
        }
        // every node's bucket has an out or an in directory, and every out / in directory yields a node bucket
        Set<Integer> union = new TreeSet<>(bucketDirs(v.resolve("out")));
        union.addAll(bucketDirs(v.resolve("in")));
        assertEquals(union, bucketDirs(v.resolve("nodes")));
        if (edges == 5 && buckets == 64)   // this case must exercise a bucket with only one side (the 1-edge corpus is a self-link)
            assertNotEquals(bucketDirs(v.resolve("out")), bucketDirs(v.resolve("in")), "a bucket holds only sources or only targets");
        assertEquals(r.manifest(), IndexBuilder.verify(v));
    }

    private static Object rs1(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }

    @Test
    void anAppendThenACompactFoldToTheNodesOfOneFullBuildOfTheSameFiles(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        long nodes = 300;
        plantFile(data, "f0.parquet", 0, 3000, nodes);
        plantFile(data, "f1.parquet", 3000, 6000, nodes);
        plantFile(data, "f2.parquet", 6000, 6600, nodes);   // the delta
        plantFile(data, "f3.parquet", 6600, 7000, nodes);   // a second delta

        IndexBuilder.Result oneShot = build(tmp.resolve("full"), data, IndexBuilder.Mode.FULL, List.of("f0.parquet", "f1.parquet", "f2.parquet", "f3.parquet"), 16);

        Path idx = tmp.resolve("idx");
        build(idx, data, IndexBuilder.Mode.FULL, List.of("f0.parquet", "f1.parquet"), 16);
        build(idx, data, IndexBuilder.Mode.APPEND, List.of("f0.parquet", "f1.parquet", "f2.parquet"), null);
        IndexBuilder.Result a2 = build(idx, data, IndexBuilder.Mode.APPEND, List.of("f0.parquet", "f1.parquet", "f2.parquet", "f3.parquet"), null);
        assertEquals(2, a2.manifest().deltas().size());
        assertTrue(a2.manifest().tables().get("nodes").rows() > oneShot.manifest().tables().get("nodes").rows(), "per-delta node rows are partial until compaction");
        IndexBuilder.Result compact = build(idx, data, IndexBuilder.Mode.COMPACT, List.of("f0.parquet", "f1.parquet", "f2.parquet", "f3.parquet"), null);
        assertEquals(IndexManifest.Builder.COMPACT, compact.manifest().builder());

        try (Connection c = mem()) {
            assertSameNodes(c, nodes(compact.directory()), nodes(oneShot.directory()), "compacted vs one full build");
        }
        assertEquals(oneShot.manifest().tables().get("nodes").rows(), compact.manifest().tables().get("nodes").rows());
    }
}
