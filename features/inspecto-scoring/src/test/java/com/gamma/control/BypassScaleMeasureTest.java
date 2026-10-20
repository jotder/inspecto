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
