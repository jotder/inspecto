package com.gamma.consignment;

import com.gamma.etl.PartitionOutput;
import com.gamma.etl.PartitionWriter;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * <b>Rung A, measured</b> — step 1 of {@code docs/superpower/consignment-addressing-plan.md}.
 *
 * <p>The plan's ladder (§5.4) starts at rung A — "catalog-pruned rescan: the Selector names the
 * files, DuckDB aggregates" — and says every rung above it should be justified by a measurement
 * nobody had taken. This is that measurement: lay down a realistic event-time-partitioned Parquet
 * corpus, land one more Consignment on it, then recompute the hopping windows that Consignment
 * dirtied — twice:
 *
 * <ol>
 *   <li><b>pruned</b> — {@code read_parquet([...])} over the explicit list of files whose partitions
 *       overlap the dirty windows (what a Consignment Selector would emit);</li>
 *   <li><b>glob</b> — {@code read_parquet('&lt;root&gt;/**&#47;*.parquet')}, the read shape
 *       {@code DatasetRelation} builds today.</li>
 * </ol>
 *
 * The gap between the two is what addressing buys; the absolute pruned number is what decides
 * whether rungs B–F are solving a real problem.
 *
 * <p><b>Two honesty notes on what this does and does not measure.</b> Pruning here is
 * <em>day-granular</em>, because that is how the corpus is partitioned and because
 * {@code consignment_outputs} carries no event-time bounds yet (plan step 3) — so the pruned number
 * is an <em>upper</em> bound on the work real per-file bounds would do, i.e. a conservative reading
 * of rung A. And the corpus is synthetic: uniformly spread event times, a hash-scattered subscriber
 * key, one measure column. Real CDR is skewed, and skew moves aggregate cost.
 *
 * <p>Not part of the normal suite, and kept out of it <b>twice</b> — the class name matches none of
 * Surefire's default include patterns, so {@code mvn test} never discovers it (the same way
 * {@code PipelineBenchmark} and its siblings stay out), and the {@code assumeTrue} below is the
 * second line of defence for whoever eventually widens {@code <includes>}. Run explicitly:
 * <pre>
 *   mvn -o -pl inspecto-engine -Dtest=RescanBenchmark -DfailIfNoTests=false \
 *       -Dbench.run=true -Dbench.rows=20000000 test
 * </pre>
 *
 * <p>System properties: {@code bench.rows} (default 20,000,000) · {@code bench.days} (30) ·
 * {@code bench.subscribers} (1,000,000) · {@code bench.window.minutes} (60) ·
 * {@code bench.hop.minutes} (10) · {@code bench.consignment.minutes} (30, the event-time span the
 * arriving Consignment covers) · {@code bench.repeats} (3, timed passes after one warm-up).
 */
class RescanBenchmark {

    private static final LocalDateTime EPOCH = LocalDateTime.parse("2026-01-01T00:00:00");

    @Test
    void rungAcatalogPrunedRescanVersusGlob(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bench.run"),
                "RescanBenchmark skipped — pass -Dbench.run=true to run it");

        int rows        = Integer.getInteger("bench.rows", 20_000_000);
        int days        = Integer.getInteger("bench.days", 30);
        int subscribers = Integer.getInteger("bench.subscribers", 1_000_000);
        int sizeMin     = Integer.getInteger("bench.window.minutes", 60);
        int hopMin      = Integer.getInteger("bench.hop.minutes", 10);
        int consignMin  = Integer.getInteger("bench.consignment.minutes", 30);
        int repeats     = Integer.getInteger("bench.repeats", 3);

        long sizeSec = sizeMin * 60L;
        long hopSec  = hopMin * 60L;
        int windowsPerEvent = (int) (sizeSec / hopSec);   // each event belongs to size/hop windows

        System.out.printf(Locale.ROOT,
                "%n=== RescanBenchmark: %,d rows over %d days, %,d subscribers, "
                        + "window %dm hop %dm, consignment spans %dm ===%n",
                rows, days, subscribers, sizeMin, hopMin, consignMin);

