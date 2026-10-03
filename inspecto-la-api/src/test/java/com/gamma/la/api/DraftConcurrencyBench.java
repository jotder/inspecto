package com.gamma.la.api;

import com.gamma.control.ApiException;
import com.gamma.control.ApiExceptionPeek;
import com.gamma.la.core.DraftCheckpoints;
import com.gamma.la.core.DraftLifecycle;
import com.gamma.la.core.DraftStore;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.SnapshotStore;
import com.gamma.la.storage.IndexBuilder;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexReader;
import com.gamma.la.storage.IndexStore;
import com.gamma.sql.SqlSandboxPolicy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.gamma.la.core.InvestigationEvaluator.canonical;

/**
 * LA separation spike D-S5 re-run on REAL Draft state (D-7 step D7-7, feasibility plan 7.10.2 / 7.10.3).
 *
 * <p>The first D-S5 run (2026-10-03) used plain JDBC and one DuckDB file per Draft. D7-6 dropped that file: a Draft is its
 * log + {@code sets/} + pins on disk, its checkpointed state in memory ({@code DraftCheckpoints}), and it reads the SHARED
 * pooled sealed index ({@code IndexReader.borrow}). This bench drives exactly that: a real partitioned index built by
 * {@code IndexBuilder}, a real Investigation in a {@code SnapshotStore}, Drafts opened through the D21 cap
 * ({@code DraftAdmission.requireRoom}) with a version pin, analysts that read one hop from the index and append sealed
 * {@code expand} ops through the checkpoint, heavy multi-hop walks under {@code DraftAdmission.heavy}, hibernate /
 * rehydrate ({@code DraftLifecycle} + the cache eviction), and the atomic promote ({@code DraftPromote.execute}). It lives
 * in {@code inspecto-la-api} because those seams do (moved from {@code inspecto-la-storage}, which cannot see them).
 *
 * <p>Never runs in the default suite (name is not {@code *Test}, tagged and gated): it needs {@code -Dinspecto.bench.dir=<dir>}
 * (the index and the Space go there, outside the repo; an index already built for the same edge count is reused). Knobs
 * {@code inspecto.bench.*}: {@code edges} (5000000), {@code drafts} (50), {@code active} (20), {@code seconds} (15 per phase),
 * {@code thinkMs} (2000 mean think time of the think-time phase), {@code longSteps} (500, the long Draft for rehydrate and
 * promote), {@code buildMemory} (8GB). Results print as {@code D-S5R ...} lines. Run: {@code mvn -o -pl inspecto-la-api test
 * -Dtest=DraftConcurrencyBench -Dinspecto.bench.dir=<dir> -Dsurefire.failIfNoSpecifiedTests=false}.
 */
@Tag("bench")
@EnabledIfSystemProperty(named = "inspecto.bench.dir", matches = ".+")
class DraftConcurrencyBench {

    private static long num(String k, long d) {
        return Long.parseLong(System.getProperty("inspecto.bench." + k, Long.toString(d)));
    }

    private static final String DATASET = "g";
    private static final String INV = "bench" + System.currentTimeMillis();   // fresh per run: no delete-then-reuse of a tree

    private Path versionDir;
    private IndexManifest manifest;
    private long nodes;
    private SnapshotStore store;
    private InvestigationRoutes.Inv main;
    private List<Map<String, Object>> mainEntries;
    private final AtomicLong heavyRan = new AtomicLong(), heavyRefused = new AtomicLong();

