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
                                             IndexBuilder.EndpointKey srcType, IndexBuilder.EndpointKey dstType) throws Exception {
        return IndexBuilder.build(new IndexBuilder.Request(DS, m, relation(data, files), new IndexStore(root, DS, m.hash()),
                "fp-" + files.size(), IndexBuilder.Options.defaults(), stamps(data, files), mode, added -> relation(data, added),
                List.of(data), IndexPlan.DEFAULT_MAX_DELTAS, srcType, dstType));
    }

    private static final List<String> BOTH = List.of("f0.parquet", "f1.parquet");

    /** A type keyed by the raw id (the test's stand-in for a normaliser that changes nothing). */
    private static IndexBuilder.EndpointKey type(String id) {
        return new IndexBuilder.EndpointKey(id, "default", c -> c);
    }

    @Test
    void aFullBuildRecordsKindCountsDegreeQuantilesOfTheStarAndTheTimeSpan(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        IndexManifest m = build(tmp.resolve("ix"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, null, null).manifest();

        assertEquals(Map.of("call", 50L, "sms", 49L), m.kindEdges(), "the NULL kind of leaf 99 is not counted");
        assertEquals(List.of("call", "sms"), List.copyOf(m.kindEdges().keySet()), "most edges first");
        // 101 nodes: 100 leaves of degree 1 and the hub of degree 100
        assertEquals(new IndexManifest.Degree(1, 1, 1, 100), m.degree());
        assertEquals(Map.of(1L, 100L, 100L, 1L), m.degreeHistogram());
        assertEquals(new IndexManifest.EndpointTypes(null, null), m.endpointTypes());
        assertEquals(new IndexManifest.TimeSpan("2026-01-01T00:00:00Z", "2026-01-05T03:00:00Z"), m.timeSpan(),
                "leaf 0 to leaf 99 (99 hours later); the NULL ts of leaf 7 is skipped");
        assertEquals(Map.of(), m.typeNodes(), "untyped endpoint columns: recorded, and empty");

        IndexBuilder.Result again = build(tmp.resolve("ix"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, null, null);
        IndexManifest onDisk = IndexManifest.read(again.directory());
        assertEquals(Map.of("call", 50L, "sms", 49L), onDisk.kindEdges(), "the statistics are written to manifest.json");
        assertEquals(new IndexManifest.Degree(1, 1, 1, 100), onDisk.degree());

        // one key's links, folded the same way: the hub's 100 neighbours, its NULL-kind link as a null kind
        try (IndexReader r = IndexReader.open(again.directory(), onDisk, com.gamma.sql.SqlSandboxPolicy.defaultPolicy())) {
            IndexReader.KeyLinks hub = r.keyLinks("src", "dst", "H");
            assertEquals(100, hub.degree());
            java.util.Map<String, Long> kinds = new java.util.HashMap<>();
            kinds.put("call", 50L);
            kinds.put("sms", 49L);
            kinds.put(null, 1L);
            assertEquals(kinds, hub.kinds());
            assertEquals(1, r.keyLinks("src", "dst", "L42").degree());
            assertEquals(0, r.keyLinks("src", "dst", "nobody").degree());
        }
    }

    @Test
    void typedEndpointsCountTheirNodesOncePerTypeAndASharedTypeCountsEachNodeOnce(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        assertEquals(Map.of("msisdn", 101L),
                build(tmp.resolve("a"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, type("msisdn"), type("msisdn")).manifest().typeNodes());
        assertEquals(Map.of("msisdn", 1L, "imei", 100L),
                build(tmp.resolve("b"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, type("msisdn"), type("imei")).manifest().typeNodes(),
                "the hub is only ever a source, the leaves only ever targets");
        assertEquals(Map.of("imei", 100L),
                build(tmp.resolve("c"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, null, type("imei")).manifest().typeNodes());
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
        IndexManifest full = build(tmp.resolve("full"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, type("msisdn"), type("imei")).manifest();

        Path root = tmp.resolve("inc");
        IndexManifest first = build(root, data, MAPPING, IndexBuilder.Mode.FULL, List.of("f0.parquet"), type("msisdn"), type("imei")).manifest();
        assertEquals(50, first.degree().max(), "the hub has 50 leaves in f0");
        IndexManifest appended = build(root, data, MAPPING, IndexBuilder.Mode.APPEND, BOTH, type("msisdn"), type("imei")).manifest();
        assertEquals(IndexManifest.Builder.APPEND, appended.builder());
        // the hub has a node row in the main AND in the delta: its degree is their sum, not either one
        assertStatsEqual(full, appended);
        IndexManifest compacted = build(root, data, MAPPING, IndexBuilder.Mode.COMPACT, BOTH, type("msisdn"), type("imei")).manifest();
        assertEquals(IndexManifest.Builder.COMPACT, compacted.builder());
        assertStatsEqual(full, compacted);
    }

    @Test
    void theEndsAreKeyedByTheirTypesNormalisedKeyAndFoldedToDistinctLinksWithoutSelfLinks(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        // a "normaliser" that folds every leaf to the one key L: the 100 edges become the links (H, L, call) and (H, L, sms)
        // (leaf 99's NULL kind is a third link, not a kind)
        IndexBuilder.EndpointKey leaves = new IndexBuilder.EndpointKey("t", "fold", c -> "CASE WHEN " + c + " LIKE 'L%' THEN 'L' ELSE " + c + " END");
        IndexManifest m = build(tmp.resolve("a"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, leaves, leaves).manifest();
        assertEquals(Map.of("call", 1L, "sms", 1L), m.kindEdges());
        assertEquals(Map.of(1L, 2L), m.degreeHistogram(), "H and L each have one neighbour");
        assertEquals(Map.of("t", 2L), m.typeNodes());
        assertEquals(new IndexManifest.EndpointTypes(new IndexManifest.EndpointType("t", "fold"), new IndexManifest.EndpointType("t", "fold")),
                m.endpointTypes());

        // every end folds to one key: every link is a self-link, so there is nothing to count - and the build still publishes
        IndexBuilder.EndpointKey all = new IndexBuilder.EndpointKey("t", "one", c -> "'K'");
        IndexManifest none = build(tmp.resolve("b"), data, MAPPING, IndexBuilder.Mode.FULL, BOTH, all, all).manifest();
        assertEquals(Map.of(), none.kindEdges());
        assertEquals(Map.of(), none.degreeHistogram());
        assertEquals(new IndexManifest.Degree(0, 0, 0, 0), none.degree());
    }

    @Test
    void aManifestWrittenBeforeTheStatisticsReadsThemAsNotRecordedAndTheyRoundTrip() {
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
        assertNull(read.degreeHistogram());
        assertNull(read.endpointTypes());

        IndexManifest with = old.withLinkStats(Map.of("call", 2L), Map.of(), new IndexManifest.Degree(1, 2, 3, 4),
                new IndexManifest.TimeSpan("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"), Map.of(1L, 3L, 4L, 1L),
                new IndexManifest.EndpointTypes(new IndexManifest.EndpointType("msisdn", "e164"), null));
        IndexManifest back = IndexManifest.fromJson(with.toJson());
        assertEquals(with, back);
        assertEquals(Map.of(), back.typeNodes(), "an empty map stays empty, not unrecorded");
    }

    private static void assertStatsEqual(IndexManifest want, IndexManifest got) {
        assertEquals(want.kindEdges(), got.kindEdges(), "kindEdges");
        assertEquals(want.typeNodes(), got.typeNodes(), "typeNodes");
        assertEquals(want.degree(), got.degree(), "degree");
        assertEquals(want.timeSpan(), got.timeSpan(), "timeSpan");
        assertEquals(want.degreeHistogram(), got.degreeHistogram(), "degreeHistogram");
    }
}
