package com.gamma.la.storage;

import com.gamma.la.storage.IndexReader.Side;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * LA-INDEX-SCALE-MEASURE-1 harness: (1) the K = 8 delta cap (lookup latency against 0..8 appended delta files per bucket, and
 * after compaction), (2) one hop and a depth-2 walk through the real reader at scale, (3) build time and disk size per scale
 * (the 10^9 extrapolation). Never runs in the default suite (not {@code *Test}, tagged and gated).
 *
 * <p>Needs {@code -Dinspecto.bench.dir=<dir>} (parquet inputs and indexes go there, outside the repo). Knobs
 * {@code inspecto.bench.*}: {@code idxDir} (index parent, default = dir; used by tools/bench-duckdb.ps1 for a per-label index), {@code edges} (10000000), {@code deltaEdges} (100000 per appended file), {@code maxDeltas} (8),
 * {@code buildMemory} (8GB), {@code samples} (60 keys per series), {@code skipDeltas} (false). Results print as
 * {@code SCALE ...} lines. Run: {@code mvn -o -pl inspecto-la-storage test -Dtest=IndexScaleBench
 * -Dinspecto.bench.dir=<dir> -Dinspecto.bench.edges=10000000 -Dsurefire.failIfNoSpecifiedTests=false}.
 *
 * <p>Corpus: the D-S5 skew (source ~ nodes * u^3, target ~ nodes * u^2, nodes = edges / 5), written as parquet files of at most
 * 5M rows so the build reads a real file list, and so deltas are real appended files.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class IndexScaleBench {

    private static final String DS = "g";
    private static final IndexMapping M = new IndexMapping("s", "t", "k", "ts", null, null, List.of());
    private static final long FILE_ROWS = 5_000_000;

    private static long num(String k, long d) {
        return Long.parseLong(System.getProperty("inspecto.bench." + k, Long.toString(d)));
    }

    private static String sql(Path p) {
        return p.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
    }

    /** Writes {@code rows} edges as parquet files [first, ...) under dir, ids offset by {@code seedOffset}; returns the file names. */
    private static List<String> plant(Path dir, String prefix, long rows, long nodes, long seedOffset) throws Exception {
        Files.createDirectories(dir);
        List<String> names = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SET TimeZone = 'UTC'");
            st.execute("SET memory_limit = '4GB'");
            for (long from = 0, k = 0; from < rows; from += FILE_ROWS, k++) {
                long to = Math.min(rows, from + FILE_ROWS);
                String name = prefix + k + ".parquet";
                st.execute("COPY (SELECT 'n' || CAST(floor(" + nodes + " * pow((hash((i+" + seedOffset + ")*2) % 1000000) / 1e6, 3)) AS BIGINT) AS s,"
                        + " 'n' || CAST(floor(" + nodes + " * pow((hash((i+" + seedOffset + ")*2+1) % 1000000) / 1e6, 2)) AS BIGINT) AS t,"
                        + " 'call' AS k, TIMESTAMP '2026-01-01' + to_seconds(i + " + seedOffset + ") AS ts FROM range(" + from + ", " + to + ") r(i)) TO '"
                        + sql(dir.resolve(name)) + "' (FORMAT parquet)");
                names.add(name);
            }
        }
        return names;
    }

    private static String relation(Path dir, List<String> files) {
        StringBuilder sb = new StringBuilder("SELECT * FROM read_parquet([");
        for (int i = 0; i < files.size(); i++) sb.append(i == 0 ? "'" : ", '").append(sql(dir.resolve(files.get(i)))).append('\'');
        return sb.append("])").toString();
    }

    private static List<IndexManifest.InputFile> stamps(Path dir, List<String> files) throws Exception {
        List<IndexManifest.InputFile> out = new ArrayList<>();
        for (String f : files)
            out.add(new IndexManifest.InputFile(f, Files.size(dir.resolve(f)), Files.getLastModifiedTime(dir.resolve(f)).toMillis()));
        return out;
    }

    private static long dirBytes(Path d) throws Exception {
        try (var w = Files.walk(d)) {
            return w.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }

    private static IndexBuilder.Result build(Path data, IndexStore store, IndexBuilder.Mode mode, List<String> files, String memory) throws Exception {
        Function<List<String>, String> delta = added -> relation(data, added);
        return IndexBuilder.build(new IndexBuilder.Request(DS, M, relation(data, files), store, "fp-" + files.size(),
                new IndexBuilder.Options(null, memory, null, null, null), stamps(data, files), mode, delta, List.of(data)));
    }

    private static String pct(long[] ns) {
        long[] s = ns.clone();
        Arrays.sort(s);
        return String.format("p50 %.2f ms  p95 %.2f ms  max %.2f ms", s[s.length / 2] / 1e6, s[(int) (s.length * 0.95)] / 1e6, s[s.length - 1] / 1e6);
    }

    /** Times one hop (edges() of one key, OUT) and a depth-2 directed walk over {@code samples} random cold/warm start nodes. */
    private static void latency(String label, IndexBuilder.Result r, long nodes, int samples) throws Exception {
        try (IndexReader rd = IndexReader.open(r.directory(), r.manifest(), SqlSandboxPolicy.defaultPolicy())) {
            Random rnd = new Random(7);
            // warm the reader once (extension load, first parquet metadata)
            rd.edges(List.of("n1"), List.of(Side.OUT), null, 1000);
            long[] hop = new long[samples], hop20 = new long[samples], walk = new long[samples], hub = new long[samples];
            int over = 0, ran = 0;
            for (int i = 0; i < samples; i++) {
                String key = "n" + (long) (rnd.nextDouble() * nodes);
                long t = System.nanoTime();
                rd.edges(List.of(key), List.of(Side.OUT, Side.IN), null, 1000);
                hop[i] = System.nanoTime() - t;
                List<String> twenty = new ArrayList<>();
                for (int j = 0; j < 20; j++) twenty.add("n" + (long) (rnd.nextDouble() * nodes));
                t = System.nanoTime();
                rd.edges(twenty, List.of(Side.OUT, Side.IN), null, 1000);
                hop20[i] = System.nanoTime() - t;
                t = System.nanoTime();
                rd.edges(List.of("n" + rnd.nextInt(20)), List.of(Side.OUT, Side.IN), null, 1000);   // hubs: the lowest source ids
                hub[i] = System.nanoTime() - t;
                IndexedTraversal.Params p = new IndexedTraversal.Params(key, null, true, 2, 1_000_000, 100_000, false, false, null, null, null);
                t = System.nanoTime();
                try {
                    IndexedTraversal.walk(rd, p);
                    ran++;
                } catch (IndexedTraversal.FrontierOverCap e) {
                    over++;
                }
                walk[i] = System.nanoTime() - t;
            }
            System.out.printf("SCALE %s  one-hop (1 key)   %s%n", label, pct(hop));
            System.out.printf("SCALE %s  one-hop (20 keys) %s%n", label, pct(hop20));
            System.out.printf("SCALE %s  hub key (n0..n19) %s%n", label, pct(hub));
            System.out.printf("SCALE %s  depth-2 walk (undirected, %d served, %d over the 20-key cap) %s%n", label, ran, over, pct(walk));
        }
    }

    @Test
    void measure() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath();
        long edges = num("edges", 10_000_000);
        long nodes = Math.max(1000, edges / 5);
        int samples = (int) num("samples", 60);
        String mem = System.getProperty("inspecto.bench.buildMemory", "8GB");
        Path data = dir.resolve("data-" + edges);
        Path idx = System.getProperty("inspecto.bench.idxDir") != null ? Path.of(System.getProperty("inspecto.bench.idxDir")).toAbsolutePath().resolve("idx-" + edges) : dir.resolve("idx-" + edges);
        boolean fresh = !Files.isDirectory(data);
        long t0 = System.nanoTime();
        List<String> files;
        if (fresh) files = plant(data, "base", edges, nodes, 0);
        else try (var s = Files.list(data)) { files = s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("base")).sorted().toList(); }
        System.out.printf("SCALE edges=%,d nodes=%,d files=%d inputs %s (%.0f s)%n", edges, nodes, files.size(), fresh ? "generated" : "reused",
                (System.nanoTime() - t0) / 1e9);

        IndexStore store = new IndexStore(idx, DS, M.hash());
        IndexBuilder.Result base;
        if (store.current().isEmpty()) {
            long b = System.nanoTime();
            base = build(data, store, IndexBuilder.Mode.FULL, files, mem);
            System.out.printf("SCALE edges=%,d FULL build %.0f s, %d buckets, %.2f GB index, %,d edges kept%n", edges, (System.nanoTime() - b) / 1e9,
                    base.manifest().buckets(), dirBytes(base.directory()) / 1e9, base.edges());
        } else {
            Path v = store.current().get();
            base = new IndexBuilder.Result(0, v, IndexManifest.read(v), 0, 0, 0, 0, 0, java.util.Map.of(), 0);
            System.out.println("SCALE edges=" + edges + " index reused " + v);
        }
        latency("N=" + edges + " base (0 deltas)", base, nodes, samples);
        if (Boolean.getBoolean("inspecto.bench.skipDeltas")) return;

        // K = 8: append one delta file at a time, measure after 1, 2, 4, 6, 8 (the cap), then compact
        long deltaEdges = num("deltaEdges", 100_000);
        int maxDeltas = (int) num("maxDeltas", IndexPlan.MAX_DELTAS);
        List<String> all = new ArrayList<>(files);
        IndexBuilder.Result cur = base;
        for (int d = 1; d <= maxDeltas; d++) {
            List<String> added = plant(data, "delta" + d + "_", deltaEdges, nodes, 1_000_000_000L * d);
            all.addAll(added);
            long b = System.nanoTime();
            cur = build(data, store, IndexBuilder.Mode.APPEND, all, mem);
            System.out.printf("SCALE N=%,d APPEND %d (+%,d edges) %.1f s, deltas in manifest=%d%n", edges, d, deltaEdges, (System.nanoTime() - b) / 1e9,
                    cur.manifest().deltas().size());
            if (d == 1 || d == 2 || d == 4 || d == 6 || d == maxDeltas) latency("N=" + edges + " deltas=" + d, cur, nodes, samples);
        }
        if (Boolean.getBoolean("inspecto.bench.skipCompact")) return;
        long b = System.nanoTime();
        IndexBuilder.Result compact = build(data, store, IndexBuilder.Mode.COMPACT, all, mem);
        System.out.printf("SCALE N=%,d COMPACT %.1f s%n", edges, (System.nanoTime() - b) / 1e9);
        latency("N=" + edges + " compacted", compact, nodes, samples);
    }
}
