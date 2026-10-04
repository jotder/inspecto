package com.gamma.la.storage;

import com.gamma.la.storage.IndexBuilder.CancelToken;
import com.gamma.la.storage.IndexBuilder.CancelledException;
import com.gamma.la.storage.IndexBuilder.IndexBuildException;
import com.gamma.la.storage.IndexBuilder.Options;
import com.gamma.la.storage.IndexBuilder.Request;
import com.gamma.la.storage.IndexBuilder.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** D-3 step 3. Every corpus is generated at test time into a TempDir; nothing is committed. */
class IndexBuilderTest {

    // ------------------------------------------------------------------------------------------ planted corpus

    record E(String a, String b, String kind, String ts, Double w, String cell) { }

    /** The planted edges. Wall-clock ts are Asia/Kolkata (UTC+05:30, no DST), so the stored UTC value is 5h30 earlier. */
    static final List<E> EDGES = List.of(
            new E("A", "B", "call", "2026-01-01 03:00:00", 10.0, "c1"),
            new E("A", "B", "call", "2026-01-01 11:00:00", 20.0, "c1"),      // parallel, same kind
            new E("A", "B", "sms", "2026-01-02 09:00:00", null, "c2"),       // multi-kind
            new E("A", "B", "call", null, 3.0, "c1"),                         // NULL time
            new E("A", "C", "call", "2026-01-03 09:00:00", 5.0, null),
            new E("C", "A", "call", "2026-01-04 09:00:00", 6.0, "c3"),
            new E("B", "B", "call", "2026-01-05 09:00:00", 1.0, "c4"),        // self-loop
            new E("D", "A", null, "2026-01-06 09:00:00", null, "c5"),         // NULL kind, parallel with the next
            new E("D", "A", null, "2026-01-06 10:00:00", null, "c5"),
            new E(null, "A", "call", "2026-01-07 09:00:00", 1.0, "x"),        // dropped: NULL source
            new E("A", null, "call", "2026-01-07 09:00:00", 1.0, "x"),        // dropped: NULL target
            new E(null, null, null, null, null, null),                         // dropped
            new E("a", "A", "call", "2026-01-07 09:00:00", 2.0, "c6"),        // ids differing only by case
            new E("A", "a", "call", "2026-01-07 09:00:00", 2.0, "c6"),
            new E("É", "É2", "sms", "2026-01-08 09:00:00", 1.0, "c7"),
            new E("é", "x", "call", "2026-01-08 09:00:00", 1.0, "c8"),  // decomposed e-acute is a different id from the composed one
            new E("日本", "Ünï", "sms", "2026-01-09 09:00:00", 1.0, "c9"));