        String outDir = dir.resolve("out").toString().replace('\\', '/');
        File db = DuckDbUtil.tempDbFile("rescan_bench_");
        try (Connection conn = DuckDbUtil.openConnection(db)) {

            // ── the history: `days` days of event-time-partitioned Parquet ─────────────
            long t = System.nanoTime();
            long historySpanSec = days * 86_400L;
            exec(conn, """
                    CREATE TABLE history AS
                    SELECT 'sub_' || CAST((i * 7919) %% %d AS VARCHAR)              AS subscriber,
                           TIMESTAMP '%s' + to_seconds(CAST(i * %d / %d AS BIGINT)) AS event_time,
                           CAST((i %% 997) * 13 AS BIGINT)                          AS bytes_used
                    FROM range(0, %d) t(i)
                    """.formatted(subscribers, EPOCH, historySpanSec, rows, rows));
            partitionColumns(conn, "history");
            List<PartitionOutput> historyFiles = PartitionWriter.write(
                    conn, "history_p", outDir, "PARQUET", "snappy", "history",
                    List.of("year", "month", "day"), List.of());
            System.out.printf(Locale.ROOT, "corpus:    %6.2fs  files=%d  %.1f MB%n",
                    secs(t), historyFiles.size(), megabytes(historyFiles));

            // ── one Consignment lands, covering the last `consignMin` of the last day ──
            LocalDateTime consignHi = EPOCH.plusSeconds(historySpanSec);
            LocalDateTime consignLo = consignHi.minusMinutes(consignMin);
            int consignRows = (int) Math.max(1, (long) rows * consignMin * 60 / historySpanSec);
            t = System.nanoTime();
            exec(conn, """
                    CREATE TABLE arriving AS
                    SELECT 'sub_' || CAST((i * 7919) %% %d AS VARCHAR)              AS subscriber,
                           TIMESTAMP '%s' + to_seconds(CAST(i * %d / %d AS BIGINT)) AS event_time,
                           CAST((i %% 997) * 13 AS BIGINT)                          AS bytes_used
                    FROM range(0, %d) t(i)
                    """.formatted(subscribers, consignLo, consignMin * 60L, consignRows, consignRows));
            partitionColumns(conn, "arriving");
            List<PartitionOutput> arrivingFiles = PartitionWriter.write(
                    conn, "arriving_p", outDir, "PARQUET", "snappy", "consignment",
                    List.of("year", "month", "day"), List.of());
            System.out.printf(Locale.ROOT, "consignment: %4.2fs  %,d rows  files=%d  %.1f MB%n",
                    secs(t), consignRows, arrivingFiles.size(), megabytes(arrivingFiles));

            // ── the dirty windows: m panes touched ⇒ m + size/hop − 1 windows (§5.1) ───
            long loEpoch = EPOCH.until(consignLo, java.time.temporal.ChronoUnit.SECONDS);
            long hiEpoch = EPOCH.until(consignHi, java.time.temporal.ChronoUnit.SECONDS);
            long baseEpoch = EPOCH.toEpochSecond(java.time.ZoneOffset.UTC);
            long firstWindow = floorTo(baseEpoch + loEpoch, hopSec) - (sizeSec - hopSec);
            long lastWindow  = floorTo(baseEpoch + hiEpoch, hopSec);
            int dirtyWindows = (int) ((lastWindow - firstWindow) / hopSec) + 1;
            // Rows a dirty window can contain: [firstWindow, lastWindow + size).
            LocalDateTime readFrom = EPOCH.plusSeconds(firstWindow - baseEpoch);
            LocalDateTime readTo   = EPOCH.plusSeconds(lastWindow - baseEpoch + sizeSec);
            System.out.printf(Locale.ROOT, "dirty:     %d windows, rows needed in [%s, %s)%n",
                    dirtyWindows, readFrom, readTo);

            // ── the two read shapes ────────────────────────────────────────────────────
            List<PartitionOutput> all = concat(historyFiles, arrivingFiles);
            List<PartitionOutput> pruned = all.stream()
                    .filter(o -> partitionOverlaps(o.partition(), readFrom, readTo))
                    .toList();
            String prunedRelation = "read_parquet([" + pruned.stream()
                    .map(o -> "'" + o.outputFile().replace('\\', '/') + "'")
                    .collect(Collectors.joining(", ")) + "])";
            String globRelation = "read_parquet('" + outDir + "/**/*.parquet')";

            System.out.printf(Locale.ROOT,
                    "prune:     %d of %d files (%.1f%%), %.1f of %.1f MB%n",
                    pruned.size(), all.size(), 100.0 * pruned.size() / all.size(),
                    megabytes(pruned), megabytes(all));

            long prunedRows = scalar(conn, "SELECT count(*) FROM " + prunedRelation);
            long allRows    = scalar(conn, "SELECT count(*) FROM " + globRelation);

            long threshold = Long.getLong("bench.threshold", 20_000L);
            String prunedSql = rescanSql(prunedRelation, readFrom, readTo, hopSec,
                    windowsPerEvent, firstWindow, lastWindow, threshold);
            String globSql = rescanSql(globRelation, readFrom, readTo, hopSec,
                    windowsPerEvent, firstWindow, lastWindow, threshold);

            Result prunedResult = time(conn, prunedSql, repeats);
            Result globResult = time(conn, globSql, repeats);

            System.out.printf(Locale.ROOT, "%n%-8s %10s %16s %8s %14s %10s%n",
                    "read", "best (s)", "rows in files", "files", "groups", "breaches");
            System.out.printf(Locale.ROOT, "%-8s %10.3f %,16d %8d %,14d %,10d%n",
                    "pruned", prunedResult.bestSec, prunedRows, pruned.size(),
                    prunedResult.groups, prunedResult.breaches);
            System.out.printf(Locale.ROOT, "%-8s %10.3f %,16d %8d %,14d %,10d%n",
                    "glob", globResult.bestSec, allRows, all.size(),
                    globResult.groups, globResult.breaches);
            System.out.printf(Locale.ROOT, "%nspeed-up from pruning: %.1f×%n",
                    globResult.bestSec / Math.max(prunedResult.bestSec, 1e-9));
        } finally {
            DuckDbUtil.deleteTempDb(db);
        }
    }

    // ── the rescan itself ───────────────────────────────────────────────────────────

    /**
     * One hopping-window aggregate, expressed the way rung A would: every event fans out to the
     * {@code size/hop} windows containing it, grouped by {@code (window, subscriber)}, kept when a
     * monotonic threshold is crossed (§5.3 — the rule shape that can fire without a watermark).
     */
    private static String rescanSql(String relation, LocalDateTime from, LocalDateTime to,
                                    long hopSec, int windowsPerEvent, long firstWindow, long lastWindow,
                                    long threshold) {
        return """
                SELECT count(*), count(*) FILTER (WHERE total > %d) FROM (
                    SELECT window_start, subscriber, sum(bytes_used) AS total
                    FROM (
                        SELECT subscriber, bytes_used,
                               ((CAST(epoch(event_time) AS BIGINT) // %d) - i) * %d AS window_start
                        FROM %s, range(0, %d) w(i)
                        WHERE event_time >= TIMESTAMP '%s' AND event_time < TIMESTAMP '%s'
                    ) fanned
                    WHERE window_start BETWEEN %d AND %d
                    GROUP BY window_start, subscriber
                ) windows
                """.formatted(threshold, hopSec, hopSec, relation, windowsPerEvent, from, to,
                firstWindow, lastWindow);
    }

    private record Result(double bestSec, long groups, long breaches) {}

    /** One warm-up pass (page cache, Parquet metadata), then {@code repeats} timed passes; best wins. */
    private static Result time(Connection conn, String sql, int repeats) throws Exception {
        long[] counts = twoScalars(conn, sql);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < repeats; i++) {
            long t = System.nanoTime();
            twoScalars(conn, sql);
            best = Math.min(best, secs(t));
        }
        return new Result(best, counts[0], counts[1]);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Add the Hive partition columns the real write path derives, zero-padded as it pads them.
     *
     * <p>{@code -Dbench.shuffle=true} randomises row order first, which is the measurement that
     * matters: written in event-time order, every Parquet row group carries a narrow
     * {@code event_time} min/max and DuckDB skips almost all of them from statistics alone, so a
     * file list has little left to save. Shuffling makes every row group span the whole day — the
     * pessimal case for statistics, and what interleaved parallel producers and late arrivals push
     * a real corpus towards.
     */
    private static void partitionColumns(Connection conn, String table) throws Exception {
        String order = Boolean.getBoolean("bench.shuffle") ? " ORDER BY random()" : "";
        exec(conn, """
                CREATE TABLE %s_p AS
                SELECT *, CAST(year(event_time) AS VARCHAR)                  AS year,
                          lpad(CAST(month(event_time) AS VARCHAR), 2, '0')   AS month,
                          lpad(CAST(day(event_time) AS VARCHAR), 2, '0')     AS day
                FROM %s%s
                """.formatted(table, table, order));
    }

    /** Whether a {@code year=2026/month=01/day=07} partition can hold a row in {@code [from, to)}. */
    private static boolean partitionOverlaps(String partition, LocalDateTime from, LocalDateTime to) {
        LocalDateTime dayStart = LocalDateTime.parse(
                partition.replace("year=", "").replace("/month=", "-").replace("/day=", "-")
                        + "T00:00:00");
        return dayStart.isBefore(to) && dayStart.plusDays(1).isAfter(from);
    }

    private static long floorTo(long epochSec, long stepSec) {
        return Math.floorDiv(epochSec, stepSec) * stepSec;
    }

    private static List<PartitionOutput> concat(List<PartitionOutput> a, List<PartitionOutput> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    private static double megabytes(List<PartitionOutput> files) {
        return files.stream().mapToLong(PartitionOutput::bytes).sum() / 1_048_576.0;
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    private static long scalar(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static long[] twoScalars(Connection conn, String sql) throws Exception {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0L, 0L};
        }
    }

    private static double secs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }
}
