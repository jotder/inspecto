package com.gamma.control;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Measurement, not a gate ({@code TELCO-BYPASS-SIMBOX-1} slice B2). Runs only with {@code TELCO_BYPASS_PERF=1} and prints:
 * <ul>
 *   <li>how long the three B2 stages (factors, Risk Score, findings) take over a synthetic {@code tb_features} day of N scored SIMs; and</li>
 *   <li>what the SIM-level Alert Rule {@code tb_sim_high} does at and above its {@code stormCap} (1000, the platform ceiling):
 *       one Incident per SIM, or one storm Incident standing for all of them.</li>
 * </ul>
 * The inputs are synthetic and shaped by a seeded generator, not by an operator's traffic.
 */
@EnabledIfEnvironmentVariable(named = "TELCO_BYPASS_PERF", matches = "1")
class BypassScaleMeasureTest {

    /** A synthetic {@code tb_features} day: about 1 % of the SIMs are box-like, the rest ordinary; only the columns the stages read. */
    private static void writeFeatures(Path data, String day, int sims) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("tb_features").resolve("window_date=" + day));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("SELECT setseed(0.42)");
            st.execute("CREATE TABLE f AS SELECT '9997' || lpad(CAST(i AS VARCHAR), 7, '0') AS msisdn, random() < 0.01 AS box FROM range(" + sims + ") t(i)");
            st.execute("COPY (SELECT msisdn, "
                    + "CAST(CASE WHEN box THEN 60 ELSE 20 + 100 * random() END AS BIGINT) AS calls_mo, "
                    + "CASE WHEN box THEN 1.0 ELSE 0.2 + 0.7 * random() END AS b_diversity, "
                    + "CASE WHEN box THEN 1.0 ELSE 0.5 * random() END AS short_call_ratio, "
                    + "CASE WHEN box THEN 0.0 ELSE 0.6 * random() END AS inbound_ratio, "
                    + "CASE WHEN box THEN 1.0 ELSE 0.7 + 0.3 * random() END AS voice_only_share, "
                    + "CASE WHEN box THEN 1.0 ELSE 0.2 + 0.8 * random() END AS top_cell_share, "
                    + "CASE WHEN box THEN 0.9 ELSE 0.1 * random() END AS seq_dial_share, "
                    + "CASE WHEN box THEN 0.2 ELSE 0.1 * random() END AS night_share, "
                    + "CASE WHEN box THEN 1.0 ELSE random() END AS onnet_target_share, "
                    + "CAST(CASE WHEN box THEN NULL ELSE 80 * random() END AS DOUBLE) AS max_implied_kmh, "
                    + "CAST(CASE WHEN box THEN 24 ELSE 3 + 15 * random() END AS BIGINT) AS active_hours, "
                    + "CASE WHEN box THEN 0.85 ELSE 0.9 END AS asr, 40.0 AS acd_s, '' AS missing, 'R1,R2,R3' AS evidence_ids "
                    + "FROM f) TO '" + dir.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
    }

    private static JobRun run(JobService js, String name, Map<String, String> args) throws Exception {
        JobRun r = BypassPack.runJob(js, name, args);
        assertEquals("SUCCESS", r.status(), name + ": " + r.message());
        return r;
    }

    @Test
    void theThreeB2StagesAtThreeVolumes(@TempDir Path tmp) throws Exception {
        for (int sims : List.of(3_000, 50_000, 150_000)) {
            Path root = tmp.resolve("n" + sims);
            Files.createDirectories(root);
            Path space = BypassPack.copyTemplate(root);
            Path data = space.resolve("data");
            writeFeatures(data, "2026-07-01", sims);
            BypassPack.writeSidecar(data, "tb_allow", "allow", List.of());
            BypassPack.writeSidecar(data, "tb_watch", "watch", List.of());
            List<JobConfig> jobs = List.of(BypassPack.job(space, "tb_factors"), BypassPack.job(space, "tb_risk_score"), BypassPack.job(space, "tb_findings"));
            System.setProperty("assist.write.root", space.resolve("config").toString());
            System.setProperty("data.dir", data.toString());
            try (Scheduler s = new Scheduler();
                 JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null, space.resolve("audit").toString(), null, null, data.toString())) {
                js.start();
                long a = run(js, "tb_factors", BypassPack.window("2026-07-01")).durationMs();
                long b = run(js, "tb_risk_score", BypassPack.window("2026-07-01")).durationMs();
                long c = run(js, "tb_findings", BypassPack.window("2026-07-01")).durationMs();
                Map<String, Map<String, Object>> found = BypassPack.rows(data, "tb_findings", "entity_key", null);
                System.out.println("BYPASS-B2 scale: " + sims + " scored SIMs -> tb_factors " + a + " ms, tb_risk_score " + b + " ms, tb_findings " + c
                        + " ms (" + found.size() + " findings, " + found.values().stream().filter(r -> "persistence".equals(r.get("status"))).count() + " held by persistence)");
            } finally {
                System.clearProperty("assist.write.root");
                System.clearProperty("data.dir");
            }
        }
    }

    // ------------------------------------------------------------------------------------------- slice B3

    /**
     * A synthetic voice day: {@code rows} ordinary rows (400,000 SIMs, three cells each, half MO, 60 % voice, 25 % SMS, 15 % data,
     * 2 % roaming, a device each) plus {@code boxes} boxes of 60 SIMs with 250 short MO calls each on one cell and one IMEI. The
     * hash-based generator is deterministic; it is NOT an operator's traffic.
     */
    private static void writeCdrDay(Path data, int rows, int boxes) throws Exception {
        DuckDbUtil.loadDriver();
        Path dir = Files.createDirectories(data.resolve("voice_cdr").resolve("database"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            String sim = "'9997' || lpad(CAST((h >> 20) % 400000 AS VARCHAR), 7, '0')";
            String other = "'9998' || lpad(CAST((h >> 34) % 1000000 AS VARCHAR), 7, '0')";
            String ordinary = "SELECT 'R' || i AS record_id, TIMESTAMP '2026-07-01 00:00:00' + to_seconds(CAST(h % 86400 AS BIGINT)) AS start_ts, "
                    + "CASE WHEN (h >> 8) % 2 = 0 THEN 'MO' ELSE 'MT' END AS direction, "
                    + "CASE WHEN (h >> 12) % 100 < 60 THEN 'VOICE' WHEN (h >> 12) % 100 < 85 THEN 'SMS' ELSE 'DATA' END AS service, "
                    + "CASE WHEN (h >> 8) % 2 = 0 THEN " + sim + " ELSE " + other + " END AS a_number, "
                    + "CASE WHEN (h >> 8) % 2 = 0 THEN " + other + " ELSE " + sim + " END AS b_number, "
                    + "CAST((h >> 40) % 300 AS INTEGER) AS duration_s, CASE WHEN (h >> 50) % 50 = 0 THEN 'Y' ELSE 'N' END AS roaming, "
                    + "'C' || CAST(((h >> 20) % 400000 * 3 + (h >> 46) % 3) % 30000 AS VARCHAR) AS cell_id, 'Y' AS answered, '16' AS release_cause, "
                    + "'99' || lpad(CAST((h >> 20) % 400000 AS VARCHAR), 13, '0') AS imei, '00101' || lpad(CAST((h >> 20) % 400000 AS VARCHAR), 10, '0') AS imsi "
                    + "FROM (SELECT i, hash(i) AS h FROM range(" + rows + ") t(i))";
            String box = "SELECT 'B' || j AS record_id, TIMESTAMP '2026-07-01 00:00:00' + to_seconds(CAST((j % 250) * 345 AS BIGINT)) AS start_ts, "
                    + "'MO' AS direction, 'VOICE' AS service, '9996' || lpad(CAST(j // 250 AS VARCHAR), 7, '0') AS a_number, "
                    + "'9997' || lpad(CAST(hash(j) % 1000000 AS VARCHAR), 7, '0') AS b_number, CAST(20 + hash(j + 7) % 41 AS INTEGER) AS duration_s, "
                    + "'N' AS roaming, 'BX' || CAST(j // 15000 AS VARCHAR) AS cell_id, 'Y' AS answered, '16' AS release_cause, "
                    + "'88' || lpad(CAST(j // 15000 AS VARCHAR), 13, '0') AS imei, '00101' || lpad(CAST(j // 250 AS VARCHAR), 10, '0') AS imsi "
                    + "FROM range(" + (boxes * 60 * 250) + ") t(j)";
            st.execute("COPY (" + ordinary + (boxes > 0 ? " UNION ALL " + box : "") + ") TO '" + dir.resolve("part.parquet").toString().replace('\\', '/')
                    + "' (FORMAT PARQUET)");
        }
    }

    /** Peak {@code duckdb_memory()} of a statement, polled every 50 ms from a second connection to the same database. */
    private static long[] timedWithPeak(Connection c, String sql) throws Exception {
        java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Connection poll = ((org.duckdb.DuckDBConnection) c).duplicate();
        Thread t = new Thread(() -> {
            try (Statement ps = poll.createStatement()) {
                while (!stop.get()) {
                    try (java.sql.ResultSet rs = ps.executeQuery("SELECT coalesce(sum(memory_usage_bytes), 0) FROM duckdb_memory()")) {
                        if (rs.next()) peak.accumulateAndGet(rs.getLong(1), Math::max);
                    }
                    Thread.sleep(50);
                }
            } catch (Exception ignored) { }
        });
        t.start();
        long t0 = System.nanoTime();
        try (Statement st = c.createStatement()) { st.execute(sql); }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        stop.set(true);
        t.join();
        poll.close();
        return new long[]{ms, peak.get()};
    }

    /**
     * The two cluster stages over one synthetic CDR day, twice: (a) the member stage's SQL standalone in DuckDB with the engine's
     * settings (the default memory cap, default threads), wall time and peak DuckDB memory; (b) both Jobs as shipped through
     * {@code JobService} (with {@code IncrementalSink} and the Parquet write), wall time of each.
     */
    @Test
    void theClusterStagesOverACdrDay(@TempDir Path tmp) throws Exception {
        int boxes = 20;
        for (int rows : List.of(1_000_000, 5_000_000, 10_000_000)) {
            Path root = tmp.resolve("c" + rows);
            Files.createDirectories(root);
            Path space = BypassPack.copyTemplate(root);
            Path data = space.resolve("data");
            writeCdrDay(data, rows, boxes);
            BypassPack.writeSidecar(data, "tb_allow", "allow", List.of());
            JobConfig members = BypassPack.job(space, "tb_cluster_members");
            String sql = members.params().get("sql");
            Map<String, String> window = BypassPack.window("2026-07-01");
            List<String> names = new java.util.ArrayList<>(members.params().keySet());
            names.sort((a, b) -> b.length() - a.length());
            for (String n : names) {
                String v = members.params().get(n);
                if (v.startsWith("$")) v = window.getOrDefault(n, v);
                sql = sql.replace("$" + n, "'" + v.replace("'", "''") + "'");
            }
            long[] standalone;
            long out;
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("SET memory_limit = '" + DuckDbUtil.defaultMemoryLimit() + "'");
                st.execute("CREATE VIEW voice_cdr AS SELECT * FROM read_parquet('" + data.resolve("voice_cdr/database").toString().replace('\\', '/')
                        + "/*.parquet', union_by_name = true)");
                st.execute("CREATE VIEW subscriber_profile AS SELECT * FROM read_parquet('" + data.resolve("subscriber_profile/database").toString().replace('\\', '/')
                        + "/*.parquet', union_by_name = true)");
                st.execute("CREATE VIEW entity_list_tb_allow AS SELECT * FROM read_parquet('" + data.resolve("entity_list_tb_allow").toString().replace('\\', '/')
                        + "/*.parquet')");
                standalone = timedWithPeak(c, "CREATE TABLE members AS " + sql);
                try (java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM members")) { rs.next(); out = rs.getLong(1); }
            }
            List<JobConfig> jobs = List.of(members, BypassPack.job(space, "tb_clusters"));
            System.setProperty("assist.write.root", space.resolve("config").toString());
            System.setProperty("data.dir", data.toString());
            long a, b;
            try (Scheduler s = new Scheduler();
                 JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null, space.resolve("audit").toString(), null, null, data.toString())) {
                js.start();
                a = run(js, "tb_cluster_members", BypassPack.window("2026-07-01")).durationMs();
                b = run(js, "tb_clusters", BypassPack.window("2026-07-01")).durationMs();
                Map<String, Map<String, Object>> boxRows = BypassPack.rows(data, "tb_clusters", "cluster_key", null);
                System.out.println("BYPASS-B3 scale: " + (rows + boxes * 60 * 250) + " CDR rows -> member stage standalone " + standalone[0] + " ms, peak DuckDB memory "
                        + standalone[1] / (1024 * 1024) + " MiB (" + out + " member SIMs); real Jobs: tb_cluster_members " + a + " ms, tb_clusters " + b
                        + " ms, " + boxRows.size() + " boxes");
            } finally {
                System.clearProperty("assist.write.root");
                System.clearProperty("data.dir");
            }
        }
    }

    /**
     * The box Alert Rule against the SIM Alert Rule for a box farm: 20 boxes of 60 SIMs (1,200 alerting SIMs): the SIM rule storms
     * (one storm Incident, no per-SIM Incident), the box rule opens one Incident per box; then the box rule at and above its own
     * stormCap (500).
     */
    @Test
    void theBoxRuleExpressesTheFarmTheSimRuleCannot(@TempDir Path tmp) throws Exception {
        for (int boxes : List.of(20, 500, 501)) {
            Path root = tmp.resolve("f" + boxes);
            Files.createDirectories(root);
            Path space = BypassPack.copyTemplate(root);
            DuckDbUtil.loadDriver();
            Path findings = Files.createDirectories(space.resolve("data").resolve("tb_findings").resolve("window_date=2026-07-02"));
            Path clusters = Files.createDirectories(space.resolve("data").resolve("tb_clusters").resolve("window_date=2026-07-02"));
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                int sims = 1200;
                st.execute("COPY (SELECT '9996' || lpad(CAST(i AS VARCHAR), 7, '0') AS entity_key, 70.0 AS alert_score, 0 AS shape_changed FROM range(" + sims
                        + ") t(i)) TO '" + findings.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                st.execute("COPY (SELECT 'imei:88' || lpad(CAST(i AS VARCHAR), 13, '0') AS cluster_key, 60 AS alert_value FROM range(" + boxes
                        + ") t(i)) TO '" + clusters.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            BypassPack.Sweep sweep = new BypassPack.Sweep(space);
            sweep.evaluate();
            long stormSim = sweep.objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT && "tb_sim_high".equals(o.attributes().get("rule"))
                    && o.attributes().containsKey("breachedKeys")).count();
            long stormBox = sweep.objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT && "tb_box_suspected".equals(o.attributes().get("rule"))
                    && o.attributes().containsKey("breachedKeys")).count();
            System.out.println("BYPASS-B3 farm: 1200 alerting SIMs (" + sweep.incidents("tb_sim_high") + " SIM-rule Incident(s), " + stormSim + " storm) and "
                    + boxes + " boxes (" + sweep.incidents("tb_box_suspected") + " box-rule Incident(s), " + stormBox + " storm)");
        }
    }

    @Test
    void theSimAlertRuleAtItsStormCap(@TempDir Path tmp) throws Exception {
        for (int alerting : List.of(900, 1000, 1001, 1200)) {
            Path root = tmp.resolve("k" + alerting);
            Files.createDirectories(root);
            Path space = BypassPack.copyTemplate(root);
            Path dir = Files.createDirectories(space.resolve("data").resolve("tb_findings").resolve("window_date=2026-07-02"));
            DuckDbUtil.loadDriver();
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
                st.execute("COPY (SELECT '9997' || lpad(CAST(i AS VARCHAR), 7, '0') AS entity_key, 70.0 AS alert_score, 0 AS shape_changed FROM range(" + alerting
                        + ") t(i)) TO '" + dir.resolve("part.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            BypassPack.Sweep sweep = new BypassPack.Sweep(space);
            sweep.evaluate();
            long storm = sweep.objects.opened.stream().filter(o -> o.kind() == ObjectType.INCIDENT && o.attributes().containsKey("breachedKeys")).count();
            System.out.println("BYPASS-B2 stormCap: " + alerting + " alerting SIMs -> " + sweep.incidents() + " Incident(s), " + storm + " of them a storm Incident");
        }
    }
}
