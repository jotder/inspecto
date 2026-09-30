package com.gamma.control;

import com.gamma.alert.Alert;
import com.gamma.service.SpaceManager;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ASSURE-PACK-TELCO-FRAUD-1} golden test. Boots the shipped {@code spaces/_templates/telco-fraud} Space
 * Template through {@code POST /spaces} (every seed gate), lands its synthetic corpus as the feeds' Parquet, runs
 * all ten {@code sql.template} detection Jobs exactly as authored, then evaluates the template's per-entity Alert
 * Rules with the production grouped Measure probe. Each Alert Rule must raise EXACTLY its planted offenders — the
 * exact count per typology — and no planted look-alike may be raised by any rule.
 *
 * <p>⚠ The feeds are landed by DuckDB {@code read_csv} with the schema's column types, standing in for the three
 * shipped Pipelines' ingest; the Pipelines' own ingest of this corpus is not exercised here.
 */
class TelcoFraudTemplateGoldenTest {

    /** Expected Alerts per Alert Rule — the golden counts. */
    private static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.of(
            "fraud_irsf", 4, "fraud_wangiri", 3, "fraud_simbox", 3, "fraud_premium_rate", 4, "fraud_roaming", 3,
            "fraud_sim_swap", 3, "fraud_identity", 2, "fraud_dealer", 2, "fraud_voucher", 3, "fraud_reversal", 3));

    private static final Map<String, String> FEED_COLUMNS = Map.of(
            "cdr", "{'record_id':'VARCHAR','start_ts':'TIMESTAMP','direction':'VARCHAR','service':'VARCHAR',"
                    + "'a_number':'VARCHAR','b_number':'VARCHAR','duration_s':'INTEGER','charge':'DOUBLE',"
                    + "'roaming':'VARCHAR','cell_id':'VARCHAR'}",
            "subscriber_events", "{'event_id':'VARCHAR','event_ts':'TIMESTAMP','event_type':'VARCHAR',"
                    + "'msisdn':'VARCHAR','dealer_id':'VARCHAR','id_doc':'VARCHAR'}",
            "payments", "{'txn_id':'VARCHAR','txn_ts':'TIMESTAMP','txn_type':'VARCHAR','msisdn':'VARCHAR',"
                    + "'amount':'DOUBLE','voucher_serial':'VARCHAR','ref_txn_id':'VARCHAR'}");

    private static Path template() {
        Path p = Path.of("..", "spaces", "_templates", "telco-fraud").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(p), "the shipped template is missing: " + p);
        return p;
    }

    @BeforeEach
    void arm() {
        // Alert Rules are Professional+: a Personal build refuses the whole template at seed time (G9).
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1",
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH))));
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        MetricRegistry.global().reset();
    }

    @Test
    void theCommittedCorpusIsTheSeededGeneratorsOutput() throws Exception {
        Map<String, String> files = new TelcoFraudCorpus().generate().files();
        Path samples = template().resolve("data").resolve("samples");
        if ("true".equals(System.getenv("TELCO_FRAUD_REGENERATE")))
            for (Map.Entry<String, String> f : files.entrySet()) {
                Files.createDirectories(samples.resolve(f.getKey()).getParent());
                Files.writeString(samples.resolve(f.getKey()), f.getValue());
            }
        for (Map.Entry<String, String> f : files.entrySet())
            assertEquals(f.getValue(), Files.readString(samples.resolve(f.getKey())).replace("\r\n", "\n"),
                    f.getKey() + " drifted from TelcoFraudCorpus (seed " + TelcoFraudCorpus.SEED
                            + ") - regenerate with env TELCO_FRAUD_REGENERATE=true");
        assertEquals(files, new TelcoFraudCorpus().generate().files(), "the generator is deterministic");
    }

    /**
     * The template ships a ZERO-row seed snapshot of every detection Job's sink, so each per-entity Alert Rule's
     * {@code by} columns (and each KPI's fields) can be checked against a real Schema when {@code POST /spaces}
     * seeds the template — that save gate fails closed on a Dataset with no data yet. The first Job run swaps the
     * seed out. Regenerated (from each Job's own SQL over empty, schema-typed feeds) with the corpus.
     */
    @Test
    void everyDetectionJobShipsAnEmptySeedSnapshotOfItsSink() throws Exception {
        Path tpl = template();
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> feed : FEED_COLUMNS.entrySet())
                st.execute("CREATE TABLE " + feed.getKey() + " AS SELECT * FROM read_csv('" + emptyCsv(feed.getValue())
                        + "', header = true, columns = " + feed.getValue() + ")");
            for (JobConfig j : jobs(tpl.resolve("config"))) {
                String sql = j.params().get("sql");
                for (Map.Entry<String, String> p : j.params().entrySet())
                    sql = sql.replace("$" + p.getKey(), "'" + p.getValue().replace("'", "''") + "'");
                Path seed = tpl.resolve("data").resolve(j.params().get("sink_dataset")).resolve("seed.parquet");
                if ("true".equals(System.getenv("TELCO_FRAUD_REGENERATE"))) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (" + sql + " LIMIT 0) TO '" + seed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), j.name() + " has no seed snapshot at " + seed);
                assertEquals(describe(st, "(" + sql + " LIMIT 0)"),
                        describe(st, "read_parquet('" + seed.toString().replace('\\', '/') + "')"),
                        j.name() + ": the seed snapshot's Schema drifted from the Job's SQL - regenerate");
                try (var rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + seed.toString().replace('\\', '/') + "')")) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), j.name() + ": a seed snapshot carries no rows");
                }
            }
        }
    }

    private static String emptyCsv(String columns) throws Exception {
        Path f = Files.createTempFile("telco-fraud-empty", ".csv");
        f.toFile().deleteOnExit();
        StringBuilder header = new StringBuilder();
        for (String part : columns.substring(1, columns.length() - 1).split(",")) {
            if (!header.isEmpty()) header.append(',');
            header.append(part.split(":")[0].replace("'", ""));
        }
        Files.writeString(f, header + "\n");
        return f.toString().replace('\\', '/');
    }

    private static List<String> describe(Statement st, String relation) throws Exception {
        List<String> out = new ArrayList<>();
        try (var rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
    }

    private static List<JobConfig> jobs(Path config) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        try (Stream<Path> files = Files.list(config.resolve("jobs"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("_job.toon")).sorted().toList())
                jobs.add(JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(f.toString()), "job"))));
        }
        return jobs;
    }

    @Test
    void everyTypologyRaisesExactlyItsPlantedOffendersAndNoLookAlike(@TempDir Path root) throws Exception {
        copyTree(template(), root.resolve("_templates").resolve("telco-fraud"));
        Path space = root.resolve("fraud");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"fraud\",\"template\":\"telco-fraud\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }

        Path data = space.resolve("data"), config = space.resolve("config");
        land(data);

        // ---- the ten detection Jobs, loaded as ServiceBootstrap loads *_job.toon, run as authored
        List<JobConfig> jobs = jobs(config);
        assertEquals(10, jobs.size(), "one detection Job per typology");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (JobConfig j : jobs) {
                assertTrue(js.triggerRun(j.name(), null).isPresent(), j.name());
                JobRun run = await(js, j.name());
                assertEquals("SUCCESS", run.status(), j.name() + ": " + run.message());
            }
        }

        // ---- the ten per-entity Alert Rules, armed as ServiceBootstrap arms them
        List<AlertRule> rules = new ArrayList<>();
        for (ComponentRegistry.Component c : new ComponentStore(config.resolve("registry")).list("alert-rule"))
            rules.add(AlertRule.fromMap(c.content()));
        assertEquals(EXPECTED.keySet(), new TreeSet<>(rules.stream().map(AlertRule::name).toList()));
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> config, () -> data);

        TelcoFraudCorpus corpus = new TelcoFraudCorpus().generate();
        Set<String> everyRaisedKey = new HashSet<>();
        for (AlertRule r : rules) {
            assertFalse(r.by().isEmpty(), r.name() + " is per-entity");
            DatasetMeasureProbe.Breaches b = probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                    r.threshold(), r.stormCap()).orElseThrow(() -> new AssertionError(r.name() + ": probe failed"));
            Set<String> raised = new TreeSet<>();
            for (DatasetMeasureProbe.Breach br : b.keys()) raised.add(String.valueOf(br.key().get(r.by().get(0))));
            assertEquals(new TreeSet<>(corpus.offenders.get(r.name())), raised,
                    r.name() + " must raise exactly its planted offenders");
            everyRaisedKey.addAll(raised);
        }
        for (Map.Entry<String, Set<String>> alike : corpus.lookAlikes.entrySet())
            for (String k : alike.getValue())
                assertFalse(everyRaisedKey.contains(k), "look-alike " + k + " (" + alike.getKey() + ") raised an Alert");

        // ---- and through the Alert engine: exactly one Alert per offender, per rule
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore());
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        Map<String, Integer> fired = new TreeMap<>();
        for (Alert a : svc.evaluateRules()) fired.merge(a.rule(), 1, Integer::sum);
        assertEquals(EXPECTED, fired, "golden Alert count per typology");
        assertEquals(30, fired.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of(), svc.evaluateRules(), "a re-evaluation raises nothing new");
    }

    /** Land each feed's samples as Parquet under {@code data/<feed>/database}, typed as its schema declares. */
    private static void land(Path data) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> feed : FEED_COLUMNS.entrySet()) {
                Path dir = Files.createDirectories(data.resolve(feed.getKey()).resolve("database"));
                String csv = data.resolve("samples").resolve(feed.getKey()).toString().replace('\\', '/') + "/*.csv";
                st.execute("COPY (SELECT * FROM read_csv('" + csv + "', header = true, columns = " + feed.getValue()
                        + ")) TO '" + dir.resolve(feed.getKey() + ".parquet").toString().replace('\\', '/')
                        + "' (FORMAT PARQUET)");
            }
        }
    }

    private static JobRun await(JobService js, String name) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Optional<JobRun> r;
        while ((r = js.lastRunOf(name)).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        return r.orElseThrow(() -> new AssertionError(name + ": no run within 30s"));
    }

    private static void copyTree(Path src, Path dst) throws Exception {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path t = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t);
            }
        }
    }

    private static ConfigSource noPipelines() {
        return new ConfigSource() {
            @Override public List<PipelineConfig> pipelines() { return List.of(); }
            @Override public List<EnrichmentConfig> enrichments() { return List.of(); }
            @Override public List<SemanticModel> semantics() { return List.of(); }
        };
    }

    private static StatusStore emptyStore() {
        return new StatusStore() {
            @Override public Set<String> committedBatches(PipelineConfig cfg) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig cfg) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig cfg, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig cfg) { return List.of(); }
        };
    }
}
