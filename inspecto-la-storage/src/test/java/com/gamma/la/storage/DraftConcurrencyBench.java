package com.gamma.la.storage;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LA separation spike D-S5 (feasibility plan 7.10): do N concurrent Drafts, each owning its own DuckDB file over one
 * shared read-only sealed base, hold the D21 envelope (20 analysts, 50 concurrent Drafts)?
 *
 * <p>Self-contained plain JDBC: it touches neither IndexBuilder nor IndexReader. The shared base is a Parquet edge
 * file sorted by {@code src} (the shape the D-S1 spike measured), read through {@code read_parquet}; each Draft file
 * holds only that Draft's own exclusions. Never runs in the default suite (name is not {@code *Test}, and it is
 * tagged and gated): it needs {@code -Dinspecto.bench.dir=<dir>} (the Parquet and Draft files go there, outside
 * the repo). Optional knobs: {@code inspecto.bench.edges} (default 5000000), {@code .drafts} (50), {@code .active}
 * (20), {@code .seconds} (15 per phase), {@code .draftMemory} (256MB), {@code .draftThreads} (1). Results print as
 * {@code D-S5 ...} lines. Run: {@code mvn -o -pl inspecto-la-storage test -Dtest=DraftConcurrencyBench
 * -Dinspecto.bench.dir=<dir> -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class DraftConcurrencyBench {

    private static long num(String k, long d) {
        return Long.parseLong(System.getProperty("inspecto.bench." + k, Long.toString(d)));
    }

    private static final String JDBC = "jdbc:duckdb:";

    @Test
    void draftsOverSharedSealedBase() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir"));
        long edges = num("edges", 5_000_000);
        int drafts = (int) num("drafts", 50);
        int active = (int) num("active", 20);
        long seconds = num("seconds", 15);
        String mem = System.getProperty("inspecto.bench.draftMemory", "256MB");
        int threads = (int) num("draftThreads", 1);
        long nodes = Math.max(1000, edges / 5);
        Files.createDirectories(dir);
        Path base = dir.resolve("base-" + edges + ".parquet").toAbsolutePath();
        String basePath = base.toString().replace('\\', '/');

        if (!Files.exists(base)) {
            long t = System.nanoTime();
            try (Connection c = DriverManager.getConnection(JDBC); Statement s = c.createStatement()) {
                s.execute("COPY (SELECT CAST(floor(" + nodes + " * pow((hash(i*2) % 1000000) / 1e6, 3)) AS BIGINT) AS src,"
                        + " CAST(floor(" + nodes + " * pow((hash(i*2+1) % 1000000) / 1e6, 2)) AS BIGINT) AS dst, i AS ts"
                        + " FROM range(" + edges + ") t(i) ORDER BY src, ts) TO '" + basePath
                        + "' (FORMAT parquet, ROW_GROUP_SIZE 100000)");
            }
            System.out.printf("D-S5 base built: %,d edges, %,d bytes, %d ms%n", edges, Files.size(base),
                    (System.nanoTime() - t) / 1_000_000);
        }
        System.out.printf("D-S5 config: edges=%,d nodes=%,d drafts=%d active=%d seconds/phase=%d draftMemory=%s draftThreads=%d%n",
                edges, nodes, drafts, active, seconds, mem, threads);

        long commit0 = commitBytes();
        List<Connection> conns = new ArrayList<>();
        List<Long> openMs = new ArrayList<>();
        for (int i = 0; i < drafts; i++) {
            Path f = dir.resolve("draft-" + i + ".duckdb");
            Files.deleteIfExists(f);
            Files.deleteIfExists(dir.resolve("draft-" + i + ".duckdb.wal"));
            long t = System.nanoTime();
            Connection c = DriverManager.getConnection(JDBC + f.toAbsolutePath().toString().replace('\\', '/'));
            try (Statement s = c.createStatement()) {
                s.execute("SET memory_limit='" + mem + "'");
                s.execute("SET threads=" + threads);
                s.execute("CREATE TABLE exclusions(id BIGINT)");
                s.execute("INSERT INTO exclusions SELECT i FROM range(50) t(i)");
                // first touch of the shared base from this Draft (opens the Parquet metadata)
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM read_parquet('" + basePath + "') WHERE src = 7")) {
                    r.next();
                }
            }
            conns.add(c);
            openMs.add((System.nanoTime() - t) / 1_000_000);
        }
        long commit1 = commitBytes();
        long duck = 0;
        for (Connection c : conns) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT sum(memory_usage_bytes) FROM duckdb_memory()")) {
                r.next();
                duck += r.getLong(1);
            }
        }
        long[] o = openMs.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf("D-S5 open: %d Drafts, open+first-touch p50 %d ms, p95 %d ms, max %d ms%n", drafts, pct(o, 50), pct(o, 95), o[o.length - 1]);
        System.out.printf("D-S5 memory: process commit +%.1f MB for %d open Drafts (%.1f MB/Draft); DuckDB-tracked sum %.1f MB; Draft files on disk %.1f KB each%n",
                (commit1 - commit0) / 1048576.0, drafts, (commit1 - commit0) / 1048576.0 / drafts, duck / 1048576.0,
                Files.size(dir.resolve("draft-0.duckdb")) / 1024.0);

        // Phase 0: one analyst alone (no contention) - the baseline the others are compared with.
        long[] solo = run(conns.subList(0, 1), 1, seconds, nodes, basePath, false);
        System.out.printf("D-S5 read solo (1 active): n=%d p50 %d ms p95 %d ms%n", solo.length, pct(solo, 50), pct(solo, 95));
        // Phase 1: `active` analysts querying their own Draft at once; the other Drafts sit open and idle.
        long[] mid = run(conns, active, seconds, nodes, basePath, false);
        System.out.printf("D-S5 read contended (%d active of %d open): n=%d p50 %d ms p95 %d ms p99 %d ms%n", active, drafts, mid.length, pct(mid, 50), pct(mid, 95), pct(mid, 99));
        // Phase 2: as phase 1, plus one Draft running a heavy 5-hop recursive walk in a loop; light analysts' p95 is reported alone.
        long[] withHeavy = run(conns, active, seconds, nodes, basePath, true);
        System.out.printf("D-S5 read with one heavy job running: light n=%d p50 %d ms p95 %d ms p99 %d ms (vs %d / %d / %d without)%n",
                withHeavy.length, pct(withHeavy, 50), pct(withHeavy, 95), pct(withHeavy, 99), pct(mid, 50), pct(mid, 95), pct(mid, 99));
        for (Connection c : conns) {
            c.close();
        }
    }


    /**
     * Runs {@code workers} threads for {@code seconds}; worker w owns conns[w]. Light query = one-hop lookup of a
     * pseudo-random key with the Draft's own exclusions anti-joined. With {@code heavy}, worker 0 instead loops a
     * 5-level recursive walk from a high-degree start (not counted in the returned latencies).
     */
    private static long[] run(List<Connection> conns, int workers, long seconds, long nodes, String basePath,
                              boolean heavy) throws Exception {
        List<List<Long>> lats = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(workers);
        AtomicBoolean stop = new AtomicBoolean();
        List<Thread> ts = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            List<Long> mine = new ArrayList<>();
            lats.add(mine);
            final int wi = w;
            Thread th = new Thread(() -> {
                long seed = 12345 + wi * 7919L;
                try (Statement s = conns.get(wi).createStatement()) {
                    while (!stop.get()) {
                        seed = seed * 6364136223846793005L + 1442695040888963407L;
                        if (heavy && wi == 0) {
                            try (ResultSet r = s.executeQuery("WITH RECURSIVE w(n, d) AS (SELECT CAST(" + (nodes / 10) + " AS BIGINT), 0 UNION ALL "
                                    + "SELECT e.dst, w.d + 1 FROM w JOIN read_parquet('" + basePath + "') e ON e.src = w.n WHERE w.d < 5) "
                                    + "SELECT count(*) FROM w")) {
                                r.next();
                            }
                            continue;
                        }
                        long key = Math.floorMod(seed >>> 17, nodes);
                        long t = System.nanoTime();
                        try (ResultSet r = s.executeQuery("SELECT count(*) FROM read_parquet('" + basePath + "') e "
                                + "WHERE e.src = " + key + " AND e.dst NOT IN (SELECT id FROM exclusions)")) {
                            r.next();
                        }
                        mine.add((System.nanoTime() - t) / 1_000_000);
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    done.countDown();
                }
            });
            ts.add(th);
            th.start();
        }
        Thread.sleep(seconds * 1000);
        stop.set(true);
        done.await();
        List<Long> all = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            if (heavy && w == 0) {
                continue;
            }
            all.addAll(lats.get(w));
        }
        long[] a = all.stream().mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        return a;
    }

    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) {
            return -1;
        }
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
    }

    /** Windows commit charge (private bytes) of this process; the closest portable-in-JDK figure to resident memory. */
    private static long commitBytes() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getCommittedVirtualMemorySize();
    }
}
