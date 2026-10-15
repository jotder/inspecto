package com.gamma.la.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entity profile D-EP4: the link statistics a build records in its manifest ({@code kindEdges}, {@code typeNodes},
 * {@code degree}, {@code timeSpan}) over a known star - one hub {@code H} with {@value #LEAVES} leaves - and how an APPEND
 * and a COMPACT keep them equal to a full rebuild of the same files.
 */
class IndexLinkStatsTest {

    private static final String DS = "star";
    private static final int LEAVES = 100;
    private static final IndexMapping MAPPING = new IndexMapping("s", "t", "k", "ts", null, null, List.of());

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    /**
     * f0.parquet holds the edges to leaves 0..49, f1.parquet to 50..99: {@code H -> Li}, kind {@code call} for even i and
     * {@code sms} for odd i (leaf 99 has a NULL kind), ts = 2026-01-01 00:00 + i hours (leaf 7 has a NULL ts). The hub is in
     * both files, so an append touches it again.
     */
    private static void plant(Path dir) throws Exception {
        Files.createDirectories(dir);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("CREATE TABLE e AS SELECT i, 'H' AS s, 'L' || i AS t,"
                    + " CASE WHEN i = 99 THEN NULL WHEN i % 2 = 0 THEN 'call' ELSE 'sms' END AS k,"
                    + " CASE WHEN i = 7 THEN NULL ELSE TIMESTAMP '2026-01-01 00:00:00' + to_hours(i) END AS ts"
                    + " FROM range(" + LEAVES + ") r(i)");
            for (int f = 0; f < 2; f++)
                st.execute("COPY (SELECT s, t, k, ts FROM e WHERE i >= " + f * 50 + " AND i < " + (f + 1) * 50 + " ORDER BY i) TO '"
                        + sql(dir.resolve("f" + f + ".parquet")) + "' (FORMAT parquet)");
        }
    }

    private static String relation(Path dir, List<String> files) {
        StringBuilder sb = new StringBuilder("SELECT * FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sql(dir.resolve(files.get(i)))).append('\'');
        return sb.append("])").toString();
    }

    private static List<IndexManifest.InputFile> stamps(Path dir, List<String> files) throws Exception {
        List<IndexManifest.InputFile> out = new ArrayList<>();
        for (String f : files) out.add(new IndexManifest.InputFile(f, Files.size(dir.resolve(f)), Files.getLastModifiedTime(dir.resolve(f)).toMillis()));
        return out;
    }

    private static IndexBuilder.Result build(Path root, Path data, IndexMapping m, IndexBuilder.Mode mode, List<String> files,
                                             String srcType, String dstType) throws Exception {
        return IndexBuilder.build(new IndexBuilder.Request(DS, m, relation(data, files), new IndexStore(root, DS, m.hash()),
                "fp-" + files.size(), IndexBuilder.Options.defaults(), stamps(data, files), mode, added -> relation(data, added),
                List.of(data), IndexPlan.DEFAULT_MAX_DELTAS, srcType, dstType));
    }

    private static final List<String> BOTH = List.of("f0.parquet", "f1.parquet");

    @Test
    void aFullBuildRecordsKindCountsDegreeQuantilesOfTheStarAndTheTimeSpan(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        IndexManifest m = build(tmp.resolve("ix"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, null, null).manifest();

        assertEquals(Map.of("call", 50L, "sms", 49L), m.kindEdges(), "the NULL kind of leaf 99 is not counted");
        assertEquals(List.of("call", "sms"), List.copyOf(m.kindEdges().keySet()), "most edges first");
        // 101 nodes: 100 leaves of degree 1 and the hub of degree 100
        assertEquals(new IndexManifest.Degree(1, 1, 1, 100), m.degree());
        assertEquals(new IndexManifest.TimeSpan("2026-01-01T00:00:00Z", "2026-01-05T03:00:00Z"), m.timeSpan(),
                "leaf 0 to leaf 99 (99 hours later); the NULL ts of leaf 7 is skipped");
        assertEquals(Map.of(), m.typeNodes(), "untyped endpoint columns: recorded, and empty");

        IndexManifest onDisk = IndexManifest.read(build(tmp.resolve("ix"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, null, null).directory());
        assertEquals(Map.of("call", 50L, "sms", 49L), onDisk.kindEdges(), "the statistics are written to manifest.json");
        assertEquals(new IndexManifest.Degree(1, 1, 1, 100), onDisk.degree());
    }

    @Test
    void typedEndpointsCountTheirNodesOncePerTypeAndASharedTypeCountsEachNodeOnce(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        assertEquals(Map.of("msisdn", 101L),
                build(tmp.resolve("a"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, "msisdn", "msisdn").manifest().typeNodes());
        assertEquals(Map.of("msisdn", 1L, "imei", 100L),
                build(tmp.resolve("b"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, "msisdn", "imei").manifest().typeNodes(),
                "the hub is only ever a source, the leaves only ever targets");
        assertEquals(Map.of("imei", 100L),
                build(tmp.resolve("c"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, null, "imei").manifest().typeNodes());
    }

    @Test
    void withoutAKindOrTimeColumnTheKindsAreEmptyAndThereIsNoTimeSpan(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        IndexMapping bare = new IndexMapping("s", "t", null, null, null, null, List.of());
        IndexManifest m = build(tmp.resolve("ix"), data, bare, IndexBuilder.Mode.FULL, BOTH, null, null).manifest();
        assertEquals(Map.of(), m.kindEdges());
        assertNull(m.timeSpan());
        assertEquals(new IndexManifest.Degree(1, 1, 1, 100), m.degree());
    }

    @Test
    void anAppendAndACompactionRecordWhatAFullRebuildOfTheSameFilesRecords(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        IndexManifest full = build(tmp.resolve("full"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, "msisdn", "imei").manifest();

        Path root = tmp.resolve("inc");
        IndexManifest first = build(root, data, MAPPING, IndexBuilder.Mode.FULL, List.of("f0.parquet"), "msisdn", "imei").manifest();
        assertEquals(50, first.degree().max(), "the hub has 50 leaves in f0");
        IndexManifest appended = build(root, data, MAPPING, IndexBuilder.Mode.APPEND, BOTH, "msisdn", "imei").manifest();
        assertEquals(IndexManifest.Builder.APPEND, appended.builder());
        // the hub has a node row in the main AND in the delta: its degree is their sum, not either one
        assertStatsEqual(full, appended);
        IndexManifest compacted = build(root, data, MAPPING, IndexBuilder.Mode.COMPACT, BOTH, "msisdn", "imei").manifest();
        assertEquals(IndexManifest.Builder.COMPACT, compacted.builder());
        assertStatsEqual(full, compacted);
    }

    @Test
    void anAppendOverAParentWithoutRecordedKindsRecountsThem(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        Path root = tmp.resolve("ix");
        IndexBuilder.Result first = build(root, data, MAPPING, IndexBuilder.Mode.FULL, List.of("f0.parquet"), null, null);
        IndexManifest old = first.manifest();                             // rewrite it as a manifest written before the statistics
        new IndexManifest(old.version(), old.builtAt(), old.builder(), old.duckdbVersion(), old.bucketFn(), old.buckets(),
                old.rowGroupSize(), old.mapping(), old.mappingHash(), old.dataset(), old.relationSqlHash(), old.baseFingerprint(),
                old.timeColZone(), old.tables(), old.droppedNull(), old.deltas(), old.parent(), old.inputFiles(), old.bucketSizing())
                .write(first.directory());
        assertNull(IndexManifest.read(first.directory()).kindEdges());

        IndexManifest appended = build(root, data, MAPPING, IndexBuilder.Mode.APPEND, BOTH, null, null).manifest();
        assertEquals(Map.of("call", 50L, "sms", 49L), appended.kindEdges(), "main + delta, not the delta alone");
    }

    @Test
    void aManifestWrittenBeforeTheStatisticsReadsThemAsNotRecordedAndTheyRoundTrip() throws Exception {
        IndexManifest old = new IndexManifest(3, "2026-10-01T00:00:00Z", IndexManifest.Builder.FULL, "v1.4.1", BucketFunction.NAME, 4,
                IndexBuilder.ROW_GROUP_SIZE, MAPPING, MAPPING.hash(), DS, "h", "fp", "UTC",
                Map.of("out", new IndexManifest.TableStats(3, 1, 10)), 0, null, null, null, null);
        String json = old.toJson();
        for (String f : List.of("kindEdges", "typeNodes", "degree", "timeSpan"))
            assertFalse(json.contains("\"" + f + "\""), "an unrecorded field is not written: " + f);
        IndexManifest read = IndexManifest.fromJson(json);
        assertNull(read.kindEdges());
        assertNull(read.typeNodes());
        assertNull(read.degree());
        assertNull(read.timeSpan());

        IndexManifest with = old.withLinkStats(Map.of("call", 2L), Map.of(), new IndexManifest.Degree(1, 2, 3, 4),
                new IndexManifest.TimeSpan("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"));
        IndexManifest back = IndexManifest.fromJson(with.toJson());
        assertEquals(with, back);
        assertEquals(Map.of(), back.typeNodes(), "an empty map stays empty, not unrecorded");
    }

    private static void assertStatsEqual(IndexManifest want, IndexManifest got) {
        assertEquals(want.kindEdges(), got.kindEdges(), "kindEdges");
        assertEquals(want.typeNodes(), got.typeNodes(), "typeNodes");
        assertEquals(want.degree(), got.degree(), "degree");
        assertEquals(want.timeSpan(), got.timeSpan(), "timeSpan");
    }
}
