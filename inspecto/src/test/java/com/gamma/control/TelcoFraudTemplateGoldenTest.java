package com.gamma.control;

import com.gamma.alert.Alert;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.service.SpaceManager;
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
import java.sql.ResultSet;
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
 * Template through {@code POST /spaces} (every seed gate), ingests the synthetic corpus through the template's
 * own three Pipelines on the production Consignment path ({@link CollectorProcessor#run}), runs all ten
 * {@code sql.template} detection Jobs as authored, then evaluates the per-entity Alert Rules with the production
 * grouped Measure probe and {@link AlertService}. Each Alert Rule must raise EXACTLY its planted offenders
 * (including one just above its threshold) and no planted look-alike (including one exactly at it).
 */
class TelcoFraudTemplateGoldenTest {

    /** Expected Alerts per Alert Rule — the golden counts. */
    private static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.of(
            "fraud_irsf", 5, "fraud_wangiri", 4, "fraud_simbox", 4, "fraud_premium_rate", 5, "fraud_roaming", 4,
            "fraud_sim_swap", 4, "fraud_identity", 3, "fraud_dealer", 3, "fraud_voucher", 4, "fraud_reversal", 4));
    private static final int TOTAL = 40;

    private static final Map<String, String> FEED_COLUMNS = Map.of(
            "cdr", "{'record_id':'VARCHAR','start_ts':'TIMESTAMP','direction':'VARCHAR','service':'VARCHAR',"
                    + "'a_number':'VARCHAR','b_number':'VARCHAR','duration_s':'INTEGER','charge':'DOUBLE',"
                    + "'roaming':'VARCHAR','cell_id':'VARCHAR'}",
            "subscriber_events", "{'event_id':'VARCHAR','event_ts':'TIMESTAMP','event_type':'VARCHAR',"
                    + "'msisdn':'VARCHAR','dealer_id':'VARCHAR','id_doc':'VARCHAR'}",
            "payments", "{'txn_id':'VARCHAR','txn_ts':'TIMESTAMP','txn_type':'VARCHAR','msisdn':'VARCHAR',"
                    + "'amount':'DOUBLE','voucher_serial':'VARCHAR','ref_txn_id':'VARCHAR'}");

    private static final String UNION = ") SELECT * FROM cur UNION ALL BY NAME ";

    private static boolean regenerate() { return "true".equals(System.getenv("TELCO_FRAUD_REGENERATE")); }

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
        if (regenerate())
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
     * seeds the template — that save gate fails closed on a Dataset with no data yet — and so each Job, which
     * reads its own sink to keep earlier windows, has a relation to read on its first run. Regenerated from each
     * Job's own SQL over empty, schema-typed feeds.
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
                String sink = j.params().get("sink_dataset");
                String sql = substitute(j.params().get("sql"), j.params());
                Path seed = tpl.resolve("data").resolve(sink).resolve("seed.parquet");
                String seedRel = "read_parquet('" + seed.toString().replace('\\', '/') + "')";
                assertTrue(sql.startsWith("WITH cur AS (") && sql.contains(UNION),
                        j.name() + " keeps earlier windows by reading its own sink");
                String cur = sql.substring("WITH cur AS (".length(), sql.lastIndexOf(UNION));
                if (regenerate()) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (SELECT * FROM (" + cur + ") LIMIT 0) TO '" + seed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), j.name() + " has no seed snapshot at " + seed);
                st.execute("CREATE OR REPLACE TABLE " + sink + " AS SELECT * FROM " + seedRel);
                assertEquals(describe(st, "(" + sql + " LIMIT 0)"), describe(st, seedRel),
                        j.name() + ": the seed snapshot's Schema drifted from the Job's SQL - regenerate");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + seedRel)) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), j.name() + ": a seed snapshot carries no rows");
                }
            }
        }
    }

    @Test
    void everyTypologyRaisesExactlyItsPlantedOffendersAndNoLookAlike(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path data = space.resolve("data"), config = space.resolve("config");
        ingest(space);

        List<JobConfig> jobs = jobs(config);
        assertEquals(10, jobs.size(), "one detection Job per typology");
        List<AlertRule> rules = rules(config);
        assertEquals(EXPECTED.keySet(), new TreeSet<>(rules.stream().map(AlertRule::name).toList()));
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> config, () -> data);
        TelcoFraudCorpus corpus = new TelcoFraudCorpus().generate();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore());
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));

        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), Map.of()).status(), j.name());

            assertRaisesExactlyThePlanted(rules, probe, corpus);
            Map<String, Integer> fired = new TreeMap<>();
            for (Alert a : svc.evaluateRules()) fired.merge(a.rule(), 1, Integer::sum);
            assertEquals(EXPECTED, fired, "golden Alert count per typology");
            assertEquals(TOTAL, fired.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(List.of(), svc.evaluateRules(), "a re-evaluation raises nothing new");

            // The NEXT window's run keeps the earlier window's rows: its offenders do not vanish from the Dataset
            // (which would auto-resolve their Alerts with nobody acting), and nothing new is raised.
            Map<String, String> day2 = Map.of("window_start", "2026-07-02 00:00:00", "window_end", "2026-07-03 00:00:00");
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), day2).status(), j.name() + " day 2");
            assertRaisesExactlyThePlanted(rules, probe, corpus);
            assertEquals(List.of(), svc.evaluateRules(), "the next window neither raises nor heals day 1's Alerts");
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(DISTINCT window_date) FROM read_parquet('"
                         + data.resolve("fraud_reversal").toString().replace('\\', '/') + "/*.parquet')")) {
                rs.next();
                assertEquals(2, rs.getLong(1), "the sink holds both windows (day 2 has one reversal-less payment)");
            }
        }
    }

    /** A malformed prefix list fails the run instead of silently matching nothing. */
    @Test
    void aMalformedPrefixListFailsTheRun(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        ingest(space);
        List<JobConfig> jobs = jobs(space.resolve("config")).stream()
                .filter(j -> j.name().equals("fraud_irsf") || j.name().equals("fraud_premium_rate")).toList();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            assertEquals("SUCCESS", runJob(js, "fraud_irsf", Map.of()).status());
            JobRun bad = runJob(js, "fraud_irsf", Map.of("irsf_prefixes", "882,+883"));
            assertNotEquals("SUCCESS", bad.status(), "a '+' in a prefix list entry must fail: " + bad.message());
            JobRun blank = runJob(js, "fraud_premium_rate", Map.of("premium_prefixes", "999900,,999909"));
            assertNotEquals("SUCCESS", blank.status(), "an empty prefix list entry must fail: " + blank.message());
        }
    }

    private static void assertRaisesExactlyThePlanted(List<AlertRule> rules, DatasetMeasureProbe probe,
                                                      TelcoFraudCorpus corpus) {
        Set<String> everyRaisedKey = new HashSet<>();
        for (AlertRule r : rules) {
            assertEquals(List.of(r.by().get(0), "window_date"), r.by(), r.name() + " is keyed on offender + window");
            DatasetMeasureProbe.Breaches b = probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                    r.threshold(), r.stormCap()).orElseThrow(() -> new AssertionError(r.name() + ": probe failed"));
            Set<String> raised = new TreeSet<>();
            for (DatasetMeasureProbe.Breach br : b.keys()) {
                raised.add(String.valueOf(br.key().get(r.by().get(0))));
                assertEquals("2026-07-01", String.valueOf(br.key().get("window_date")), r.name());
            }
            assertEquals(new TreeSet<>(corpus.offenders.get(r.name())), raised,
                    r.name() + " must raise exactly its planted offenders");
            everyRaisedKey.addAll(raised);
        }
        for (Map.Entry<String, Set<String>> alike : corpus.lookAlikes.entrySet())
            for (String k : alike.getValue())
                assertFalse(everyRaisedKey.contains(k), "look-alike " + k + " (" + alike.getKey() + ") raised an Alert");
    }

    /** {@code POST /spaces} with the template (every seed gate); the Space is closed again before the test drives it. */
    private static Path createSpace(Path root) throws Exception {
        copyTree(template(), root.resolve("_templates").resolve("telco-fraud"));
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
        return root.resolve("fraud");
    }

    /**
     * Ingest each feed's samples through the template's own Pipeline on the production Consignment path:
     * drop them in the poll dir, run the Collector, and require every CSV row to land in the feed's Parquet.
     */
    private static void ingest(Path space) throws Exception {
        DuckDbUtil.loadDriver();
        for (String feed : FEED_COLUMNS.keySet()) {
            PipelineConfig cfg = PipelineConfig.load(space.resolve("config").resolve(feed)
                    .resolve(feed + "_pipeline.toon").toString());
            Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
            assertTrue(poll.startsWith(space), feed + " polls outside its Space: " + poll);
            Files.createDirectories(poll);
            long csvRows = 0;
            try (Stream<Path> samples = Files.list(space.resolve("data").resolve("samples").resolve(feed))) {
                for (Path csv : samples.toList()) {
                    Files.copy(csv, poll.resolve(csv.getFileName()));
                    csvRows += Files.readAllLines(csv).size() - 1;
                }
            }
            CollectorProcessor.run(cfg);
            Path db = Path.of(cfg.dirs().database()).toAbsolutePath().normalize();
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('"
                         + db.toString().replace('\\', '/') + "/**/*.parquet')")) {
                rs.next();
                assertEquals(csvRows, rs.getLong(1), feed + ": the Pipeline must ingest every corpus row");
            }
        }
    }

    private static JobRun runJob(JobService js, String name, Map<String, String> args) throws Exception {
        String prior = js.lastRunOf(name).map(JobRun::runId).orElse(null);
        assertTrue(js.triggerRun(name, null, args).isPresent(), name);
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<JobRun> r = js.lastRunOf(name);
            if (r.isPresent() && !r.get().runId().equals(prior)) return r.get();
            Thread.sleep(50);
        }
        throw new AssertionError(name + ": no run within 30s");
    }

    private static List<AlertRule> rules(Path config) {
        List<AlertRule> rules = new ArrayList<>();
        for (ComponentRegistry.Component c : new ComponentStore(config.resolve("registry")).list("alert-rule"))
            rules.add(AlertRule.fromMap(c.content()));
        return rules;
    }

    private static List<JobConfig> jobs(Path config) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        try (Stream<Path> files = Files.list(config.resolve("jobs"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("_job.toon")).sorted().toList())
                jobs.add(JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(f.toString()), "job"))));
        }
        return jobs;
    }

    /** {@code $name} → quoted literal, longest name first (as the Job framework substitutes). */
    private static String substitute(String sql, Map<String, String> params) {
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) sql = sql.replace("$" + n, "'" + params.get(n).replace("'", "''") + "'");
        return sql;
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
        try (ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
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
