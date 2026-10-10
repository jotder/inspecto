package com.gamma.pack;

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
import com.gamma.mask.EvidenceMasker;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import com.gamma.workflow.ObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-AML-1}: the {@code aml} Space Template RUN end to end from a verbatim copy. The two Pipelines ingest the
 * committed synthetic corpus ({@link AmlCorpus}); the four {@code sql.template} typology Jobs build their Datasets
 * for the golden window; the {@code aml_account} Risk Score is evaluated as the {@code risk.score} Job does; every
 * Alert Rule is swept by the production {@link AlertService}. The assertions are EXACT per typology: the planted
 * offenders and nothing else — every look-alike (AT the threshold, outside the look-back, of another kind) is silent.
 */
class AmlPackGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "aml").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("aml_txn", "aml_parties");
    private static final List<String> JOBS = List.of("aml_structuring", "aml_threshold", "aml_fan_in", "aml_high_risk_traffic");
    private static final Map<String, String> DAY7 = Map.of("window_start", "2026-07-07 00:00:00", "window_end", "2026-07-08 00:00:00");
    private static final String TXN_DDL = "CREATE TABLE aml_txn (txn_id VARCHAR, txn_ts TIMESTAMP, txn_type VARCHAR, "
            + "account_id VARCHAR, counterparty_id VARCHAR, amount DECIMAL(18,2), country VARCHAR)";
    private static final String UNION = ") SELECT * FROM cur UNION ALL BY NAME ";

    private static boolean regenerate() { return Boolean.getBoolean("aml.regenerate"); }

    @Test
    void theCommittedCorpusIsTheSeededGeneratorsOutput() throws Exception {
        Map<String, String> files = AmlCorpus.files();
        Path samples = TEMPLATE.resolve("data/samples");
        if (regenerate())
            for (Map.Entry<String, String> f : files.entrySet()) {
                Files.createDirectories(samples.resolve(f.getKey()).getParent());
                Files.writeString(samples.resolve(f.getKey()), f.getValue());
            }
        Set<String> committed = new TreeSet<>();
        try (Stream<Path> w = Files.walk(samples)) {
            w.filter(Files::isRegularFile).forEach(p -> committed.add(samples.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(new TreeSet<>(files.keySet()), committed, "the sample files are the generator's, no more");
        for (Map.Entry<String, String> f : files.entrySet())
            assertEquals(f.getValue(), Files.readString(samples.resolve(f.getKey())).replace("\r\n", "\n"),
                    f.getKey() + " drifted from AmlCorpus (seed " + AmlCorpus.SEED + ") - regenerate with -Daml.regenerate=true");
        assertEquals(files, AmlCorpus.files(), "the generator is deterministic");
    }

    /** A zero-row seed of every sink, so the seed gate can check Alert Rule and KPI fields and each Job has a sink to read first. */
    @Test
    void everyJobShipsAnEmptySeedOfItsSinkMatchingItsSql() throws Exception {
        DuckDbUtil.loadDriver();
        try (var c = DriverManager.getConnection("jdbc:duckdb:"); var st = c.createStatement()) {
            st.execute(TXN_DDL);
            for (String j : JOBS) {
                JobConfig cfg = JobConfig.load(TEMPLATE.resolve("config/jobs/" + j + "_job.toon").toString());
                assertEquals("sql.template", cfg.type());
                assertEquals("0 3 * * *", cfg.cron(), j);
                assertEquals("$yesterday", cfg.params().get("window_start"), j);
                assertEquals("$today", cfg.params().get("window_end"), j);
                String sql = substitute(cfg.params().get("sql"), cfg.params());
                assertTrue(sql.startsWith("WITH cur AS (") && sql.contains(UNION), j + " keeps earlier windows by reading its own sink");
                String cur = sql.substring("WITH cur AS (".length(), sql.lastIndexOf(UNION));
                Path seed = TEMPLATE.resolve("data").resolve(j).resolve("seed.parquet");
                String seedRel = "read_parquet('" + seed.toString().replace('\\', '/') + "')";
                if (regenerate()) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (SELECT * FROM (" + cur + ") LIMIT 0) TO '" + seed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), j + " has no seed snapshot");
                st.execute("CREATE OR REPLACE TABLE " + j + " AS SELECT * FROM " + seedRel);
                assertEquals(describe(st, "(" + sql + " LIMIT 0)"), describe(st, seedRel), j + ": the seed drifted from the Job's SQL - regenerate");
                try (var rs = st.executeQuery("SELECT count(*) FROM " + seedRel)) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), j + ": a seed carries no rows");
                }
            }
        }
    }

    @Test
    void theGoldenCorpusRaisesExactlyThePlantedAccountsPerTypology(@TempDir Path tmp) throws Exception {
        Path space = copyTemplate(tmp);
        Path cfg = space.resolve("config"), data = space.resolve("data");
        for (String feed : FEEDS) {
            PipelineConfig pc = ingest(space, feed);
            assertEquals(0, count(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
        }
        assertEquals(10L, scalar(data, "aml_txn/database", "SELECT count(DISTINCT CAST(txn_ts AS DATE)) FROM \"s\""), "ten daily files");
        assertEquals(126L, scalar(data, "aml_parties/database", "SELECT count(*) FROM \"s\""), "the register snapshot, quoted name included");

        runJobs(space);

        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> modelContent = store.get("risk-score", "aml_account").orElseThrow().content();
        RiskScoreModel model = RiskScoreModel.fromMap("aml_account", modelContent);
        assertEquals(60.0, model.highThreshold());
        var run = RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null),
                EvidenceMasker.of(store, cfg, model.datasetIds()));
        RiskScoreEvaluator.write(data, model, RiskScoreEvaluator.version(modelContent), "golden", Instant.now(), run.scored());

        // The Risk Score's Alert Rule ships PENDING (its Dataset is the Job's own output); create it the documented way.
        Map<String, Object> pending = ToonHelper.load(cfg.resolve("pending/alert-rules/aml_high_risk_account.toon").toString());
        assertEquals(Map.of("kind", "risk-score", "model", "aml_account"), pending.get("afterScore"));
        assertFalse(Files.exists(cfg.resolve("registry/alert-rules/aml_high_risk_account.toon")), "the rule must not ship armed");
        store.write("dataset", "risk_scores_aml_account_latest", Map.of("physicalRef", "risk_scores_aml_account_latest"));
        Map<String, Object> rule = new HashMap<>(pending);
        rule.remove("afterScore");
        rule.put("name", "aml_high_risk_account");
        store.write("alert-rule", "aml_high_risk_account", rule);

        Map<String, Set<String>> detections = sweep(store, cfg, data);
        assertEquals(Map.of(
                "aml_structuring", Set.of("AC100001", "AC100002"),
                "aml_threshold", Set.of("AC200001", "AC200002"),
                "aml_fan_in", Set.of("AC300001", "AC300002"),
                "aml_high_risk_traffic", Set.of("AC400001", "AC400002"),
                "aml_high_risk_account", Set.of("AC500001", "AC500002")), detections,
                "exactly the planted offenders per typology - every look-alike stays silent");

        Map<String, Double> high = new TreeMap<>();
        run.scored().stream().filter(s -> s.score() >= model.highThreshold()).forEach(s -> high.put(s.entityKey(), s.score()));
        assertEquals(Map.of("AC500001", 60.0, "AC500002", 66.0), high,
                "R1: 4 near-limit deposits x 10 + 2 high-risk wires x 10; R2: 9 payers x 4 + 3 near-limit x 10 - each signal silent alone");
        Map<String, Double> all = new TreeMap<>();
        run.scored().forEach(s -> all.put(s.entityKey(), s.score()));
        assertEquals(50.0, all.get("AC100001"), "5 near-limit deposits x 10, capped at 50: an offender, but not high alone");
        assertEquals(40.0, all.get("AC100003"), "the AT-threshold look-alike");
        assertEquals(40.0, all.get("AC300002"), "14 payers x 4, capped at 40");
        assertEquals(20.0, all.get("AC400003"), "the AT-threshold wires: 2 x 10");
    }

    @Test
    void theShippedPartsDeclareWhatTheyNeed() throws Exception {
        Path cfg = TEMPLATE.resolve("config");
        // watch-list traffic is a job parameter list, never a hard-coded country
        JobConfig hr = JobConfig.load(cfg.resolve("jobs/aml_high_risk_traffic_job.toon").toString());
        assertEquals("XA,XB", hr.params().get("high_risk_countries"));
        // the pending Alert Rule reads the model's latest scores
        Map<String, Object> pending = ToonHelper.load(cfg.resolve("pending/alert-rules/aml_high_risk_account.toon").toString());
        assertEquals("risk_scores_aml_account_latest", pending.get("dataset"));
        assertEquals(List.of("model", "entity_key"), pending.get("by"));
        // the screening Job ships OFF: its Entity Lists cannot ship in a template
        String screening = Files.readString(cfg.resolve("jobs/aml_screening_job.toon"));
        assertTrue(screening.contains("type: screening.run") && screening.contains("enabled: false"), screening);
        // every Alert Rule is per entity
        for (String r : JOBS)
            assertEquals(List.of("account_id"), ToonHelper.load(cfg.resolve("registry/alert-rules/" + r + ".toon").toString()).get("by"), r);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private static String substitute(String sql, Map<String, String> params) {
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        Map<String, String> p = new HashMap<>(params);
        p.putAll(DAY7);
        for (String n : names) sql = sql.replace("$" + n, "'" + p.get(n).replace("'", "''") + "'");
        return sql;
    }

    private static List<String> describe(java.sql.Statement st, String relation) throws Exception {
        List<String> out = new ArrayList<>();
        try (var rs = st.executeQuery("DESCRIBE SELECT * FROM " + relation)) {
            while (rs.next()) out.add(rs.getString("column_name") + " " + rs.getString("column_type"));
        }
        return out;
    }

    static Path copyTemplate(Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("aml");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return space;
    }

    private static PipelineConfig ingest(Path space, String feed) throws Exception {
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        try (Stream<Path> f = Files.list(space.resolve("data/samples/" + feed))) {
            for (Path p : f.toList()) Files.copy(p, inbox.resolve(p.getFileName()));
        }
        CollectorProcessor.run(pc);
        return pc;
    }

    private static void runJobs(Path space) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (String j : JOBS) {
                assertTrue(js.triggerRun(j, null, DAY7).isPresent(), j);
                JobRun r = null;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((r = js.lastRunOf(j).orElse(null)) == null && System.nanoTime() < deadline) Thread.sleep(50);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
    }

    private static Map<String, Set<String>> sweep(ComponentStore store, Path cfg, Path data) {
        List<AlertRule> rules = store.list("alert-rule").stream().map(c -> AlertRule.fromMap(c.content())).toList();
        assertEquals(5, rules.size(), "the four typology Alert Rules + the Risk Score's");
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore(), objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(), r.threshold(), r.stormCap()));
        svc.evaluateRules();
        Map<String, Set<String>> out = new TreeMap<>();
        for (FakeObjectAccess.Opened o : objects.opened) {
            if (o.kind() != ObjectType.INCIDENT) continue;
            String rule = String.valueOf(o.attributes().get("rule"));
            String key = o.attributes().entrySet().stream()
                    .filter(e -> e.getKey().startsWith("key.") && !e.getKey().equals("key.model"))
                    .map(e -> String.valueOf(e.getValue())).findFirst().orElse("?");
            out.computeIfAbsent(rule, k -> new TreeSet<>()).add(key);
        }
        return out;
    }

    private static long scalar(Path data, String ref, String sql) throws Exception {
        var rows = com.gamma.query.QueryExecutor.run(new com.gamma.query.QueryExecutor.Request("s",
                DatasetRelation.relationSql(Map.of("physicalRef", ref), data, null), sql, 10, 0, List.of(), List.of())).rows();
        return ((Number) rows.get(0).values().iterator().next()).longValue();
    }

    private static long count(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> w = Files.walk(dir)) {
            return w.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().startsWith(".")).count();
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
            @Override public Set<String> committedBatches(PipelineConfig c) { return Set.of(); }
            @Override public List<Map<String, String>> batches(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> files(PipelineConfig c) { return List.of(); }
            @Override public List<Map<String, String>> lineage(PipelineConfig c, String b) { return List.of(); }
            @Override public List<Map<String, String>> quarantine(PipelineConfig c) { return List.of(); }
        };
    }
}
