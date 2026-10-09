package com.gamma.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.Roles;
import com.gamma.alert.Alert;
import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.control.ControlApi;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.StatusStore;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.metrics.MetricRegistry;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.SpaceConfigRoot;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.recon.ReconBreaks;
import com.gamma.recon.ReconRunJob;
import com.gamma.recon.ReconStateStore;
import com.gamma.service.SpaceManager;
import com.gamma.signal.SignalEmitter;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-MOBILE-MONEY-1} golden test. Boots the shipped {@code spaces/_templates/mobile-money} Space Template
 * through {@code POST /spaces} (every seed gate), ingests the synthetic corpus through the template's own five
 * Pipelines on the production Consignment path ({@link CollectorProcessor#run}), runs both Reconciliations through
 * their {@code recon.run} Jobs and the seven {@code sql.template} detection Jobs as authored, then evaluates the
 * per-entity Alert Rules with the production grouped Measure probe and {@link AlertService}. Each Reconciliation
 * must record EXACTLY its planted Breaks and each Alert Rule raise EXACTLY its planted offenders (one just above
 * its threshold), and no planted look-alike (one exactly at it). The agent Risk Score is proven in the Scoring
 * module ({@code MobileMoneyRiskScoreGoldenTest}): no module carries both add-ons.
 */
class MobileMoneyPackGoldenTest {

    private static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.of(
            "mm_commission", 4, "mm_fee", 4, "mm_agent_split", 4, "mm_round_trip", 3, "mm_dormant", 3,
            "mm_kyc_limit", 4, "mm_provisioning", 4));
    private static final int TOTAL = 26;
    private static final List<String> RECONS = List.of("mm_bank_float", "mm_partner_settlement");

    private static final Map<String, String> FEED_COLUMNS = new LinkedHashMap<>(Map.of(
            "wallet_txn", "{'txn_id':'VARCHAR','txn_ts':'TIMESTAMP','txn_type':'VARCHAR','wallet_id':'VARCHAR',"
                    + "'agent_id':'VARCHAR','partner_id':'VARCHAR','amount':'DECIMAL(18,2)','fee':'DECIMAL(18,2)',"
                    + "'commission':'DECIMAL(18,2)'}",
            "bank_statement", "{'stmt_line_id':'VARCHAR','value_ts':'TIMESTAMP','bank_ref':'VARCHAR',"
                    + "'direction':'VARCHAR','amount':'DECIMAL(18,2)'}",
            "partner_settlement", "{'line_id':'VARCHAR','settle_date':'DATE','partner_id':'VARCHAR',"
                    + "'txn_ref':'VARCHAR','amount':'DECIMAL(18,2)'}",
            "wallets", "{'snapshot_date':'DATE','wallet_id':'VARCHAR','msisdn':'VARCHAR','kyc_tier':'INTEGER',"
                    + "'status':'VARCHAR','last_activity_date':'DATE'}",
            "core_subscribers", "{'snapshot_date':'DATE','msisdn':'VARCHAR','status':'VARCHAR'}"));

    private static final String UNION = ") SELECT * FROM cur UNION ALL BY NAME ";
    private static final Map<String, String> DAY1 =
            Map.of("window_start", "2026-07-01 00:00:00", "window_end", "2026-07-02 00:00:00");

    private static boolean regenerate() { return Boolean.getBoolean("mobilemoney.regenerate"); }

    private static Path template() {
        Path p = Path.of("..", "..", "spaces", "_templates", "mobile-money").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(p), "the shipped template is missing: " + p);
        return p;
    }

    @BeforeEach
    void arm() {
        SpaceConfigRoot.clear();
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER,
                Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES, Roles.CAN_ONBOARD_CONNECTIONS,
                Roles.CAN_MANAGE_INCIDENTS, "canWorkIncidents"))));
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        MetricRegistry.global().reset();
        System.clearProperty("assist.write.root");
        SpaceConfigRoot.clear();
    }

    @Test
    void theCommittedCorpusIsTheSeededGeneratorsOutput() throws Exception {
        Map<String, String> files = new MobileMoneyCorpus().generate().files();
        Path samples = template().resolve("data").resolve("samples");
        if (regenerate())
            for (Map.Entry<String, String> f : files.entrySet()) {
                Files.createDirectories(samples.resolve(f.getKey()).getParent());
                Files.writeString(samples.resolve(f.getKey()), f.getValue());
            }
        for (Map.Entry<String, String> f : files.entrySet())
            assertEquals(f.getValue(), Files.readString(samples.resolve(f.getKey())).replace("\r\n", "\n"),
                    f.getKey() + " drifted from MobileMoneyCorpus (seed " + MobileMoneyCorpus.SEED
                            + ") - regenerate with -Dmobilemoney.regenerate=true");
        assertEquals(files, new MobileMoneyCorpus().generate().files(), "the generator is deterministic");
    }

    @Test
    void everyDetectionJobRollsItsWindowOnADailySchedule() throws Exception {
        List<JobConfig> jobs = sqlJobs(template().resolve("config"));
        assertEquals(EXPECTED.keySet(), new TreeSet<>(jobs.stream().map(JobConfig::name).toList()));
        for (JobConfig j : jobs) {
            assertEquals("0 3 * * *", j.cron(), j.name() + " cron");
            assertEquals("$yesterday", j.params().get("window_start"), j.name());
            assertEquals("$today", j.params().get("window_end"), j.name());
        }
    }

    /**
     * The template ships a ZERO-row seed snapshot of every detection Job's sink, so the seed gate can check each
     * Alert Rule's {@code by} column and each KPI's fields against a real Schema, and each Job has its own sink to
     * read on its first run. Regenerated from each Job's own SQL over empty, schema-typed feeds.
     */
    @Test
    void everyDetectionJobShipsAnEmptySeedSnapshotOfItsSink() throws Exception {
        Path tpl = template();
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> feed : FEED_COLUMNS.entrySet())
                st.execute("CREATE TABLE " + feed.getKey() + " AS SELECT * FROM read_csv('" + emptyCsv(feed.getValue())
                        + "', header = true, columns = " + feed.getValue() + ")");
            for (JobConfig j : sqlJobs(tpl.resolve("config"))) {
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
    void everyControlFlagsExactlyItsPlantedCasesAndNoLookAlike(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path data = space.resolve("data"), config = space.resolve("config");
        ingest(space);
        MobileMoneyCorpus corpus = new MobileMoneyCorpus().generate();

        // ── the two Reconciliations, through the template's own recon.run Jobs ──
        System.setProperty("assist.write.root", config.toString());
        for (int run = 1; run <= 2; run++)   // a re-run records the same Breaks, no duplicate
            for (String recon : RECONS) {
                JobConfig cfg = JobConfig.load(config.resolve("jobs").resolve(recon + "_job.toon").toString());
                assertEquals("recon.run", cfg.type());
                JobResult r = new ReconRunJob(cfg, data.toString(), () -> null).run(new Ctx(cfg.params()));
                assertEquals("SUCCESS", r.status(), r.message());
                assertTrue(r.message().contains(": " + corpus.breaks.get(recon).size() + " break(s)"), recon + ": " + r.message());
                List<String> flagged = new ArrayList<>();
                for (ReconBreaks.Break b : new ReconStateStore(config).read(recon).breaks())
                    flagged.add(b.pair() + "|" + b.type() + "|" + b.key());
                assertEquals(new TreeSet<>(corpus.breaks.get(recon)), new TreeSet<>(flagged), recon + ": exactly the planted Breaks");
                assertEquals(flagged.size(), new HashSet<>(flagged).size(), recon + " run " + run + ": one record per Break");
                for (String benign : corpus.benignBreaks.get(recon))
                    assertTrue(flagged.stream().noneMatch(k -> k.endsWith("|" + benign)), recon + " flagged benign " + benign);
            }

        // ── the seven sql.template typologies and their per-entity Alert Rules ──
        List<JobConfig> jobs = sqlJobs(config);
        List<AlertRule> rules = rules(config);
        assertEquals(EXPECTED.keySet(), new TreeSet<>(rules.stream().map(AlertRule::name).toList()));
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> config, () -> data);
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore());
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), DAY1).status(), j.name());

            assertRaisesExactlyThePlanted(rules, probe, corpus);
            Map<String, Integer> fired = new TreeMap<>();
            for (Alert a : svc.evaluateRules()) fired.merge(a.rule(), 1, Integer::sum);
            assertEquals(EXPECTED, fired, "golden Alert count per typology");
            assertEquals(TOTAL, fired.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(List.of(), svc.evaluateRules(), "a re-evaluation raises nothing new");

            // Re-running the SAME window replaces that window's rows: no duplicates, no Alert change.
            Map<String, Long> rows = sinkRows(data, jobs);
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), DAY1).status(), j.name() + " re-run");
            assertEquals(rows, sinkRows(data, jobs), "a same-window re-run duplicates no row");
            assertRaisesExactlyThePlanted(rules, probe, corpus);
            assertEquals(List.of(), svc.evaluateRules(), "a same-window re-run changes no Alert");

            // The NEXT (empty) window keeps day 1's rows: its offenders stay breached, nothing new is raised.
            Map<String, String> day2 = Map.of("window_start", "2026-07-02 00:00:00", "window_end", "2026-07-03 00:00:00");
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), day2).status(), j.name() + " day 2");
            assertRaisesExactlyThePlanted(rules, probe, corpus);
            assertEquals(List.of(), svc.evaluateRules(), "the next window neither raises nor heals day 1's Alerts");
        }
    }

    /** Every tile on {@code mobile_money_overview} renders after the golden run, through the routes the SPA uses. */
    @Test
    void everyDashboardTileRendersThePlantedOffendersAfterTheGoldenRun(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path data = space.resolve("data"), config = space.resolve("config");
        ingest(space);
        List<JobConfig> jobs = sqlJobs(config);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), DAY1).status(), j.name());
        }
        MobileMoneyCorpus corpus = new MobileMoneyCorpus().generate();
        ObjectMapper json = new ObjectMapper();
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            String base = "http://localhost:" + api.port() + "/api/v1/spaces/mm";
            JsonNode dash = body(call(base + "/components/dashboard/mobile_money_overview", null)).get("content");
            Set<String> typologiesShown = new TreeSet<>();
            int tiles = 0;
            for (JsonNode tile : dash.get("tiles")) {
                tiles++;
                String id = tile.get("widgetId").asText();
                JsonNode w = body(call(base + "/components/widget/" + id, null)).get("content");
                if ("kpi".equals(w.get("vizType").asText())) {
                    String kpi = w.get("options").get("kpi").get("kpiId").asText();
                    JsonNode v = body(call(base + "/kpis/" + kpi + "/value?asOf=2026-07-01", null));
                    assertTrue(v.get("value").asDouble() > 0, id + ": the KPI tile has a value: " + v);
                    continue;
                }
                assertEquals("bar", w.get("vizType").asText(), id);
                String dataset = w.get("datasetId").asText();
                String x = w.get("controls").get("x").get(0).get("field").asText();
                JsonNode y = w.get("controls").get("y").get(0);
                String agg = y.get("agg").asText(), field = y.get("field").asText();
                String body = json.writeValueAsString(Map.of("dataset", dataset, "groupBy", List.of(x),
                        "measures", List.of(Map.of("agg", agg, "field", field))));
                Map<String, Double> bars = new TreeMap<>();
                for (JsonNode r : body(call(base + "/bi/query", body)).get("rows"))
                    bars.put(r.get(x).asText(), r.get(agg + "_" + field).asDouble());
                for (String offender : corpus.offenders.get(dataset))
                    assertTrue(bars.getOrDefault(offender, 0.0) > 0, id + ": planted offender " + offender + " has a bar: " + bars);
                typologiesShown.add(dataset);
            }
            assertEquals(11, tiles, "four KPI tiles and seven typology Widgets");
            assertEquals(EXPECTED.keySet(), typologiesShown, "every typology has a Widget on the dashboard");
        } finally {
            api.close();
            spaces.close();
        }
    }

    private static void assertRaisesExactlyThePlanted(List<AlertRule> rules, DatasetMeasureProbe probe, MobileMoneyCorpus corpus) {
        Set<String> everyRaisedKey = new HashSet<>();
        for (AlertRule r : rules) {
            assertEquals(1, r.by().size(), r.name() + " is keyed on the offender alone");
            DatasetMeasureProbe.Breaches b = probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                    r.threshold(), r.stormCap()).orElseThrow(() -> new AssertionError(r.name() + ": probe failed"));
            Set<String> raised = new TreeSet<>();
            for (DatasetMeasureProbe.Breach br : b.keys()) raised.add(String.valueOf(br.key().get(r.by().get(0))));
            assertEquals(new TreeSet<>(corpus.offenders.get(r.name())), raised, r.name() + " must raise exactly its planted offenders");
            everyRaisedKey.addAll(raised);
        }
        for (Map.Entry<String, Set<String>> alike : corpus.lookAlikes.entrySet())
            for (String k : alike.getValue())
                assertFalse(everyRaisedKey.contains(k), "look-alike " + k + " (" + alike.getKey() + ") raised an Alert");
    }

    /** The v1 envelope's {@code data}, or the body itself. */
    private static JsonNode body(String raw) throws Exception {
        JsonNode n = new ObjectMapper().readTree(raw);
        return n.has("data") ? n.get("data") : n;
    }

    private static String call(String url, String jsonBody) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer admin");
        if (jsonBody != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), url + " -> " + r.body());
        return r.body();
    }

    private static Map<String, Long> sinkRows(Path data, List<JobConfig> jobs) throws Exception {
        Map<String, Long> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (JobConfig j : jobs) {
                String sink = j.params().get("sink_dataset");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('"
                        + data.resolve(sink).toString().replace('\\', '/') + "/*.parquet')")) {
                    rs.next();
                    out.put(sink, rs.getLong(1));
                }
            }
        }
        return out;
    }

    /** {@code POST /spaces} with the template (every seed gate); the Space is closed again before the test drives it. */
    private static Path createSpace(Path root) throws Exception {
        copyTree(template(), root.resolve("_templates").resolve("mobile-money"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"mm\",\"template\":\"mobile-money\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }
        SpaceConfigRoot.clear();
        Path space = root.resolve("mm");
        assertTrue(Files.isRegularFile(space.resolve("config/runbooks/mobile-money-runbooks.md")), "the runbook ships");
        return space;
    }

    /** Ingest each feed's samples through the template's own Pipeline on the production Consignment path. */
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

    /** The template's {@code sql.template} detection Jobs (the recon.run and risk.score Jobs run elsewhere). */
    private static List<JobConfig> sqlJobs(Path config) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        try (Stream<Path> files = Files.list(config.resolve("jobs"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("_job.toon")).sorted().toList()) {
                JobConfig j = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(f.toString()), "job")));
                if ("sql.template".equals(j.type())) jobs.add(j);
            }
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
        Path f = Files.createTempFile("mobile-money-empty", ".csv");
        f.toFile().deleteOnExit();
        StringBuilder header = new StringBuilder();
        for (String part : columns.substring(1, columns.length() - 1).split(",'")) {
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

    private record Ctx(Map<String, String> params) implements JobContext {
        @Override public String runId() { return "golden"; }
        @Override public String spaceId() { return "default"; }
        @Override public TriggerInfo trigger() { return null; }
        @Override public Map<String, String> config() { return params; }
        @Override public RunLog log() {
            return new RunLog() {
                @Override public void info(String message, Object... kv) {}
                @Override public void warn(String message, Object... kv) {}
                @Override public void error(String message, Throwable t, Object... kv) {}
            };
        }
        @Override public SignalEmitter signals() { return (type, sev, payload) -> { }; }
        @Override public ArtifactRecorder artifacts() { return null; }
    }
}
