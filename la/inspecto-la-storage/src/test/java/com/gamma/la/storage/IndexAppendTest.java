package com.gamma.la.storage;

import com.gamma.la.core.Algorithm;
import com.gamma.la.core.GraphInput;
import com.gamma.la.core.GraphResult;
import com.gamma.la.storage.IndexBuilder.CancelToken;
import com.gamma.la.storage.IndexBuilder.NotApplicableException;
import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-3 step 8 - incremental append, compaction and the plan that decides between them. The corpus is a seeded random graph (60
 * nodes, 600 edges, three kinds, NULL kind / time / weight, NULL endpoints, a self-loop and case-differing ids) cut into five
 * parquet files, so "the same Dataset" can be indexed in one full build or as a full build plus appended files, and the two
 * must answer every read identically.
 */
class IndexAppendTest {

    private static final String DS = "ds";
    private static final IndexMapping MAPPING = new IndexMapping("s", "t", "k", "ts", null, "w", List.of("cell"));
    private static final int FILES = 5, PER_FILE = 120, NODES = 60;

    // ---------------------------------------------------------------------------------------------------- corpus

    /** Writes f0..f4.parquet (contiguous slices of one seeded random edge list) into {@code dir}. */
    private static void plant(Path dir) throws Exception {
        Files.createDirectories(dir);
        Random rnd = new Random(42);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("CREATE TABLE e (n INTEGER, s VARCHAR, t VARCHAR, k VARCHAR, ts TIMESTAMP, w DOUBLE, cell VARCHAR)");
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO e VALUES (?, ?, ?, ?, CAST(? AS TIMESTAMP), ?, ?)")) {
                for (int i = 0; i < FILES * PER_FILE; i++) {
                    String s = "N" + (int) (Math.pow(rnd.nextDouble(), 2) * NODES), t = "N" + rnd.nextInt(NODES);
                    if (i % 97 == 0) s = null;                                   // dropped: NULL endpoint
                    if (i % 89 == 0) t = null;
                    if (i % 41 == 0) t = s == null ? "n0" : s;                   // self-loop (or a case-differing id)
                    String kind = switch (rnd.nextInt(4)) { case 0 -> "call"; case 1 -> "sms"; case 2 -> "data"; default -> null; };
                    ins.setInt(1, i);
                    ins.setString(2, s);
                    ins.setString(3, t);
                    ins.setString(4, kind);
                    ins.setString(5, i % 13 == 0 ? null : "2026-01-" + String.format("%02d %02d:00:00", 1 + rnd.nextInt(28), rnd.nextInt(24)));
                    ins.setObject(6, i % 7 == 0 ? null : (Double) (double) rnd.nextInt(100));
                    ins.setString(7, "c" + rnd.nextInt(5));
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            for (int k = 0; k < FILES; k++)
                st.execute("COPY (SELECT s, t, k, ts, w, cell FROM e WHERE n >= " + k * PER_FILE + " AND n < " + (k + 1) * PER_FILE + " ORDER BY n) TO '"
                        + sql(dir.resolve("f" + k + ".parquet")) + "' (FORMAT parquet)");
        }
    }

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    /** The relation over the named files: a pinned list, as a store read renders it. */
    private static String relation(Path dir, List<String> files) {
        StringBuilder sb = new StringBuilder("SELECT * FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sql(dir.resolve(files.get(i)))).append('\'');
        return sb.append("])").toString();
    }

    private static List<String> names(int from, int toExclusive) {
        List<String> out = new ArrayList<>();
        for (int k = from; k < toExclusive; k++) out.add("f" + k + ".parquet");
        return out;
    }

    private static List<IndexManifest.InputFile> stamps(Path dir, List<String> files) throws Exception {
        List<IndexManifest.InputFile> out = new ArrayList<>();
        for (String f : files) out.add(new IndexManifest.InputFile(f, Files.size(dir.resolve(f)), Files.getLastModifiedTime(dir.resolve(f)).toMillis()));
        return out;
    }

    private static IndexStore store(Path root) {
        return new IndexStore(root, DS, MAPPING.hash());
    }

    /** One build over the first {@code files} files. */
    private static IndexBuilder.Result build(Path root, Path data, IndexBuilder.Mode mode, int files, IndexBuilder.Options opt) throws Exception {
        return buildOver(root, data, mode, names(0, files), opt);
    }

    private static IndexBuilder.Result buildOver(Path root, Path data, IndexBuilder.Mode mode, List<String> files, IndexBuilder.Options opt) throws Exception {
        Function<List<String>, String> delta = added -> relation(data, added);
        return IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING, relation(data, files), store(root), "fp-" + files.size(), opt,
                stamps(data, files), mode, delta, List.of(data)));
    }

    // ---------------------------------------------------------------------------------------------------- parity

    private static List<String> keys() {
        List<String> k = new ArrayList<>();
        for (int i = 0; i < 12; i++) k.add("N" + i);                    // the hot nodes (the corpus skews to low ids) ...
        k.addAll(List.of("N30", "N44", "N59", "n0"));                   // ... a few cold ones and the case-differing id
        k.add("nobody");
        return k;
    }

    private static IndexReader open(IndexBuilder.Result r) throws Exception {
        return IndexReader.open(r.directory(), r.manifest(), SqlSandboxPolicy.defaultPolicy());
    }

    /** Every read path of two index versions, key by key; returns how many comparisons ran. */
    private static int assertSameAnswers(IndexBuilder.Result want, IndexBuilder.Result got) throws Exception {
        int n = 0;
        try (IndexReader a = open(want); IndexReader b = open(got)) {
            for (String key : keys()) {
                for (Side side : Side.values()) {
                    assertEquals(sorted(a.edges(List.of(key), List.of(side), null, 100_000)), sorted(b.edges(List.of(key), List.of(side), null, 100_000)), "edges " + key + side);
                    assertEquals(sorted(a.fold(key, side, List.of("kind", "a0"), null, null)), sorted(b.fold(key, side, List.of("kind", "a0"), null, null)), "fold " + key + side);
                    assertEquals(sorted(a.fold(key, side, List.of("kind"), List.of("call", "sms"), "ts IS NOT NULL")),
                            sorted(b.fold(key, side, List.of("kind"), List.of("call", "sms"), "ts IS NOT NULL")), "filtered fold " + key + side);
                    n += 3;
                }
                assertEquals(a.degree(key), b.degree(key), "degree " + key);
                n++;
                if (!key.matches("N(0|1|2|5|9|17|30|44)|n0|nobody")) continue;                  // a walk returns every path: a sample keeps the test quick
                for (boolean undirected : new boolean[] {false, true}) {
                    IndexedTraversal.Params p = new IndexedTraversal.Params(key, null, undirected, 2, 1_000_000, 100_000, false, false, null, null, null);
                    Object x = walk(a, p), y = walk(b, p);
                    assertEquals(x, y, "traversal " + key + " undirected=" + undirected);
                    n++;
                }
            }
        }
        for (String seed : List.of("N0", "N5", "n0", "nobody")) {
            for (String dir : List.of("out", "in", "both")) {
                for (Algorithm alg : List.of(Algorithm.NEIGHBORHOOD, Algorithm.EGO_NETWORK)) {
                    Map<String, Object> params = Map.of("node", seed, "hops", 1, "direction", dir);
                    assertEquals(engine(want, seed, alg, params), engine(got, seed, alg, params), "engine " + alg + " " + seed + " " + dir);
                    n++;
                }
            }
        }
        assertEquals(engine(want, "N0", Algorithm.DEGREE_CENTRALITY, Map.of()), engine(got, "N0", Algorithm.DEGREE_CENTRALITY, Map.of()));
        return n + 1;
    }

    private static Object engine(IndexBuilder.Result r, String seed, Algorithm alg, Map<String, Object> params) {
        GraphInput.IndexRef ref = new GraphInput.IndexRef(r.directory(), r.manifest().version(), "g:test", List.of(seed), List.of(), 0, 0);
        try {
            GraphResult res = new SqlGraphEngine(SqlSandboxPolicy.defaultPolicy()).run(alg, params, ref, null);
            return res.payload();
        } catch (RuntimeException capped) {
            return capped.getClass().getSimpleName() + ": " + capped.getMessage();       // a cap refusal must be the same refusal
        }
    }

    private static Object walk(IndexReader r, IndexedTraversal.Params p) throws Exception {
        try {
            IndexedTraversal.Result res = IndexedTraversal.walk(r, p);
            // paths that differ only by WHICH parallel edge they took tie on (hops, path), so their order is not defined: compare as a multiset
            return sorted(res.paths()) + " truncated=" + res.limitTruncated() + " capped=" + res.yieldCapped();
        } catch (IndexedTraversal.FrontierOverCap over) {
            return "over cap " + over.keys();
        }
    }

    private static <T> List<String> sorted(Map<String, List<T>> m) {
        List<String> out = new ArrayList<>();
        m.forEach((k, v) -> v.forEach(x -> out.add(k + "|" + x)));
        out.sort(null);
        return out;
    }

    private static <T> List<String> sorted(List<T> l) {
        List<String> out = new ArrayList<>();
        l.forEach(x -> out.add(String.valueOf(x)));
        out.sort(null);
        return out;
    }

    private static long maxFilesPerBucket(Path versionDir, String table) throws Exception {
        long max = 0;
        try (Stream<Path> buckets = Files.list(versionDir.resolve(table))) {
            for (Path b : (Iterable<Path>) buckets::iterator) {
                try (Stream<Path> fs = Files.list(b)) {
                    max = Math.max(max, fs.filter(f -> f.toString().endsWith(".parquet")).count());
                }
            }
        }
        return max;
    }

    private static List<String> versionDirs(Path root) throws Exception {
        try (Stream<Path> s = Files.list(store(root).directory())) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("v")).sorted().toList();
        }
    }

    // ---------------------------------------------------------------------------------------------------- tests

    @Test
    void anAppendedIndexAnswersEveryReadLikeAFullRebuildOfTheSameFiles(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        IndexBuilder.Result full = build(tmp.resolve("full"), data, IndexBuilder.Mode.FULL, FILES, null);

        Path inc = tmp.resolve("inc");
        IndexBuilder.Result v1 = build(inc, data, IndexBuilder.Mode.FULL, 3, null);
        IndexBuilder.Result v2 = buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 4), null);
        IndexBuilder.Result v3 = buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 5), null);

        // a new immutable version per append, chained by parent, each listing its deltas
        assertEquals(List.of(1L, 2L, 3L), List.of(v1.version(), v2.version(), v3.version()));
        assertEquals(IndexManifest.Builder.APPEND, v3.manifest().builder());
        assertEquals("v000002", v3.manifest().parent());
        assertEquals(List.of("d001", "d002"), v3.manifest().deltas().stream().map(IndexManifest.Delta::dir).toList());
        assertTrue(v1.manifest().deltas().isEmpty());
        assertEquals(full.manifest().tables().get("out").rows(), v3.manifest().tables().get("out").rows());
        assertEquals(full.manifest().droppedNull(), v3.manifest().droppedNull());
        assertEquals("fp-5", v3.manifest().baseFingerprint());
        assertEquals(5, v3.manifest().inputFiles().size());
        assertEquals(3, maxFilesPerBucket(v3.directory(), "out"), "main + two deltas in the busiest bucket");

        int compared = assertSameAnswers(full, v3);
        assertTrue(compared > 150, "ran " + compared + " comparisons");
        // the mid version is still whole and still answers as ITS files: v2 equals a full build over four files
        IndexBuilder.Result full4 = build(tmp.resolve("full4"), data, IndexBuilder.Mode.FULL, 4, null);
        assertSameAnswers(full4, v2);
        // a delta really adds rows (so equality above is not two copies of the same index)
        assertTrue(v3.manifest().tables().get("out").rows() > v1.manifest().tables().get("out").rows());
    }

    @Test
    void compactionMergesTheDeltasIntoOneSortedMainWithTheSameAnswers(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        IndexBuilder.Result full = build(tmp.resolve("full"), data, IndexBuilder.Mode.FULL, FILES, null);
        Path inc = tmp.resolve("inc");
        build(inc, data, IndexBuilder.Mode.FULL, 2, null);
        buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 3), null);
        buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 4), null);
        IndexBuilder.Result appended = buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 5), null);
        assertEquals(3, appended.manifest().deltas().size());

        IndexBuilder.Result compact = buildOver(inc, data, IndexBuilder.Mode.COMPACT, names(0, 5), null);
        assertEquals(IndexManifest.Builder.COMPACT, compact.manifest().builder());
        assertTrue(compact.manifest().deltas().isEmpty());
        assertEquals("v000004", compact.manifest().parent());
        assertEquals(appended.version() + 1, compact.version());
        // what the index COVERS is carried over, never re-derived (compaction does not read the Dataset)
        assertEquals(appended.manifest().baseFingerprint(), compact.manifest().baseFingerprint());
        assertEquals(appended.manifest().inputFiles(), compact.manifest().inputFiles());
        assertEquals(appended.manifest().droppedNull(), compact.manifest().droppedNull());
        // skipping is restored: one file per bucket again, and the node table is the full build's (distinct ids), not the per-delta partials
        long before = maxFilesPerBucket(appended.directory(), "out"), after = maxFilesPerBucket(compact.directory(), "out");
        assertEquals(4, before);
        assertEquals(1, after);
        assertEquals(full.manifest().tables().get("nodes").rows(), compact.manifest().tables().get("nodes").rows());
        assertTrue(appended.manifest().tables().get("nodes").rows() > compact.manifest().tables().get("nodes").rows(), "partial per-delta node rows were folded");
        assertSameAnswers(full, compact);
        // measured, not asserted: the same key lookup over the appended and the compacted version
        try (IndexReader a = open(appended); IndexReader c = open(compact)) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 20; i++) a.edges(List.of("N1"), List.of(Side.OUT), null, 1000);
            long t1 = System.nanoTime();
            for (int i = 0; i < 20; i++) c.edges(List.of("N1"), List.of(Side.OUT), null, 1000);
            long t2 = System.nanoTime();
            System.out.println("[step 8] key lookup x20: appended (" + before + " files/bucket) " + (t1 - t0) / 1_000_000 + " ms, compacted (1 file/bucket) " + (t2 - t1) / 1_000_000 + " ms");
        }
        // a version without deltas has nothing to compact
        assertThrows(NotApplicableException.class, () -> buildOver(inc, data, IndexBuilder.Mode.COMPACT, names(0, 5), null));
    }

    @Test
    void aRemovedOrRewrittenFileAChangedRelationOrNoNewFileForcesAFullRebuildAndLeavesNothingBehind(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        Path inc = tmp.resolve("inc");
        IndexBuilder.Result v1 = build(inc, data, IndexBuilder.Mode.FULL, 3, null);
        List<String> before = versionDirs(inc);

        // 1. a covered file is gone (superseded): files 0 and 2 + new 3, file 1 missing
        assertThrows(NotApplicableException.class, () -> buildOver(inc, data, IndexBuilder.Mode.APPEND, List.of("f0.parquet", "f2.parquet", "f3.parquet"), null));
        // 2. a covered file was rewritten (same path, other size/mtime)
        List<IndexManifest.InputFile> rewritten = new ArrayList<>(stamps(data, names(0, 4)));
        rewritten.set(1, new IndexManifest.InputFile("f1.parquet", rewritten.get(1).size() + 1, rewritten.get(1).mtimeMillis()));
        assertThrows(NotApplicableException.class, () -> IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING, relation(data, names(0, 4)), store(inc),
                "fp", null, rewritten, IndexBuilder.Mode.APPEND, added -> relation(data, added), List.of(data))));
        // 3. the relation's DEFINITION changed (a filter), even though a file was added
        assertThrows(NotApplicableException.class, () -> IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING,
                relation(data, names(0, 4)) + " WHERE k = 'call'", store(inc), "fp", null, stamps(data, names(0, 4)), IndexBuilder.Mode.APPEND,
                added -> relation(data, added), List.of(data))));
        // 4. nothing was added
        assertThrows(NotApplicableException.class, () -> buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 3), null));
        // 5. the Dataset cannot render a delta (not row-wise)
        assertThrows(NotApplicableException.class, () -> IndexBuilder.build(new IndexBuilder.Request(DS, MAPPING, relation(data, names(0, 4)), store(inc),
                "fp", null, stamps(data, names(0, 4)), IndexBuilder.Mode.APPEND, null, List.of(data))));
        // 6. an index that does not exist yet cannot be appended to
        assertThrows(NotApplicableException.class, () -> buildOver(tmp.resolve("none"), data, IndexBuilder.Mode.APPEND, names(0, 4), null));

        // every refusal left the store exactly as it was: no new version, no stage, CURRENT unchanged
        assertEquals(before, versionDirs(inc));
        assertEquals(v1.directory().getFileName(), store(inc).current().orElseThrow().getFileName());
        // the positive twin: the same request without the defect succeeds (the refusals above were for the stated reason)
        IndexBuilder.Result ok = buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 4), null);
        assertEquals(2, ok.version());
    }

    @Test
    void thePlanSaysAppendOnlyForPureAdditionsAndNamesWhatForcesFull() throws Exception {
        IndexMapping m = MAPPING;
        List<IndexManifest.InputFile> covered = List.of(new IndexManifest.InputFile("a", 1, 10), new IndexManifest.InputFile("b", 2, 20));
        IndexManifest base = manifest(m, covered, List.of(), "sqlhash", "hashfn", "1.0");
        List<IndexManifest.InputFile> plus = List.of(covered.get(0), covered.get(1), new IndexManifest.InputFile("c", 3, 30));

        IndexPlan.Plan append = IndexPlan.classify(base, plus, "sqlhash", "hashfn", "1.0");
        assertEquals(IndexPlan.Action.APPEND, append.recommended());
        assertTrue(append.appendable());
        assertEquals(List.of("c"), append.added());

        IndexPlan.Plan none = IndexPlan.classify(base, covered, "sqlhash", "hashfn", "1.0");
        assertEquals(IndexPlan.Action.NONE, none.recommended());
        assertFalse(none.appendable());

        assertFull(IndexPlan.classify(base, List.of(covered.get(0), new IndexManifest.InputFile("c", 3, 30)), "sqlhash", "hashfn", "1.0"), "input_files_removed");
        assertFull(IndexPlan.classify(base, List.of(covered.get(0), new IndexManifest.InputFile("b", 2, 21), new IndexManifest.InputFile("c", 3, 30)), "sqlhash", "hashfn", "1.0"), "input_files_changed");
        assertFull(IndexPlan.classify(base, plus, "other", "hashfn", "1.0"), "relation_sql_changed");
        assertFull(IndexPlan.classify(base, plus, null, "hashfn", "1.0"), "relation_unresolvable");
        assertFull(IndexPlan.classify(base, plus, "sqlhash", "md5_number_lower", "1.0"), "bucket_function_changed");     // a foreign bucket function
        assertFull(IndexPlan.classify(base, plus, "sqlhash", "hashfn", "2.0"), "duckdb_version_changed");
        assertFull(IndexPlan.classify(base, null, "sqlhash", "hashfn", "1.0"), "input_files_unknown");
        assertFull(IndexPlan.classify(manifest(m, null, List.of(), "sqlhash", "hashfn", "1.0"), plus, "sqlhash", "hashfn", "1.0"), "input_files_unknown");

        List<IndexManifest.Delta> eight = new ArrayList<>();
        for (int i = 1; i <= IndexPlan.MAX_DELTAS; i++) eight.add(new IndexManifest.Delta("d" + i, 1, 1));
        IndexManifest capped = manifest(m, covered, eight, "sqlhash", "hashfn", "1.0");
        IndexPlan.Plan atCap = IndexPlan.classify(capped, plus, "sqlhash", "hashfn", "1.0");
        assertEquals(IndexPlan.Action.COMPACT, atCap.recommended());
        assertFalse(atCap.appendable());
        assertEquals(List.of("delta_cap_reached"), atCap.reasons());
        assertEquals(IndexPlan.Action.COMPACT, IndexPlan.classify(capped, covered, "sqlhash", "hashfn", "1.0").recommended());
    }

    private static void assertFull(IndexPlan.Plan p, String reason) {
        assertEquals(IndexPlan.Action.FULL, p.recommended(), reason);
        assertFalse(p.appendable(), reason);
        assertTrue(p.reasons().contains(reason), reason + " in " + p.reasons());
    }

    private static IndexManifest manifest(IndexMapping m, List<IndexManifest.InputFile> files, List<IndexManifest.Delta> deltas, String sqlHash,
                                          String bucketFn, String duck) {
        return new IndexManifest(1, "t", IndexManifest.Builder.FULL, duck, bucketFn, 4, 100, m, m.hash(), DS, sqlHash, "fp", null, Map.of(), 0, deltas, null, files);
    }

    @Test
    void aCancelledAppendPublishesNothingAndTheLiveVersionIsUntouched(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        Path inc = tmp.resolve("inc");
        IndexBuilder.Result v1 = build(inc, data, IndexBuilder.Mode.FULL, 3, null);
        List<String> before = versionDirs(inc);
        List<String> dirBefore;
        try (Stream<Path> s = Files.list(store(inc).directory())) {
            dirBefore = s.map(p -> p.getFileName().toString()).sorted().toList();
        }
        for (String phase : List.of("estimate", "out", "in", "nodes", "verify")) {
            CancelToken token = new CancelToken();
            IndexBuilder.Options opt = new IndexBuilder.Options(null, null, null, token, p -> {
                if (p.phase().equals(phase)) token.cancel();
            });
            assertThrows(IndexBuilder.CancelledException.class, () -> buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 4), opt), "cancel at " + phase);
        }
        assertEquals(before, versionDirs(inc));
        try (Stream<Path> s = Files.list(store(inc).directory())) {
            assertEquals(dirBefore, s.map(p -> p.getFileName().toString()).sorted().toList(), "no stage, no CURRENT.tmp left");
        }
        assertEquals("v000001", store(inc).current().orElseThrow().getFileName().toString());
        assertSameAnswers(v1, v1);                                                       // the parent still reads (its files were never touched)
        IndexBuilder.Result again = buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 4), null);
        assertEquals(2, again.version());
        assertSameAnswers(build(tmp.resolve("full4"), data, IndexBuilder.Mode.FULL, 4, null), again);
    }

    @Test
    void aReaderOfTheLiveVersionKeepsAnsweringItsOwnVersionWhileAnAppendRunsAndPublishes(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        plant(data);
        Path inc = tmp.resolve("inc");
        IndexBuilder.Result v1 = build(inc, data, IndexBuilder.Mode.FULL, 3, null);
        List<String> baseline;
        try (IndexReader r = open(v1)) {
            baseline = sorted(r.edges(keys(), List.of(Side.OUT, Side.IN), null, 100_000));
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Integer> reads = new AtomicReference<>(0);
        Thread reader = new Thread(() -> {
            try (IndexReader r = open(v1)) {
                while (!stop.get()) {
                    List<String> now = sorted(r.edges(keys(), List.of(Side.OUT, Side.IN), null, 100_000));
                    if (!now.equals(baseline)) throw new AssertionError("a read of v1 changed while the append ran");
                    reads.set(reads.get() + 1);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        reader.start();
        IndexBuilder.Result v2;
        try {
            v2 = buildOver(inc, data, IndexBuilder.Mode.APPEND, names(0, 5), null);
        } finally {
            stop.set(true);
            reader.join();
        }
        assertNull(failure.get(), String.valueOf(failure.get()));
        assertTrue(reads.get() > 0);
        // v1 is still whole after the append, and v2 has strictly more edges (one version per response: no mixing)
        try (IndexReader r1 = open(v1); IndexReader r2 = open(v2)) {
            assertEquals(baseline, sorted(r1.edges(keys(), List.of(Side.OUT, Side.IN), null, 100_000)));
            assertTrue(sorted(r2.edges(keys(), List.of(Side.OUT, Side.IN), null, 100_000)).size() > baseline.size());
        }
        // the parent's files are hard links, not moved: deleting the parent version leaves the child readable
        long parentFiles;
        try (Stream<Path> w = Files.walk(v1.directory())) {
            parentFiles = w.filter(p -> p.toString().endsWith(".parquet")).count();
        }
        assertTrue(parentFiles > 0);
        IndexBuilder.Result full = build(tmp.resolve("full"), data, IndexBuilder.Mode.FULL, FILES, null);
        assertSameAnswers(full, v2);
        deleteTree(v1.directory());
        assertSameAnswers(full, v2);
    }

    // ---------------------------------------------------------------------------------------------------- T7 backfill

    private static final int DAYS = 30, PER_DAY = 40;

    /** Writes day01..day30.parquet: one event date per file (the feed contract), every row's ts on that date. */
    private static List<String> plantDays(Path dir) throws Exception {
        Files.createDirectories(dir);
        Random rnd = new Random(7);
        List<String> files = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("CREATE TABLE e (d INTEGER, s VARCHAR, t VARCHAR, k VARCHAR, ts TIMESTAMP, w DOUBLE, cell VARCHAR)");
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO e VALUES (?, ?, ?, ?, CAST(? AS TIMESTAMP), ?, ?)")) {
                for (int d = 1; d <= DAYS; d++) {
                    for (int i = 0; i < PER_DAY; i++) {
                        ins.setInt(1, d);
                        ins.setString(2, i % 53 == 0 ? null : "N" + (int) (Math.pow(rnd.nextDouble(), 2) * NODES));
                        ins.setString(3, "N" + rnd.nextInt(NODES));
                        ins.setString(4, rnd.nextBoolean() ? "call" : "sms");
                        ins.setString(5, "2026-09-" + String.format("%02d %02d:00:00", d, rnd.nextInt(24)));
                        ins.setObject(6, (double) rnd.nextInt(100));
                        ins.setString(7, "c" + rnd.nextInt(5));
                        ins.addBatch();
                    }
                }
                ins.executeBatch();
            }
            for (int d = 1; d <= DAYS; d++) {
                String name = String.format("day%02d.parquet", d);
                st.execute("COPY (SELECT s, t, k, ts, w, cell FROM e WHERE d = " + d + ") TO '" + sql(dir.resolve(name)) + "' (FORMAT parquet)");
                files.add(name);
            }
        }
        return files;
    }

    /**
     * T7 (LA-DAILY-INGEST-1, R-07): a 30-day backfill is ONE full build over the landed range, and it must answer every read like
     * the day-by-day run the scheduled Job performs (first day full, then an append per day, compacting at the delta cap before
     * the append - the {@code la.index.build} policy). 30 days cross the cap of {@link IndexPlan#MAX_DELTAS} three times.
     */
    @Test
    void aThirtyDayBackfillAnswersEveryReadLikeTheDayByDayRunAcrossThreeCompactions(@TempDir Path tmp) throws Exception {
        Path data = tmp.resolve("data");
        List<String> days = plantDays(data);
        IndexBuilder.Result backfill = buildOver(tmp.resolve("backfill"), data, IndexBuilder.Mode.FULL, days, null);

        Path daily = tmp.resolve("daily");
        IndexBuilder.Result live = buildOver(daily, data, IndexBuilder.Mode.FULL, days.subList(0, 1), null);
        int compactions = 0;
        for (int d = 2; d <= DAYS; d++) {
            if (live.manifest().deltas().size() >= IndexPlan.MAX_DELTAS) {
                live = buildOver(daily, data, IndexBuilder.Mode.COMPACT, days.subList(0, d - 1), null);
                compactions++;
            }
            live = buildOver(daily, data, IndexBuilder.Mode.APPEND, days.subList(0, d), null);
        }
        assertEquals(3, compactions);
        assertEquals(DAYS, live.manifest().inputFiles().size());
        assertEquals(backfill.manifest().inputFiles().stream().map(IndexManifest.InputFile::path).toList(),
                live.manifest().inputFiles().stream().map(IndexManifest.InputFile::path).toList());
        assertEquals(backfill.manifest().tables().get("out").rows(), live.manifest().tables().get("out").rows());
        assertEquals(backfill.manifest().droppedNull(), live.manifest().droppedNull());
        assertTrue(assertSameAnswers(backfill, live) > 150);

        // negative probe: a backfill that missed ONE day is a different index, and the parity check says so
        List<String> missing = new ArrayList<>(days);
        missing.remove(14);
        IndexBuilder.Result gap = buildOver(tmp.resolve("gap"), data, IndexBuilder.Mode.FULL, missing, null);
        assertTrue(gap.manifest().tables().get("out").rows() < live.manifest().tables().get("out").rows());
        IndexBuilder.Result last = live;
        assertThrows(AssertionError.class, () -> assertSameAnswers(gap, last));
    }

    private static void deleteTree(Path p) throws Exception {
        try (Stream<Path> w = Files.walk(p)) {
            for (Path x : (Iterable<Path>) w.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(x);
        }
    }
}