    static final String ZONE = "Asia/Kolkata";
    static final IndexMapping MAPPING = new IndexMapping("a_party", "b_party", "call_type", "ts", ZONE, "dur", List.of("cell"));
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String sqlPath(Path p) { return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''"); }

    /** Every directory {@link #plant} wrote: the declared read roots of the builds below (the relation reads nowhere else). */
    private static final List<Path> PLANTED = new java.util.concurrent.CopyOnWriteArrayList<>();

    private static Request req(String ds, IndexMapping m, String rel, IndexStore store, String fp, Options o) {
        return new Request(ds, m, rel, store, fp, o, null, IndexBuilder.Mode.FULL, null, List.copyOf(PLANTED));
    }

    /** Writes the planted edges as three parquet files (so the relation really is a glob over several files); returns the relation SQL. */
    static String plant(Path dir, List<E> edges, String tsType) throws Exception {
        Files.createDirectories(dir);
        PLANTED.add(dir);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("CREATE TABLE e (n INTEGER, a_party VARCHAR, b_party VARCHAR, call_type VARCHAR, ts " + tsType + ", dur DOUBLE, cell VARCHAR)");
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO e VALUES (?, ?, ?, ?, CAST(? AS " + tsType + "), ?, ?)")) {
                for (int i = 0; i < edges.size(); i++) {
                    E e = edges.get(i);
                    ins.setInt(1, i);
                    ins.setString(2, e.a());
                    ins.setString(3, e.b());
                    ins.setString(4, e.kind());
                    ins.setString(5, e.ts());
                    ins.setObject(6, e.w());
                    ins.setString(7, e.cell());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            for (int k = 0; k < 3; k++)
                st.execute("COPY (SELECT a_party, b_party, call_type, ts, dur, cell FROM e WHERE n % 3 = " + k + ") TO '" + sqlPath(dir.resolve("part_" + k + ".parquet")) + "' (FORMAT parquet)");
        }
        return "SELECT * FROM read_parquet('" + sqlPath(dir) + "/part_*.parquet')";
    }

    static Result build(Path root, String rel, IndexMapping mapping, Options options) {
        return IndexBuilder.build(req("planted ds", mapping, rel, new IndexStore(root, "planted ds", mapping.hash()), "fp-1", options));
    }

    static Connection mem() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = c.createStatement()) { st.execute("SET TimeZone = 'UTC'"); }
        return c;
    }

    static List<List<String>> query(Connection c, String sql, Object... binds) throws Exception {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < binds.length; i++) p.setObject(i + 1, binds[i]);
            try (ResultSet rs = p.executeQuery()) {
                List<List<String>> out = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.add(rs.getString(i));
                    out.add(row);
                }
                return out;
            }
        }
    }

    static String utc(String kolkataWall) {
        return kolkataWall == null ? null : LocalDateTime.parse(kolkataWall.replace(' ', 'T')).atZone(ZoneId.of(ZONE)).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(FMT);
    }

    // ------------------------------------------------------------------------------------------------- golden

    @Test
    void goldenPlantedCorpus(@TempDir Path tmp) throws Exception {
        String rel = plant(tmp.resolve("corpus"), EDGES, "TIMESTAMP");
        List<String> phases = new ArrayList<>();
        Result r = build(tmp.resolve("idx"), rel, MAPPING, new Options(null, null, null, null, p -> phases.add(p.phase())));

        // counts
        assertEquals(17, r.rowsInRelation());
        assertEquals(14, r.edges());
        assertEquals(3, r.droppedNull());
        assertEquals(16, r.buckets(), "17 rows: the formula's floor");
        assertEquals(List.of("estimate", "out", "in", "nodes", "verify", "publish"), phases);
        IndexManifest man = IndexManifest.read(r.directory());
        assertEquals(man, r.manifest());
        assertEquals(1, man.version());
        assertEquals(IndexManifest.Builder.FULL, man.builder());
        assertEquals("md5_number_lower", man.bucketFn());
        assertEquals(100_000, man.rowGroupSize());
        assertEquals("planted ds", man.dataset());
        assertEquals(MAPPING, man.mapping());
        assertEquals(ZONE, man.timeColZone());
        assertEquals("fp-1", man.baseFingerprint());
        assertEquals(3, man.droppedNull());
        assertEquals(64, man.relationSqlHash().length());
        assertTrue(man.duckdbVersion().startsWith("v1."), man.duckdbVersion());
        assertEquals(14, man.tables().get("out").rows());
        assertEquals(14, man.tables().get("in").rows());
        assertTrue(man.tables().get("out").files() >= 1 && man.tables().get("out").bytes() > 0);
        assertEquals(r.nodes(), man.tables().get("nodes").rows());

        // published, nothing left behind
        IndexStore store = new IndexStore(tmp.resolve("idx"), "planted ds", MAPPING.hash());
        assertEquals(r.directory(), store.current().orElseThrow());
        try (Stream<Path> w = Files.walk(store.directory())) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().endsWith(".tmp") || p.getFileName().toString().equals(".spill")));
        }
        assertEquals(man, IndexBuilder.verify(r.directory()));

        try (Connection c = mem()) {
            String v = sqlPath(r.directory());
            assertEquals(List.of(List.of("ZSTD")), query(c, "SELECT DISTINCT compression FROM parquet_metadata('" + v + "/out/**/*.parquet') WHERE path_in_schema = 'src'"));

            // nodes against an independent Java oracle over the planted list
            Map<String, List<String>> expected = oracle();
            Map<String, List<String>> actual = new TreeMap<>();
            for (List<String> row : query(c, "SELECT id, out_edges, in_edges, out_links, in_links, CAST(first_ts AS VARCHAR), CAST(last_ts AS VARCHAR) FROM read_parquet('"
                    + v + "/nodes/**/*.parquet', hive_partitioning = true)"))
                actual.put(row.get(0), row.subList(1, row.size()));
            assertEquals(new TreeMap<>(expected), actual);
            // ... and by hand for the two interesting ones
            assertEquals(List.of("6", "4", "4", "3", "2025-12-31 21:30:00", "2026-01-07 03:30:00"), actual.get("A"));
            assertEquals(List.of("1", "5", "1", "3", "2025-12-31 21:30:00", "2026-01-05 03:30:00"), actual.get("B"));
            // every node row sits in the bucket the Java function names
            for (List<String> row : query(c, "SELECT id, bucket FROM read_parquet('" + v + "/nodes/**/*.parquet', hive_partitioning = true)"))
                assertEquals(BucketFunction.bucketOf(row.get(0), man.buckets()), Integer.parseInt(row.get(1)), row.get(0));

            // one hop: the index (bucket + key equality, both directions) equals the flat read through the same relation SQL
            List<String> probes = List.of("A", "B", "C", "D", "a", "É", "é", "日本", "Ünï", "x", "no-such-id");
            String ts = "CAST(CAST(timezone('UTC', timezone('" + ZONE + "', CAST(ts AS TIMESTAMP))) AS TIMESTAMP) AS VARCHAR)";
            for (String id : probes) {
                int b = BucketFunction.bucketOf(id, man.buckets());
                assertEquals(
                        query(c, "SELECT CAST(b_party AS VARCHAR), CAST(call_type AS VARCHAR), " + ts + ", CAST(dur AS VARCHAR), cell FROM (" + rel
                                + ") WHERE CAST(a_party AS VARCHAR) = ? AND b_party IS NOT NULL ORDER BY 1, 2, 3, 4, 5", id),
                        query(c, "SELECT dst, kind, CAST(ts AS VARCHAR), CAST(w AS VARCHAR), a0 FROM read_parquet('" + v + "/out/**/*.parquet', hive_partitioning = true)"
                                + " WHERE bucket = ? AND src = ? ORDER BY 1, 2, 3, 4, 5", b, id),
                        "out of " + id);
                assertEquals(
                        query(c, "SELECT CAST(a_party AS VARCHAR), CAST(call_type AS VARCHAR), " + ts + ", CAST(dur AS VARCHAR), cell FROM (" + rel
                                + ") WHERE CAST(b_party AS VARCHAR) = ? AND a_party IS NOT NULL ORDER BY 1, 2, 3, 4, 5", id),
                        query(c, "SELECT src, kind, CAST(ts AS VARCHAR), CAST(w AS VARCHAR), a0 FROM read_parquet('" + v + "/in/**/*.parquet', hive_partitioning = true)"
                                + " WHERE bucket = ? AND dst = ? ORDER BY 1, 2, 3, 4, 5", b, id),
                        "in of " + id);
            }
            assertEquals(List.of(List.of("B", "call", "2025-12-31 21:30:00", "10.0", "c1")),
                    query(c, "SELECT dst, kind, CAST(ts AS VARCHAR), CAST(w AS VARCHAR), a0 FROM read_parquet('" + v + "/out/**/*.parquet', hive_partitioning = true)"
                            + " WHERE bucket = ? AND src = ? AND ts = TIMESTAMP '2025-12-31 21:30:00'", BucketFunction.bucketOf("A", 16), "A"));
        }
    }

    /** id -> [out_edges, in_edges, out_links, in_links, first_ts, last_ts], computed from the planted list in plain Java. */
    static Map<String, List<String>> oracle() {
        List<E> kept = EDGES.stream().filter(e -> e.a() != null && e.b() != null).toList();
        Set<String> ids = new LinkedHashSet<>();
        kept.forEach(e -> { ids.add(e.a()); ids.add(e.b()); });
        Map<String, List<String>> out = new TreeMap<>();
        for (String id : ids) {
            List<E> o = kept.stream().filter(e -> e.a().equals(id)).toList();
            List<E> i = kept.stream().filter(e -> e.b().equals(id)).toList();
            long ol = o.stream().map(e -> e.b() + "|" + e.kind()).distinct().count();
            long il = i.stream().map(e -> e.a() + "|" + e.kind()).distinct().count();
            List<String> times = Stream.concat(o.stream(), i.stream()).map(E::ts).filter(t -> t != null).map(IndexBuilderTest::utc).sorted().toList();
            out.put(id, List.of(String.valueOf(o.size()), String.valueOf(i.size()), String.valueOf(ol), String.valueOf(il),
                    times.isEmpty() ? null : times.get(0), times.isEmpty() ? null : times.get(times.size() - 1)));
        }
        return out;
    }

    @Test
    void integerIdsAreCastToVarcharAndUnmappedKindAndTimeAreNull(@TempDir Path tmp) throws Exception {
        String rel = "SELECT CAST(i AS BIGINT) AS s, CAST((i * 7) % 50 AS BIGINT) AS t FROM range(500) r(i)";
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        Result r = IndexBuilder.build(req("ints", m, rel, new IndexStore(tmp, "ints", m.hash()), "fp", null));
        assertEquals(500, r.edges());
        assertEquals(0, r.droppedNull());
        assertNull(r.manifest().timeColZone());
        try (Connection c = mem()) {
            String v = sqlPath(r.directory());
            assertEquals(List.of(List.of("0")), query(c, "SELECT count(*) FROM read_parquet('" + v + "/out/**/*.parquet') WHERE kind IS NOT NULL OR ts IS NOT NULL OR w IS NOT NULL"));
            assertEquals(List.of(List.of("0")), query(c, "SELECT count(*) FROM read_parquet('" + v + "/nodes/**/*.parquet') WHERE first_ts IS NOT NULL OR last_ts IS NOT NULL"));
            assertEquals(query(c, "SELECT CAST(t AS VARCHAR) FROM (" + rel + ") WHERE CAST(s AS VARCHAR) = '3' ORDER BY 1"),
                    query(c, "SELECT dst FROM read_parquet('" + v + "/out/**/*.parquet', hive_partitioning = true) WHERE bucket = ? AND src = '3' ORDER BY 1", BucketFunction.bucketOf("3", r.buckets())));
        }
    }

    // ------------------------------------------------------------------------------------------------- time

    @Test
    void timeIsReadInTheExplicitZoneNeverTheHostZone(@TempDir Path tmp) throws Exception {
        List<E> two = List.of(new E("a", "b", "k", "2026-01-15 12:00:00", null, null), new E("a", "b", "k", "2026-07-15 12:00:00", null, null));
        String rel = plant(tmp.resolve("c"), two, "TIMESTAMP");
        IndexMapping ny = new IndexMapping("a_party", "b_party", "call_type", "ts", "America/New_York", null, null);
        TimeZone before = TimeZone.getDefault();
        try {
            for (String host : new String[] {"UTC", "Pacific/Auckland", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(host));
                Result r = IndexBuilder.build(req("tz-" + host, ny, rel, new IndexStore(tmp.resolve("i-" + host.replace('/', '_')), "tz", ny.hash()), "fp", null));
                assertEquals("America/New_York", r.manifest().timeColZone());
                try (Connection c = mem()) {
                    assertEquals(List.of(List.of("2026-01-15 17:00:00"), List.of("2026-07-15 16:00:00")),   // EST = UTC-5, EDT = UTC-4
                            query(c, "SELECT CAST(ts AS VARCHAR) FROM read_parquet('" + sqlPath(r.directory()) + "/out/**/*.parquet') ORDER BY ts"), "host " + host);
                }
            }
        } finally {
            TimeZone.setDefault(before);
        }
        // no zone given for a naive column: UTC, recorded as such
        IndexMapping none = new IndexMapping("a_party", "b_party", null, "ts", null, null, null);
        Result r = IndexBuilder.build(req("tz-none", none, rel, new IndexStore(tmp.resolve("i-none"), "tz", none.hash()), "fp", null));
        assertEquals("UTC", r.manifest().timeColZone());
        try (Connection c = mem()) {
            assertEquals(List.of(List.of("2026-01-15 12:00:00"), List.of("2026-07-15 12:00:00")),
                    query(c, "SELECT CAST(ts AS VARCHAR) FROM read_parquet('" + sqlPath(r.directory()) + "/out/**/*.parquet') ORDER BY ts"));
        }
    }

    @Test
    void aTimestampWithTimeZoneColumnIsAnInstantAndRefusesAZone(@TempDir Path tmp) throws Exception {
        String rel = "SELECT 'a' AS s, 'b' AS d, TIMESTAMPTZ '2026-01-01 00:00:00+05:30' AS t";
        IndexMapping ok = new IndexMapping("s", "d", null, "t", null, null, null);
        Result r = IndexBuilder.build(req("tz", ok, rel, new IndexStore(tmp, "tz", ok.hash()), "fp", null));
        assertNull(r.manifest().timeColZone());
        try (Connection c = mem()) {
            assertEquals(List.of(List.of("2025-12-31 18:30:00")), query(c, "SELECT CAST(ts AS VARCHAR) FROM read_parquet('" + sqlPath(r.directory()) + "/out/**/*.parquet')"));
        }
        IndexMapping bad = new IndexMapping("s", "d", null, "t", "Asia/Kolkata", null, null);
        IndexStore store = new IndexStore(tmp, "tz2", bad.hash());
        assertThrows(IllegalArgumentException.class, () -> IndexBuilder.build(req("tz2", bad, rel, store, "fp", null)));
        assertNoStageAndNoCurrent(store);
    }

    @Test
    void unsafeInputsAreRefusedBeforeAnyWork(@TempDir Path tmp) {
        IndexMapping zoneInjection = new IndexMapping("s", "d", null, "t", "UTC') ; DROP TABLE x; --", null, null);
        assertThrows(IllegalArgumentException.class, () -> IndexBuilder.build(req("d", zoneInjection, "SELECT 1", new IndexStore(tmp, "d", "h"), "fp", null)));
        IndexMapping m = new IndexMapping("s", "d", null, null, null, null, null);
        for (String mem : new String[] {"1GB'; DROP", "lots", ""})
            assertThrows(IllegalArgumentException.class, () -> IndexBuilder.build(req("d", m, "SELECT 1", new IndexStore(tmp, "d", "h"), "fp", new Options(null, mem, null, null, null))), mem);
        assertThrows(IllegalArgumentException.class, () -> IndexBuilder.build(req("d", m, "SELECT 1", new IndexStore(tmp, "d", "h"), "fp", new Options(0, null, null, null, null))));
    }

    // ------------------------------------------------------------------------------------------------- shape

    @Test
    void heavyTailedCorpusSpreadsAcrossBucketsInBothDirections(@TempDir Path tmp) throws Exception {
        // ~1.8% of the edges leave node n0 (a hub); the largest bucket must still stay within 2x the median
        String rel = "SELECT 'n' || CAST(floor(3000 * pow((hash(i) % 100000) / 100000.0, 2)) AS INTEGER) AS s,"
                + " 'n' || CAST(floor(3000 * pow((hash(i * 31 + 7) % 100000) / 100000.0, 2)) AS INTEGER) AS t FROM range(60000) r(i)";
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        Result r = IndexBuilder.build(req("skew", m, rel, new IndexStore(tmp, "skew", m.hash()), "fp", new Options(16, null, null, null, null)));
        assertEquals(16, r.buckets());
        try (Connection c = mem()) {
            for (String t : new String[] {"out", "in"}) {
                List<Long> counts = new ArrayList<>();
                for (List<String> row : query(c, "SELECT bucket, count(*) FROM read_parquet('" + sqlPath(r.directory()) + "/" + t + "/**/*.parquet', hive_partitioning = true) GROUP BY bucket"))
                    counts.add(Long.parseLong(row.get(1)));
                assertEquals(16, counts.size(), t + ": every bucket present");
                Collections.sort(counts);
                long median = counts.get(8);
                assertTrue(counts.get(15) < 2 * median, t + " buckets " + counts);
            }
        }
    }

    @Test
    void memoryLimitAndThreadsAreHonouredAndTheSpillDirectoryIsCleaned(@TempDir Path tmp) throws Exception {
        String rel = "SELECT md5(CAST(i AS VARCHAR)) AS s, md5(CAST(i * 7 + 1 AS VARCHAR)) AS t FROM range(400000) r(i)";
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        Result r = IndexBuilder.build(req("mem", m, rel, new IndexStore(tmp, "mem", m.hash()), "fp", new Options(4, "256MB", 2, null, null)));
        assertEquals(400000, r.edges());
        assertEquals(4, r.buckets());
        try (Stream<Path> w = Files.walk(tmp)) {
            assertTrue(w.noneMatch(p -> p.getFileName().toString().equals(".spill")));
        }
    }

    // ------------------------------------------------------------------------------------------------- failure

    /** Bytes under the in-flight {@code .tmp} stage (spill and output), 0 when there is none yet. */
    private static long stageBytes(Path indexDir) throws java.io.IOException {
        try (Stream<Path> w = Files.walk(indexDir)) {
            return w.filter(Files::isRegularFile)
                    .filter(q -> indexDir.relativize(q).getName(0).toString().endsWith(".tmp"))
                    .mapToLong(q -> q.toFile().length()).sum();
        }
    }

    @Test
    void cancelMidCopyStopsTheStatementDeletesTheStageAndLeavesCurrentAlone(@TempDir Path tmp) throws Exception {
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        IndexStore store = new IndexStore(tmp, "slow", m.hash());
        Result first = IndexBuilder.build(req("slow", m, "SELECT 'a' AS s, 'b' AS t", store, "fp0", null));   // v000001
        assertEquals(first.directory(), store.current().orElseThrow());

        // 30M rows of md5 text, sorted under a small memory limit: far longer than the whole test is allowed to take
        String slow = "SELECT md5(CAST(i AS VARCHAR)) AS s, md5(CAST(i * 7 + 1 AS VARCHAR)) AS t FROM range(30000000) r(i)";
        CancelToken token = new CancelToken();
        CountDownLatch inOut = new CountDownLatch(1);
        ExecutorService ex = Executors.newSingleThreadExecutor();
        try {
            Future<Result> f = ex.submit(() -> IndexBuilder.build(req("slow", m, slow, store, "fp1",
                    new Options(16, "256MB", 2, token, p -> { if (p.phase().equals("out")) inOut.countDown(); }))));
            assertTrue(inOut.await(120, TimeUnit.SECONDS), "the build never reached the out COPY");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);       // the COPY is under way once bytes land in the stage
            while (stageBytes(store.directory()) == 0 && !f.isDone() && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(stageBytes(store.directory()) > 0, "no bytes were written to the stage within 60 s");
            assertFalse(f.isDone(), "the build finished before it could be cancelled - the corpus is too small");
            long t0 = System.nanoTime();
            token.cancel();
            var e = assertThrows(java.util.concurrent.ExecutionException.class, () -> f.get(60, TimeUnit.SECONDS));
            assertInstanceOf(CancelledException.class, e.getCause());
            long took = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(took < 20_000, "Statement.cancel() should stop the COPY promptly, took " + took + " ms");
        } finally {
            ex.shutdownNow();
        }
        assertEquals(first.directory(), store.current().orElseThrow(), "CURRENT is untouched");
        try (Stream<Path> l = Files.list(store.directory())) {
            assertEquals(List.of("CURRENT", "v000001"), l.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).sorted().toList(), "no stage left");
        }
    }

    @Test
    void aTokenCancelledBeforeTheStartDoesNothing(@TempDir Path tmp) {
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        IndexStore store = new IndexStore(tmp, "pre", m.hash());
        CancelToken token = new CancelToken();
        token.cancel();
        assertThrows(CancelledException.class, () -> IndexBuilder.build(req("pre", m, "SELECT 'a' AS s, 'b' AS t", store, "fp", new Options(null, null, null, token, null))));
        assertNoStageAndNoCurrent(store);
    }

    @Test
    void aRelationThatDoesNotHoldStillFailsVerificationAndPublishesNothing(@TempDir Path tmp) {
        // random() is re-evaluated per scan, so the count, out and in disagree - exactly what verification exists to catch
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        IndexStore store = new IndexStore(tmp, "flaky", m.hash());
        var e = assertThrows(IndexBuildException.class, () -> IndexBuilder.build(req("flaky", m,
                "SELECT i AS s, i + 1 AS t FROM range(20000) r(i) WHERE random() < 0.5", store, "fp", null)));
        assertTrue(e.getMessage().contains("verification failed"), e.getMessage());
        assertNoStageAndNoCurrent(store);
    }

    @Test
    void noEdgesAnUnknownColumnAndBrokenSqlAreRefusedWithAReason(@TempDir Path tmp) {
        IndexMapping m = new IndexMapping("s", "t", null, null, null, null, null);
        IndexStore a = new IndexStore(tmp, "a", m.hash());
        assertTrue(assertThrows(IndexBuildException.class, () -> IndexBuilder.build(req("a", m,
                "SELECT CAST(NULL AS VARCHAR) AS s, 'x' AS t FROM range(5)", a, "fp", null))).getMessage().contains("no edge with both endpoints"));
        assertNoStageAndNoCurrent(a);
        IndexStore b = new IndexStore(tmp, "b", m.hash());
        assertTrue(assertThrows(IndexBuildException.class, () -> IndexBuilder.build(req("b", m, "SELECT 1 AS s, 2 AS other", b, "fp", null)))
                .getMessage().contains("no column 't'"));
        assertNoStageAndNoCurrent(b);
        IndexStore c = new IndexStore(tmp, "c", m.hash());
        assertThrows(IndexBuildException.class, () -> IndexBuilder.build(req("c", m, "SELECT * FROM no_such_table", c, "fp", null)));
        assertNoStageAndNoCurrent(c);
    }

    @Test
    void reVerifyCatchesAMissingBucketFileAndAMissingBucketDirectory(@TempDir Path tmp) throws Exception {
        String rel = plant(tmp.resolve("corpus"), EDGES, "TIMESTAMP");
        Result r = build(tmp.resolve("idx"), rel, MAPPING, Options.defaults());
        IndexBuilder.verify(r.directory());
        List<Path> files;
        try (Stream<Path> w = Files.walk(r.directory().resolve("out"))) {
            files = w.filter(p -> p.toString().endsWith(".parquet")).sorted().toList();
        }
        Path victim = files.get(0);
        byte[] saved = Files.readAllBytes(victim);
        Files.delete(victim);
        var e = assertThrows(IndexBuildException.class, () -> IndexBuilder.verify(r.directory()));
        assertTrue(e.getMessage().contains("holds no parquet file") || e.getMessage().contains("differs from its manifest") || e.getMessage().contains("out holds"), e.getMessage());
        Files.delete(victim.getParent());                                  // the directory goes too: rows are missing, so the totals catch it
        assertThrows(IndexBuildException.class, () -> IndexBuilder.verify(r.directory()));
        Files.createDirectories(victim.getParent());
        Files.write(victim, saved);
        IndexBuilder.verify(r.directory());                                 // restored: passes again

        Path nodesFile;
        try (Stream<Path> w = Files.walk(r.directory().resolve("nodes"))) {
            nodesFile = w.filter(p -> p.toString().endsWith(".parquet")).sorted().findFirst().orElseThrow();
        }
        byte[] nodesSaved = Files.readAllBytes(nodesFile);
        Files.delete(nodesFile);
        assertThrows(IndexBuildException.class, () -> IndexBuilder.verify(r.directory()), "a lost node bucket must fail too");
        Files.write(nodesFile, nodesSaved);
        IndexBuilder.verify(r.directory());
    }

    @Test
    void reVerifyCatchesAManifestThatNoLongerMatchesTheFiles(@TempDir Path tmp) throws Exception {
        String rel = plant(tmp.resolve("corpus"), EDGES, "TIMESTAMP");
        Result r = build(tmp.resolve("idx"), rel, MAPPING, Options.defaults());
        IndexManifest m = r.manifest();
        for (String table : List.of("out", "nodes")) {
            Map<String, IndexManifest.TableStats> t = new java.util.LinkedHashMap<>(m.tables());
            IndexManifest.TableStats real = t.get(table);
            for (IndexManifest.TableStats lie : List.of(new IndexManifest.TableStats(real.rows() + 1, real.files(), real.bytes()),
                    new IndexManifest.TableStats(real.rows(), real.files() + 1, real.bytes()))) {
                t.put(table, lie);
                new IndexManifest(m.version(), m.builtAt(), m.builder(), m.duckdbVersion(), m.bucketFn(), m.buckets(), m.rowGroupSize(), m.mapping(), m.mappingHash(),
                        m.dataset(), m.relationSqlHash(), m.baseFingerprint(), m.timeColZone(), t, m.droppedNull(), m.deltas(), m.parent()).write(r.directory());
                var e = assertThrows(IndexBuildException.class, () -> IndexBuilder.verify(r.directory()), table + " " + lie);
                assertTrue(e.getMessage().contains("differs from its manifest"), e.getMessage());
            }
        }
        m.write(r.directory());
        IndexBuilder.verify(r.directory());
    }

    // ------------------------------------------------------------------------------------------------- bench

    @Test
    @EnabledIfSystemProperty(named = "inspecto.bench", matches = "true")
    void generatedBuildTimings(@TempDir Path tmp) throws Exception {
        long edges = Long.getLong("inspecto.bench.edges", 1_000_000L);
        String rel = "SELECT 'n' || CAST(floor(" + (edges / 5) + " * pow((hash(i) % 1000000) / 1000000.0, 2)) AS BIGINT) AS s,"
                + " 'n' || CAST(floor(" + (edges / 5) + " * pow((hash(i * 31 + 7) % 1000000) / 1000000.0, 2)) AS BIGINT) AS t,"
                + " TIMESTAMP '2026-01-01 00:00:00' + to_seconds(i % 31536000) AS ts FROM range(" + edges + ") r(i)";
        IndexMapping m = new IndexMapping("s", "t", null, "ts", "UTC", null, null);
        Result r = IndexBuilder.build(req("bench", m, rel, new IndexStore(tmp, "bench", m.hash()), "fp", null));
        IndexBuilder.verify(r.directory());
        System.out.println("D3S3 bench edges=" + edges + " buckets=" + r.buckets() + " nodes=" + r.nodes() + " totalMs=" + r.totalMs() + " timings=" + r.timingsMs()
                + " bytes=" + r.manifest().tables());
        assertEquals(edges, r.edges());
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static void assertNoStageAndNoCurrent(IndexStore store) {
        assertTrue(store.current().isEmpty(), "CURRENT must not exist");
        try (Stream<Path> l = Files.isDirectory(store.directory()) ? Files.list(store.directory()) : Stream.<Path>empty()) {
            List<String> left = l.map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).toList();
            assertEquals(List.of(), left, "no stage or version may remain");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void theRelationHashIgnoresThePinnedFileListButNotTheDefinition() {
        String one = "SELECT * FROM read_parquet(['/d/a.parquet'], union_by_name=true)";
        String two = "SELECT * FROM read_parquet(['/d/a.parquet', '/d/b''s.parquet'], union_by_name=true)";
        assertEquals(IndexBuilder.relationSqlHash(one), IndexBuilder.relationSqlHash(two), "an added file is not a definition change");
        assertNotEquals(IndexBuilder.relationSqlHash(one), IndexBuilder.relationSqlHash(one + " WHERE who <> 'x'"));
        String filterList = "SELECT * FROM read_parquet(['/d/a.parquet']) WHERE k IN ['a', 'b']";
        assertNotEquals(IndexBuilder.relationSqlHash(filterList), IndexBuilder.relationSqlHash(filterList.replace("'b'", "'c'")),
                "a literal list outside the read is part of the definition");
    }

    /**
     * ENGINE-INMEMORY-UNSEALED-1: the build connection is sealed to the declared read roots plus the store - a relation
     * reading a file outside them fails (the old unsealed opt-in read any local path), the twin over a declared root builds,
     * and a URL never reaches the network.
     */
    @Test
    void aBuildReadsOnlyDeclaredRootsAndNeverTheNetwork(@TempDir Path tmp) throws Exception {
        String rel = plant(tmp.resolve("declared"), EDGES, "TIMESTAMP");
        String other = plant(tmp.resolve("undeclared"), EDGES, "TIMESTAMP");
        PLANTED.clear();                                                      // each build below names its own roots
        IndexMapping m = MAPPING;
        IndexStore ok = new IndexStore(tmp.resolve("i-ok"), "ds", m.hash());
        IndexBuilder.build(new Request("ds", m, rel, ok, "fp", null, null, IndexBuilder.Mode.FULL, null, List.of(tmp.resolve("declared"))));
        assertTrue(ok.current().isPresent(), "the twin over a declared root builds");
        IndexStore bad = new IndexStore(tmp.resolve("i-bad"), "ds", m.hash());
        assertThrows(IndexBuildException.class, () -> IndexBuilder.build(
                new Request("ds", m, other, bad, "fp", null, null, IndexBuilder.Mode.FULL, null, List.of(tmp.resolve("declared")))));
        assertTrue(bad.current().isEmpty(), "a refused build publishes nothing");
        List<Path> declared = List.of(tmp.resolve("declared"));
        assertThrows(IndexBuildException.class, () -> IndexBuilder.countRows(other, 0, declared));
        assertEquals(EDGES.size(), IndexBuilder.countRows(rel, 0, declared));
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/x.csv", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        try {
            String url = "SELECT * FROM read_csv('http://127.0.0.1:" + server.getAddress().getPort() + "/x.csv')";
            assertThrows(IndexBuildException.class, () -> IndexBuilder.countRows(url, 0, declared));
        } finally {
            server.stop(0);
        }
        assertEquals(0, hits.get(), "the index builder's connection reached the network");
    }
}