    @Test
    void realDraftsOverSharedPartitionedIndex() throws Exception {
        Path dir = Path.of(System.getProperty("inspecto.bench.dir")).toAbsolutePath();
        long edges = num("edges", 5_000_000);
        int drafts = (int) num("drafts", 50);
        int active = (int) num("active", 20);
        long seconds = num("seconds", 15);
        long thinkMs = num("thinkMs", 2000);
        int longSteps = (int) num("longSteps", 500);
        nodes = Math.max(1000, edges / 5);
        Path writeRoot = dir.resolve("space-" + edges);
        Files.createDirectories(writeRoot);
        DraftLifecycle.maxOpenDrafts = drafts;

        // ── the shared index (real IndexBuilder output, partitioned into buckets) ──
        IndexMapping m = new IndexMapping("s", "t", "k", "ts", null, null, List.of());
        IndexStore index = new IndexStore(writeRoot.resolve(IndexRoutes.INDEX_DIR), DATASET, m.hash());
        if (index.current().isEmpty()) {
            String rel = "SELECT 'n' || CAST(floor(" + nodes + " * pow((hash(i*2) % 1000000) / 1e6, 3)) AS BIGINT) AS s,"
                    + " 'n' || CAST(floor(" + nodes + " * pow((hash(i*2+1) % 1000000) / 1e6, 2)) AS BIGINT) AS t,"
                    + " 'call' AS k, TIMESTAMP '2026-01-01' + to_seconds(i) AS ts FROM range(" + edges + ") r(i)";
            long t = System.nanoTime();
            IndexBuilder.Result r = IndexBuilder.build(new IndexBuilder.Request(DATASET, m, rel, index, "bench-" + edges,
                    new IndexBuilder.Options(null, System.getProperty("inspecto.bench.buildMemory", "8GB"), null, null, null)));
            System.out.printf("D-S5R index built: %,d edges, %d buckets, %.1f GB on disk, %d s%n", r.edges(), r.manifest().buckets(),
                    dirBytes(r.directory()) / 1e9, (System.nanoTime() - t) / 1_000_000_000);
        }
        versionDir = index.current().orElseThrow();
        manifest = IndexManifest.read(versionDir);
        long version = manifest.version();

        // ── a fresh Investigation with a 20-step main log ──
        store = new SnapshotStore(writeRoot);
        Path prior = store.investigationDir(INV).getParent();   // an earlier run's Drafts would hold the Space's seats
        if (Files.isDirectory(prior))
            try (var w = Files.walk(prior)) {
                for (Path f : w.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
            }
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", INV);
        header.put("dataset", DATASET);
        header.put("sourceCol", "s");
        header.put("targetCol", "t");
        store.createInvestigation(INV, canonical(header));
        main = new InvestigationRoutes.Inv(store, writeRoot, INV, header);
        InvestigationEvaluator.State ms = new InvestigationEvaluator.State();
        for (int i = 1; i <= 20; i++) {
            Map<String, Object> e = op(i, "seed", Map.of("ids", List.of("n" + (i * 7))));
            InvestigationEvaluator.apply(ms, e);
            e.put("workingSetHash", ms.hash());
            store.appendStep(INV, i, canonical(e), canonical(InvestigationRoutes.setDoc(i, ms)));
        }
        mainEntries = DraftRebase.parseAll(store.readLog(INV));
        String baseHash = DraftStore.prefixHash(store.readLog(INV), mainEntries.size());
        System.out.printf("D-S5R config: edges=%,d nodes=%,d buckets=%d drafts=%d active=%d seconds/phase=%d thinkMs=%d heavyLimit=%d cores=%d%n",
                edges, nodes, manifest.buckets(), drafts, active, seconds, thinkMs, DraftAdmission.heavyLimit(),
                Runtime.getRuntime().availableProcessors());

        // ── open: cap check + seat + pin + base fold + first index touch ──
        long heap0 = heap(), commit0 = commit();
        List<Path> dirs = new ArrayList<>();
        long[] openMs = new long[drafts];
        for (int i = 0; i < drafts; i++) {
            long t = System.nanoTime();
            dirs.add(fork(m, version, baseHash));
            openMs[i] = (System.nanoTime() - t) / 1_000_000;
        }
        long heap1 = heap(), commit1 = commit();
        Arrays.sort(openMs);
        System.out.printf("D-S5R open: %d Drafts, open p50 %d ms p95 %d ms max %d ms%n", drafts, pct(openMs, 50),
                pct(openMs, 95), openMs[drafts - 1]);
        try {
            fork(m, version, baseHash);
            System.out.println("D-S5R cap: FAIL - fork " + (drafts + 1) + " was admitted");
        } catch (ApiException e) {
            System.out.printf("D-S5R cap: fork %d refused %d %s%n", drafts + 1, ApiExceptionPeek.status(e), ApiExceptionPeek.code(e));
        }
        System.out.printf("D-S5R memory at open: heap +%.2f MB (%.3f MB/Draft), process commit +%.1f MB (%.2f MB/Draft)%n",
                (heap1 - heap0) / 1048576.0, (heap1 - heap0) / 1048576.0 / drafts, (commit1 - commit0) / 1048576.0,
                (commit1 - commit0) / 1048576.0 / drafts);

        // ── light phases ──
        Result solo = run(dirs, 1, 0, seconds, 0, 0);
        report("solo (1 active, closed loop)", solo);
        Result mid = run(dirs, active, 0, seconds, 0, 0);
        report(active + " active of " + drafts + " open, closed loop", mid);
        Result think = run(dirs, active, 0, seconds, thinkMs, 0);
        report(active + " active, think " + thinkMs + " ms mean", think);
        int heavyThreads = DraftAdmission.heavyLimit() + 2;
        Result heavy = run(dirs, active, heavyThreads, seconds, 0, 0);
        report(active + " active + " + heavyThreads + " heavy callers (limit " + DraftAdmission.heavyLimit() + ")", heavy);
        System.out.printf("D-S5R heavy: %d 3-hop walks ran, %d refused 429 (p50 walk %d ms)%n", heavyRan.get(), heavyRefused.get(), pct(heavy.heavyMs, 50));
        Result heavyThink = run(dirs, active, heavyThreads, seconds, thinkMs, 0);
        report(active + " active think " + thinkMs + " ms + heavy saturated", heavyThink);
        long heap2 = heap();
        long steps = 0;
        for (Path d : dirs) steps += SnapshotStore.readLogAt(d).size();
        System.out.printf("D-S5R memory after the phases: %d own steps over %d Drafts, heap +%.2f MB over the pre-open heap (%.3f MB/Draft)%n",
                steps, drafts, (heap2 - heap0) / 1048576.0, (heap2 - heap0) / 1048576.0 / drafts);

        // ── the long Draft: longSteps appends, then hibernate / rehydrate / promote ──
        Path longDir = dirs.get(0);
        if (SnapshotStore.readLogAt(longDir).size() < longSteps) run(List.of(longDir), 1, 0, 0, 0, longSteps);

        // ── hibernate every Draft, then rehydrate each with its one cold fold ──
        Map<Path, String> before = new LinkedHashMap<>();
        for (Path d : dirs) before.put(d, stateOf(d).hash());
        long heapH0 = heap();
        long[] hib = new long[drafts];
        for (int i = 0; i < drafts; i++) {
            long t = System.nanoTime();
            DraftLifecycle.hibernate(dirs.get(i));
            DraftAdmission.evictCaches(dirs.get(i));
            hib[i] = (System.nanoTime() - t) / 1000;
        }
        long heapH1 = heap();
        long[] reh = new long[drafts];
        int mismatch = 0;
        long longReh = 0;
        for (int i = 0; i < drafts; i++) {
            Path d = dirs.get(i);
            long t = System.nanoTime();
            DraftLifecycle.rehydrate(d);
            String h = stateOf(d).hash();
            reh[i] = (System.nanoTime() - t) / 1_000_000;
            if (d.equals(longDir)) longReh = reh[i];
            if (!h.equals(before.get(d))) mismatch++;
        }
        Arrays.sort(hib);
        Arrays.sort(reh);
        System.out.printf("D-S5R hibernate: p50 %d us max %d us; heap released %.2f MB for %d Drafts%n", pct(hib, 50), hib[drafts - 1],
                (heapH0 - heapH1) / 1048576.0, drafts);
        System.out.printf("D-S5R rehydrate (cold fold of main + own log): p50 %d ms max %d ms; the %d-step Draft %d ms; state mismatches %d%n",
                pct(reh, 50), reh[drafts - 1], SnapshotStore.readLogAt(longDir).size(), longReh, mismatch);

        // ── promote the long Draft (re-fold equivalence + append to main + marker + unpin) ──
        String longId = longDir.getFileName().toString();
        long t = System.nanoTime();
        Map<String, Object> out = DraftPromote.execute(main, longId, "bench", Map.of("approvedBy", "bench-lead"), null);
        System.out.printf("D-S5R promote: %s steps in %d ms (main %s -> %s)%n", out.get("steps"), (System.nanoTime() - t) / 1_000_000,
                out.get("fromStep"), out.get("toStep"));
        IndexReader.evictAll();
    }

    // ── one Draft fork, as DraftRoutes does it minus HTTP: cap, seat, pin, base fold, first index touch ──

    private Path fork(IndexMapping m, long version, String baseHash) throws Exception {
        String id = DraftStore.newId();
        Path d;
        synchronized (DraftAdmission.CAP) {
            DraftAdmission.requireRoom(main);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("draftId", id);
            h.put("investigationId", INV);
            h.put("actor", "analyst-" + id.substring(6, 10));
            h.put("createdAt", java.time.Instant.now().toString());
            h.put("baseStep", mainEntries.size());
            h.put("baseLogHash", baseHash);
            h.put("pins", Map.of("indexes", List.of(Map.of("mappingHash", m.hash(), "version", version))));
            // DraftStore.create retries a transient Windows rename denial itself (DraftStore.moveRetrying).
            if (!DraftStore.create(main.dir(), id, canonical(h))) throw new IllegalStateException("fork failed");
            d = DraftStore.draftDir(main.dir(), id);
        }
        new IndexStore(main.writeRoot().resolve(IndexRoutes.INDEX_DIR), DATASET, m.hash()).pins().pin(version, id);
        DraftLifecycle.touch(d);
        stateOf(d);
        try (IndexReader r = IndexReader.borrow(versionDir, manifest, SqlSandboxPolicy.defaultPolicy())) {
            r.degree("n7");
        }
        return d;
    }

    private InvestigationEvaluator.State stateOf(Path d) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>(mainEntries);
        all.addAll(DraftRebase.parseAll(SnapshotStore.readLogAt(d)));
        return DraftCheckpoints.stateOf(d, all);
    }

