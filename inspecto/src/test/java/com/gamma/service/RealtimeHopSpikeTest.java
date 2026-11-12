package com.gamma.service;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PA-9a spike (PRODUCT-ASSEMBLY-1, D-PA38/D-PA39, gate G-RT): how long from an event's arrival to its Alert when a
 * windowed condition is evaluated on each commit of a {@code trigger: stream} Pipeline — through TODAY's lane and
 * ingest, with no product code changed.
 *
 * <p>Opt-in: it skips unless {@code -Drealtime.spike=true} (it runs for minutes and writes hundreds of MB).
 * Knobs: {@code -Drealtime.spike.rates=1000,5000,20000} events/s, {@code -Drealtime.spike.seconds=60} per rate,
 * {@code periodMs} (partitions), {@code seedSeconds} (history), {@code batchMaxFiles} and {@code mode=glob|buffer}.
 * The measured matrix and the gate G-RT verdict are in the PA-9a note of
 * {@code docs/superpower/2026-11-10-product-assembly-plan.md} §11.
 *
 * <p>What it does, per rate R:
 * <ol>
 *   <li>Pre-seeds the output folder with {@code seedSeconds} of history, one Parquet file of R rows per second (the
 *       file shape a 1 s drain cadence leaves), so the window query reads a full window from the first commit.</li>
 *   <li>A writer lands one CSV every {@code periodMs} (A-number from 100k keys, B-number, duration, arrival ms) in
 *       the fake remote folder; every 5 s it plants a Wangiri burst (one A-number, 40 distinct B-numbers, under
 *       3 s each). The lane ({@code max_wait: 1s}) drains and the real ingest commits Parquet.</li>
 *   <li>The test thread stands in for the commit hook: on each new commit it reads the files committed since the
 *       last evaluation, runs the windowed query (count of distinct B-numbers per touched A-number over 10 minutes,
 *       duration under 3 s, threshold 30) over every committed file ({@code glob}) or over the window buffer
 *       ({@code buffer}), and raises each breach through {@link AlertService#raiseFromEvent} with the dedup key
 *       {@code rule:key:window}.</li>
 * </ol>
 * It prints arrival → evaluated latency for every event (p50/p95/p99), arrival → Alert for each planted burst, the
 * evaluation time per commit, the files written per hour, and the backlog left when the writer stops.
 */
class RealtimeHopSpikeTest {

    private static final long WINDOW_MS = 600_000;
    private static final int KEYS = 100_000;
    /**
     * How often the writer lands one file. A Kafka drain makes ONE slice per partition, so one file a second models
     * a one-partition topic drained at {@code max_wait: 1s}; {@code -Drealtime.spike.periodMs=250} models four.
     */
    private static final int PERIOD_MS = Integer.getInteger("realtime.spike.periodMs", 1000);
    /**
     * Seconds of pre-seeded history, one Parquet file per second. 600 fills the 10-minute window; more adds files
     * OUTSIDE the window, to measure what globbing every output file costs ({@code 3600} = one hour of drains).
     */
    private static final int SEED_SECONDS = Integer.getInteger("realtime.spike.seedSeconds", 600);
    /**
     * {@code processing.batch.max_files}: 1 (the engine default) makes each landed slice its own Consignment; a
     * higher cap lets one drain's partition slices commit as ONE Consignment.
     */
    private static final int BATCH_MAX_FILES = Integer.getInteger("realtime.spike.batchMaxFiles", 1);

    @Test
    void eventToAlertThroughTodaysLane(@TempDir Path root) throws Exception {
        assumeTrue(Boolean.getBoolean("realtime.spike"), "PA-9a spike: run with -Drealtime.spike=true");
        String rates = System.getProperty("realtime.spike.rates", "1000,5000,20000");
        int seconds = Integer.getInteger("realtime.spike.seconds", 60);
        for (String r : rates.split(",")) runAtRate(Files.createDirectories(root.resolve("r" + r.trim())),
                Integer.parseInt(r.trim()), seconds);
    }

    private void runAtRate(Path dir, int rate, int seconds) throws Exception {
        Path remote = Files.createDirectories(dir.resolve("remote"));
        Path db = Files.createDirectories(dir.resolve("db"));
        FakeRemoteConnectorFactory.reset(remote);
        long t0 = System.currentTimeMillis();
        seedHistory(db, rate, t0);
        long seeded = System.currentTimeMillis() - t0;

        Path cfg = pipeline(dir);
        CollectorService svc = new CollectorService(List.of(cfg), 3600, 1);
        AlertService alerts = alertService(cfg);
        Map<String, Long> planted = new ConcurrentHashMap<>();   // key -> arrival ms
        Map<String, Long> alerted = new ConcurrentHashMap<>();   // key -> Alert raised ms
        TreeMap<Long, Long> latencyBuckets = new TreeMap<>();    // 50 ms bucket -> events
        List<Long> evalMs = new ArrayList<>();
        AtomicBoolean writing = new AtomicBoolean(true);
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:")) {
            // Committed files only: a bare "**" also matches db/.staging/<consignment>/…, which a concurrent
            // Consignment renames away mid-read ("Cannot open file", seen with four partitions).
            String d = db.toString().replace('\\', '/');
            String glob = "['" + d + "/year=*/**/*.parquet', '" + d + "/_seed/*.parquet']";
            long b0 = System.currentTimeMillis();
            if (BUFFER) {   // the window buffer: rebuilt at boot from the Dataset, then appended per commit
                List<String> existing;
                try (Stream<Path> w = Files.walk(db)) {   // every committed file at boot (a fresh Space has none)
                    existing = w.filter(p -> p.toString().endsWith(".parquet"))
                            .map(p -> "'" + p.toString().replace('\\', '/') + "'")
                            .filter(p -> !p.contains("/.staging/")).toList();
                }
                try (Statement st = duck.createStatement()) {
                    st.execute("CREATE TABLE buf AS SELECT A_NUM, B_NUM, DUR, ARRIVED_MS FROM read_parquet(["
                            + String.join(", ", existing) + "], union_by_name=true) WHERE ARRIVED_MS >= "
                            + (t0 - WINDOW_MS));
                }
            }
            long bufferBoot = System.currentTimeMillis() - b0;
            PipelineScheduler s = scheduler(svc);
            s.reconcileStreamLanes();
            Thread writer = Thread.ofVirtual().start(() -> write(remote, rate, seconds, planted, writing));
            Set<String> evaluated = new java.util.HashSet<>();
            long[] hi = {t0};
            int seen = 0;
            long until = System.currentTimeMillis() + seconds * 1000L + 15_000;
            while (System.currentTimeMillis() < until) {
                int committed = svc.pipelines().get(0).committedBatches();
                if (committed == seen) {
                    if (!writing.get() && committed > 0 && backlog(remote) == 0) break;
                    Thread.sleep(10);
                    continue;
                }
                seen = committed;
                long e0 = System.currentTimeMillis();
                if (evaluate(duck, db, glob, evaluated, hi, alerts, alerted, latencyBuckets))
                    evalMs.add(System.currentTimeMillis() - e0);
            }
            writer.join();
            report(rate, seconds, seeded, bufferBoot, latencyBuckets, planted, alerted, evalMs, db, remote, seen);
        } finally {
            svc.close();
        }
    }

    /** {@code -Drealtime.spike.mode=glob} (default) reads every committed file; {@code buffer} reads the window buffer. */
    private static final boolean BUFFER = "buffer".equals(System.getProperty("realtime.spike.mode", "glob"));

    /**
     * One commit's evaluation, scoped to the files committed since the last one (out-of-order commits of concurrent
     * Consignments make an arrival high-water mark skip rows). Returns false when no new file was committed.
     */
    private static boolean evaluate(Connection duck, Path db, String glob, Set<String> evaluated, long[] hi,
                                    AlertService alerts, Map<String, Long> alerted, TreeMap<Long, Long> buckets)
            throws Exception {
        List<String> fresh = new ArrayList<>();
        try (Stream<Path> w = Files.walk(db)) {
            w.filter(p -> p.toString().endsWith(".parquet"))
                    .map(p -> p.toString().replace('\\', '/'))
                    .filter(p -> !p.contains("/.staging/") && !p.contains("/_seed/") && !evaluated.contains(p))
                    .forEach(fresh::add);
        }
        if (fresh.isEmpty()) return false;
        evaluated.addAll(fresh);
        try (Statement st = duck.createStatement()) {
            st.execute("CREATE OR REPLACE TEMP TABLE fresh AS SELECT A_NUM, B_NUM, DUR, ARRIVED_MS FROM read_parquet(["
                    + String.join(", ", fresh.stream().map(f -> "'" + f + "'").toList()) + "], union_by_name=true)");
            try (ResultSet rs = st.executeQuery("SELECT max(ARRIVED_MS) FROM fresh")) {
                rs.next();
                hi[0] = Math.max(hi[0], rs.getLong(1));
            }
            String source = "read_parquet(" + glob + ", union_by_name=true)";
            if (BUFFER) {
                st.execute("INSERT INTO buf SELECT * FROM fresh");
                st.execute("DELETE FROM buf WHERE ARRIVED_MS < " + (hi[0] - WINDOW_MS));
                source = "buf";
            }
            String sql = "SELECT A_NUM, count(DISTINCT B_NUM) c FROM " + source
                    + " WHERE ARRIVED_MS >= ? AND ARRIVED_MS <= ? AND DUR < 3"
                    + " AND A_NUM IN (SELECT DISTINCT A_NUM FROM fresh) GROUP BY A_NUM HAVING count(DISTINCT B_NUM) >= 30";
            long hiMs = hi[0];
            try (PreparedStatement ps = duck.prepareStatement(sql)) {
                ps.setLong(1, hiMs - WINDOW_MS);
                ps.setLong(2, hiMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        long now = System.currentTimeMillis();
                        Alert a = new Alert("wangiri_burst", "CRITICAL", "LANE_ETL", "count_distinct(B_NUM)",
                                rs.getLong(2), "gte", 30, "10m", now, "Wangiri burst from " + key);
                        if (alerts.raiseFromEvent(a, "Wangiri burst", "spike", "wangiri_burst:" + key + ":"
                                + (hiMs / 60_000), Map.of("key", key)).isPresent()) alerted.putIfAbsent(key, now);
                    }
                }
            }
            long done = System.currentTimeMillis();
            try (ResultSet rs = st.executeQuery("SELECT (" + done + " - ARRIVED_MS) // 50 b, count(*) FROM fresh GROUP BY b")) {
                while (rs.next()) buckets.merge(rs.getLong(1), rs.getLong(2), Long::sum);
            }
            return true;
        }
    }

    private static void write(Path remote, int rate, int seconds, Map<String, Long> planted, AtomicBoolean writing) {
        Random rnd = new Random(42);
        int perFile = Math.max(1, rate * PERIOD_MS / 1000);
        long end = System.currentTimeMillis() + seconds * 1000L;
        int seq = 0;
        try {
            while (System.currentTimeMillis() < end) {
                long tick = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder(perFile * 40);
                for (int i = 0; i < perFile; i++) {
                    sb.append('A').append(rnd.nextInt(KEYS)).append(",B").append(rnd.nextInt(1_000_000)).append(',')
                            .append(5 + rnd.nextInt(300)).append(',').append(System.currentTimeMillis())
                            .append(",2026-11-12\n");
                }
                if (seq % Math.max(1, 5000 / PERIOD_MS) == 0) {   // every 5 s: one planted burst on a key outside the random range
                    String key = "W" + seq;
                    long at = System.currentTimeMillis();
                    for (int i = 0; i < 40; i++) sb.append(key).append(",V").append(i).append(",1,").append(at)
                            .append(",2026-11-12\n");
                    planted.put(key, at);
                }
                Path tmp = remote.resolve("f" + seq + ".tmp");
                Files.writeString(tmp, sb);
                Files.move(tmp, remote.resolve("f" + (seq++) + ".csv"));
                long sleep = PERIOD_MS - (System.currentTimeMillis() - tick);
                if (sleep > 0) Thread.sleep(sleep);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            writing.set(false);
        }
    }

    /** 10 minutes of history as 600 one-second Parquet files, the shape a 1 s drain cadence leaves. */
    private static void seedHistory(Path db, int rate, long now) throws Exception {
        Path seed = Files.createDirectories(db.resolve("_seed"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (int sec = SEED_SECONDS; sec > 0; sec--) {
                long base = now - sec * 1000L;
                st.execute("COPY (SELECT 'A' || (hash(i, " + sec + ") % " + KEYS + ") AS A_NUM, 'B' || (hash(i * 7, "
                        + sec + ") % 1000000) AS B_NUM, CAST(5 + hash(i, 3) % 300 AS DOUBLE) AS DUR, "
                        + base + " + (i % 1000) AS ARRIVED_MS, DATE '2026-11-12' AS EVENT_DATE FROM range(" + rate
                        + ") t(i)) TO '" + seed.resolve("s" + sec + ".parquet").toString().replace('\\', '/')
                        + "' (FORMAT PARQUET)");
            }
        }
    }

    /** CSVs written but not yet fetched (the fake connector counts each fetch). */
    private static long backlog(Path remote) throws Exception {
        return Math.max(0, countCsv(remote) - FakeRemoteConnectorFactory.FETCHES.get());
    }

    private static void report(int rate, int seconds, long seededMs, long bufferBootMs, TreeMap<Long, Long> buckets,
                               Map<String, Long> planted, Map<String, Long> alerted, List<Long> evalMs, Path db,
                               Path remote, int commits) throws Exception {
        long files;
        try (Stream<Path> w = Files.walk(db)) {
            files = w.filter(p -> p.toString().endsWith(".parquet") && !p.toString().contains("_seed")).count();
        }
        List<Long> burst = new ArrayList<>();
        int missed = 0;
        for (var e : planted.entrySet()) {
            Long a = alerted.get(e.getKey());
            if (a == null) missed++;
            else burst.add(a - e.getValue());
        }
        burst.sort(null);
        List<Long> ev = new ArrayList<>(evalMs);
        ev.sort(null);
        System.out.printf("PA9A mode=%s periodMs=%d batchMaxFiles=%d seedSeconds=%d rate=%d/s seconds=%d seed=%dms"
                        + " bufferBoot=%dms commits=%d | event->evaluated p50=%dms p95=%dms p99=%dms"
                        + " | burst->Alert n=%d missed=%d p50=%s p95=%s max=%s | eval p50=%s p95=%s max=%s"
                        + " | outputFiles=%d (%d/h) | fetched=%d of %d csv%n",
                BUFFER ? "buffer" : "glob", PERIOD_MS, BATCH_MAX_FILES, SEED_SECONDS,
                rate, seconds, seededMs, bufferBootMs, commits, pct(buckets, 0.50), pct(buckets, 0.95), pct(buckets, 0.99),
                burst.size(), missed, q(burst, 0.5), q(burst, 0.95), burst.isEmpty() ? "-" : burst.getLast(),
                q(ev, 0.5), q(ev, 0.95), ev.isEmpty() ? "-" : ev.getLast(),
                files, files * 3600 / Math.max(1, seconds), FakeRemoteConnectorFactory.FETCHES.get(),
                countCsv(remote));
    }

    private static long countCsv(Path remote) throws Exception {
        try (Stream<Path> f = Files.list(remote)) {
            return f.filter(p -> p.toString().endsWith(".csv")).count();
        }
    }

    private static long pct(TreeMap<Long, Long> buckets, double p) {
        long total = buckets.values().stream().mapToLong(Long::longValue).sum();
        long target = (long) Math.ceil(total * p), run = 0;
        for (var e : buckets.entrySet()) {
            run += e.getValue();
            if (run >= target) return e.getKey() * 50 + 50;
        }
        return -1;
    }

    private static String q(List<Long> sorted, double p) {
        return sorted.isEmpty() ? "-" : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p) - 1)) + "ms";
    }

    private static PipelineScheduler scheduler(CollectorService svc) throws Exception {
        Field f = CollectorService.class.getDeclaredField("pipelineScheduler");
        f.setAccessible(true);
        return (PipelineScheduler) f.get(svc);
    }

    private static AlertService alertService(Path cfgPath) throws Exception {
        PipelineConfig cfg = PipelineConfig.load(cfgPath.toString());
        ConfigSource configs = new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(cfg); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
        StatusStore store = new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
        return new AlertService(List.of(), configs, store);
    }

    private static Path pipeline(Path dir) throws Exception {
        Path schema = dir.resolve("cdr_schema.toon");
        Files.writeString(schema, """
            partitionKey: EVENT_DATE
            raw:
              name: cdr
              format: CSV
              fields[5]{name,selector,type}:
                A_NUM,"0",VARCHAR
                B_NUM,"1",VARCHAR
                DUR,"2",DOUBLE
                ARRIVED_MS,"3",BIGINT
                EVENT_DATE,"4",DATE
            mapping:
              canonicalName: cdr
              rawName: cdr
              rules[5]{targetColumn,sourceExpression,transformType}:
                A_NUM,A_NUM,DIRECT
                B_NUM,B_NUM,DIRECT
                DUR,DUR,DIRECT
                ARRIVED_MS,ARRIVED_MS,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
            """);
        String d = dir.toString().replace('\\', '/');
        String toon = """
            name: LANE_ETL
            active: true
            version: 1
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            collector:
              connector: faketest
            trigger:
              type: stream
              records: 1000
              max_wait: 1s
            output:
              format: PARQUET
            processing:
              threads: 2
              batch:
                max_files: %3$d
              file_pattern: "glob:**/*.csv"
              duplicate_check:
                enabled: true
                marker_extension: .processed
              schema_file: "%2$s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            """.formatted(d, schema.toString().replace("\\", "/"), BATCH_MAX_FILES);
        Path p = dir.resolve("lane_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}
