package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INCREMENTAL-1 (operator, 2026-10-10): an {@code incremental: {by: day, column, lookback: N}} sql.template
 * Job replaces exactly the last N day partitions of its sink and leaves every older day byte-identical.
 */
class SqlTemplateIncrementalTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 9);
    private static final String SQL = "SELECT event_date, account_id, sum(amount) AS total FROM events GROUP BY ALL";

    @AfterEach
    void resetSeam() { IncrementalSink.afterHide = () -> { }; }

    private static JobRun await(Supplier<JobRun> s, String prev) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        JobRun r;
        while (((r = s.get()) == null || r.runId().equals(prev) || "RUNNING".equals(r.status())) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertNotNull(r);
        return r;
    }

    /** {@code days} days ending at {@link #D}, two accounts a day, written as one file per call. */
    private static void seed(Path dataDir, String file, String valuesSql) throws Exception {
        DuckDbUtil.loadDriver();
        Path store = dataDir.resolve("events");
        Files.createDirectories(store);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("COPY (" + valuesSql + ") TO '" + store.resolve(file).toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private static String days(int n) {
        return "SELECT (DATE '" + D + "' - CAST(i AS INTEGER)) AS event_date, a AS account_id, 10.0 AS amount "
                + "FROM range(" + n + ") t(i), (VALUES (1), (2)) v(a)";
    }

    private static JobConfig job(int lookback) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("name", "daily");
        j.put("type", "sql.template");
        j.put("sql", SQL);
        j.put("sources", "events");
        j.put("sink_dataset", "daily_totals");
        j.put("incremental", Map.of("by", "day", "column", "event_date", "lookback", String.valueOf(lookback)));
        return JobConfig.fromMap(Map.of("job", j));
    }

    private static String run(JobService js, String name, String prev) throws Exception {
        assertTrue(js.triggerRun(name, null, Map.of("day", D.toString())).isPresent());
        JobRun r = await(() -> js.lastRunOf(name).orElse(null), prev);
        assertEquals("SUCCESS", r.status(), r.message());
        return r.runId();
    }

    /** Every partition file under the sink → its bytes. */
    private static Map<String, byte[]> snapshot(Path sink) throws Exception {
        Map<String, byte[]> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(sink)) {
            for (Path p : s.filter(p -> p.toString().endsWith(".parquet")).toList())
                out.put(sink.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        return out;
    }

    private static Map<LocalDate, Double> totals(Path sink) throws Exception {
        Map<LocalDate, Double> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT event_date, sum(total), typeof(event_date) FROM read_parquet('"
                     + sink.toString().replace('\\', '/') + "/**/*.parquet', hive_partitioning=true) GROUP BY ALL")) {
            while (rs.next()) {
                assertEquals("DATE", rs.getString(3), "the Hive reader restores the date column as DATE");
                out.put(rs.getObject(1, LocalDate.class), rs.getDouble(2));
            }
        }
        return out;
    }

    private static String dir(LocalDate d) { return "event_date=" + d + "/"; }

    @Test
    void onlyTheLookbackDaysChangeAndALateRowOutsideItIsNotPickedUp(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Path sink = dataDir.resolve("daily_totals");
        seed(dataDir, "base.parquet", days(30));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job(30)), new ConsignmentEventBus(), s, null,
                     tmp.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            String r1 = run(js, "daily", null);
            Map<LocalDate, Double> first = totals(sink);
            assertEquals(30, first.size(), "a 30-day backfill lays out 30 day partitions");
            assertEquals(20.0, first.get(D.minusDays(1)));
            String marker = Files.readString(sink.resolve(IncrementalSink.OWNER_MARKER));
            Map<String, byte[]> before = snapshot(sink);

            // late rows: one inside the 3-day lookback, one 10 days back (outside it)
            seed(dataDir, "late.parquet", "SELECT * FROM (VALUES (DATE '" + D.minusDays(1) + "', 1, 5.0), (DATE '"
                    + D.minusDays(10) + "', 1, 7.0)) v(event_date, account_id, amount)");
            js.upsertJob(job(3));
            run(js, "daily", r1);

            Map<String, byte[]> after = snapshot(sink);
            Map<LocalDate, Double> second = totals(sink);
            assertEquals(25.0, second.get(D.minusDays(1)), "the late row inside the lookback is picked up");
            assertEquals(20.0, second.get(D.minusDays(10)),
                    "the late row OUTSIDE the lookback is not picked up (a re-run with a wider lookback is the remedy)");
            int untouched = 0;
            for (int i = 3; i < 30; i++) {
                LocalDate d = D.minusDays(i);
                List<String> b = before.keySet().stream().filter(k -> k.startsWith(dir(d))).toList();
                List<String> a = after.keySet().stream().filter(k -> k.startsWith(dir(d))).toList();
                assertEquals(b, a, "day " + d + " keeps its file");
                for (String k : b) assertArrayEquals(before.get(k), after.get(k), "day " + d + " is byte-identical");
                untouched++;
            }
            assertEquals(27, untouched);
            for (int i = 0; i < 3; i++) {
                LocalDate d = D.minusDays(i);
                assertNotEquals(before.keySet().stream().filter(k -> k.startsWith(dir(d))).toList(),
                        after.keySet().stream().filter(k -> k.startsWith(dir(d))).toList(), "lookback day " + d + " was replaced");
            }
            assertEquals(marker, Files.readString(sink.resolve(IncrementalSink.OWNER_MARKER)), "ownership marker preserved");
            assertEquals(30, second.size());
        }
    }

    @Test
    void aCrashMidReplaceLeavesTheOldPartitionAndTheNextRunCompletes(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        Path sink = dataDir.resolve("daily_totals");
        seed(dataDir, "base.parquet", days(5));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job(2)), new ConsignmentEventBus(), s, null,
                     tmp.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            String r1 = run(js, "daily", null);
            Map<String, byte[]> before = snapshot(sink);
            seed(dataDir, "late.parquet", "SELECT DATE '" + D + "' AS event_date, 1 AS account_id, 5.0 AS amount");

            IncrementalSink.afterHide = () -> { throw new IllegalStateException("simulated crash"); };
            assertTrue(js.triggerRun("daily", null, Map.of("day", D.toString())).isPresent());
            JobRun crashed = await(() -> js.lastRunOf("daily").orElse(null), r1);
            assertNotEquals("SUCCESS", crashed.status());
            // mid-crash the day folder holds the hidden old file plus the unrevealed new one
            try (Stream<Path> w = Files.walk(sink)) {
                assertTrue(w.anyMatch(p -> p.toString().endsWith(".stale")), "the crash really happened mid-replace");
            }
            IncrementalSink.recoverAll(sink, "event_date");
            assertEquals(before.keySet(), snapshot(sink).keySet(), "rolled back to the OLD partition");
            for (String k : before.keySet()) assertArrayEquals(before.get(k), snapshot(sink).get(k));

            IncrementalSink.afterHide = () -> { };
            run(js, "daily", crashed.runId());
            assertEquals(25.0, totals(sink).get(D), "the next run completes the replace");
        }
    }

    @Test
    void recoveryRollsForwardWhenTheRevealHappened(@TempDir Path tmp) throws Exception {
        Path day = tmp.resolve("event_date=2026-10-09");
        Files.createDirectories(day);
        Files.writeString(day.resolve("part-new.parquet"), "new");
        Files.writeString(day.resolve("part-old.parquet.stale"), "old");
        IncrementalSink.recoverAll(tmp, "event_date");
        try (Stream<Path> l = Files.list(day)) {
            assertEquals(List.of("part-new.parquet"), l.map(p -> p.getFileName().toString()).toList());
        }
    }

    @Test
    void aFlatSnapshotSinkIsRefusedAtRunAndAtSave(@TempDir Path tmp) throws Exception {
        Path dataDir = tmp.resolve("data");
        seed(dataDir, "base.parquet", days(2));
        Path sink = dataDir.resolve("daily_totals");
        Files.createDirectories(sink);
        Files.writeString(sink.resolve("sql-1.parquet"), "flat snapshot");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job(2)), new ConsignmentEventBus(), s, null,
                     tmp.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            assertNotNull(js.incrementalSinkRefusal(job(2)), "save refuses a flat sink");
            assertTrue(js.triggerRun("daily", null, Map.of("day", D.toString())).isPresent());
            JobRun r = await(() -> js.lastRunOf("daily").orElse(null), null);
            assertNotEquals("SUCCESS", r.status());
            assertTrue(r.message().contains("not laid out by day"), r.message());
            assertEquals("flat snapshot", Files.readString(sink.resolve("sql-1.parquet")), "nothing touched");
            Files.delete(sink.resolve("sql-1.parquet"));
            assertNull(js.incrementalSinkRefusal(job(2)), "an empty sink will be laid out by day");
        }
    }

    @Test
    void theBlockIsValidatedFailClosedAndRoundTrips() {
        for (Map<String, ?> bad : List.<Map<String, ?>>of(
                Map.of("by", "day", "column", "event_date", "lookback", "0"),
                Map.of("by", "day", "column", "event_date", "lookback", "367"),
                Map.of("by", "day", "column", "event_date", "lookback", "x"),
                Map.of("by", "week", "column", "event_date", "lookback", "3"),
                Map.of("by", "day", "column", "no_such_col", "lookback", "3"),
                Map.of("by", "day", "column", "event_date", "lookback", "3", "extra", "1"),
                Map.of("by", "day", "lookback", "3"))) {
            Map<String, Object> j = new HashMap<>(Map.of("name", "x", "type", "sql.template", "sql", SQL,
                    "sink_dataset", "s", "incremental", bad));
            assertThrows(IllegalArgumentException.class, () -> JobConfig.fromMap(Map.of("job", j)), bad.toString());
        }
        Map<String, Object> wrongType = new HashMap<>(Map.of("name", "x", "type", "report", "sql", SQL,
                "incremental", Map.of("by", "day", "column", "event_date", "lookback", "3")));
        assertThrows(IllegalArgumentException.class, () -> JobConfig.fromMap(Map.of("job", wrongType)));
        // the edges are legal, and the block re-nests on write
        assertNotNull(job(1));
        JobConfig ok = job(366);
        assertEquals(Map.of("by", "day", "column", "event_date", "lookback", "366"), ok.toMap().get("incremental"));
        assertFalse(ok.toMap().keySet().stream().anyMatch(k -> k.startsWith("incremental.")));
        assertEquals(ok, JobConfig.fromMap(Map.of("job", ok.toMap())));
    }
}