    /** Append one op to Draft {@code d} the checkpointed way (D7-4): one apply on the cached state, one append, remember. */
    private void append(Path d, Map<String, Object> entry) throws Exception {
        synchronized (InvestigationRoutes.lock(d)) {
            List<Map<String, Object>> all = new ArrayList<>(mainEntries);
            all.addAll(DraftRebase.parseAll(SnapshotStore.readLogAt(d)));
            InvestigationEvaluator.State s = DraftCheckpoints.stateOf(d, all);
            int step = all.size() + 1;
            entry.put("step", step);
            InvestigationEvaluator.State next = DraftCheckpoints.after(s, all, entry);
            entry.put("workingSetHash", next.hash());
            DraftStore.appendStep(d, step, canonical(entry), canonical(InvestigationRoutes.setDoc(step, next)));
            DraftCheckpoints.remember(d, all.size() + 1, next);
            DraftLifecycle.touch(d);
        }
    }

    private static Map<String, Object> op(int step, String op, Map<String, Object> params) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("step", step);
        e.put("kind", "op");
        e.put("op", op);
        e.put("params", params);
        return e;
    }

    // ── the workload ──

    private record Result(long[] readMs, long[] appendMs, long[] heavyMs) { }

    /**
     * {@code light} analysts (worker w owns Draft w) loop: think (exponential, mean {@code thinkMs}; 0 = closed loop), read one
     * hop of a pseudo-random key from the pooled index, append it as a sealed {@code expand} op. {@code heavy} extra threads
     * loop a 3-hop walk under {@code DraftAdmission.heavy}. Runs {@code seconds}, or (seconds 0) until worker 0 did
     * {@code appends} appends.
     */
    private Result run(List<Path> dirs, int light, int heavy, long seconds, long thinkMs, int appends) throws Exception {
        List<List<Long>> reads = new ArrayList<>(), apps = new ArrayList<>(), heavies = new ArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(light + heavy);
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int w = 0; w < light + heavy; w++) {
            List<Long> rd = new ArrayList<>(), ap = new ArrayList<>(), hv = new ArrayList<>();
            reads.add(rd);
            apps.add(ap);
            heavies.add(hv);
            final int wi = w;
            Thread th = new Thread(() -> {
                long seed = 12345 + wi * 7919L;
                java.util.Random rnd = new java.util.Random(seed);
                try {
                    while (!stop.get()) {
                        seed = seed * 6364136223846793005L + 1442695040888963407L;
                        String key = "n" + Math.floorMod(seed >>> 17, Math.max(1, nodes / 50));   // the dense head of the power law
                        if (wi >= light) {
                            long t = System.nanoTime();
                            try {
                                DraftAdmission.heavy("bench 3-hop walk", () -> walk(key, 3));
                                heavyRan.incrementAndGet();
                                hv.add((System.nanoTime() - t) / 1_000_000);
                            } catch (ApiException refused) {
                                heavyRefused.incrementAndGet();
                                Thread.sleep(50);
                            }
                            continue;
                        }
                        if (thinkMs > 0) Thread.sleep((long) (-thinkMs * Math.log(1 - rnd.nextDouble())));
                        long t = System.nanoTime();
                        List<IndexReader.Folded> hop;
                        try (IndexReader r = IndexReader.borrow(versionDir, manifest, SqlSandboxPolicy.defaultPolicy())) {
                            hop = r.fold(key, IndexReader.Side.OUT, List.of("kind"), null, null);
                        }
                        rd.add((System.nanoTime() - t) / 1_000_000);
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (IndexReader.Folded f : hop.subList(0, Math.min(50, hop.size())))
                            rows.add(Map.of("source", f.source(), "target", f.target(), "kind", f.extras().get(0), "count", f.count()));
                        Map<String, Object> e = op(0, "expand", new LinkedHashMap<>(Map.of("frontier", List.of(key), "hops", 1, "budget", 50, "maxFanOut", 50)));
                        e.put("read", Map.of("query", Map.of("frontier", List.of(key)), "rows", rows));
                        Path d = dirs.get(wi);
                        // the frontier must be in the Working Set for the rows to admit: seed it first when it is not
                        if (!stateOf(d).entities.containsKey(key)) append(d, op(0, "seed", new LinkedHashMap<>(Map.of("ids", List.of(key)))));
                        t = System.nanoTime();
                        append(d, e);
                        ap.add((System.nanoTime() - t) / 1_000_000);
                        if (appends > 0 && wi == 0 && SnapshotStore.readLogAt(d).size() >= appends) stop.set(true);
                    }
                } catch (Throwable e) {
                    errors.add(e);
                    stop.set(true);
                } finally {
                    done.countDown();
                }
            });
            th.start();
        }
        if (seconds > 0) {
            long end = System.nanoTime() + seconds * 1_000_000_000L;
            while (!stop.get() && System.nanoTime() < end) Thread.sleep(100);
            stop.set(true);
        }
        done.await();
        if (!errors.isEmpty()) throw new IllegalStateException("bench worker failed", errors.get(0));
        return new Result(flat(reads), flat(apps), flat(heavies));
    }

    /** A heavy job: a breadth-first walk of {@code hops} hops through the pooled index, frontier capped at 500 keys, 50 edges per key. */
    private int walk(String start, int hops) throws java.io.IOException {
        try (IndexReader r = IndexReader.borrow(versionDir, manifest, SqlSandboxPolicy.defaultPolicy())) {
            java.util.Set<String> seen = new java.util.HashSet<>(List.of(start));
            List<String> frontier = List.of(start);
            for (int h = 0; h < hops && !frontier.isEmpty(); h++) {
                List<String> next = new ArrayList<>();
                for (List<IndexReader.Edge> es : r.edges(frontier, List.of(IndexReader.Side.OUT), null, 50).values())
                    for (IndexReader.Edge e : es)
                        if (seen.add(e.neighbour()) && next.size() < 500) next.add(e.neighbour());
                frontier = next;
            }
            return seen.size();
        } catch (java.sql.SQLException e) {
            throw new java.io.IOException(e);
        }
    }

    // ── helpers ──

    private static void report(String what, Result r) {
        System.out.printf("D-S5R one-hop read, %s: n=%d p50 %d ms p95 %d ms p99 %d ms | append n=%d p50 %d ms p95 %d ms%n", what,
                r.readMs.length, pct(r.readMs, 50), pct(r.readMs, 95), pct(r.readMs, 99), r.appendMs.length, pct(r.appendMs, 50),
                pct(r.appendMs, 95));
    }

    private static long[] flat(List<List<Long>> lists) {
        long[] a = lists.stream().flatMap(List::stream).mapToLong(Long::longValue).toArray();
        Arrays.sort(a);
        return a;
    }

    private static long pct(long[] sorted, int p) {
        if (sorted.length == 0) return -1;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
    }

    private static long heap() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    /** Windows commit charge (private bytes) of this process. */
    private static long commit() {
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return os.getCommittedVirtualMemorySize();
    }

    private static long dirBytes(Path p) throws java.io.IOException {
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
    }
}
