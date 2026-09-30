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
import com.gamma.objects.FakeObjectAccess;
import com.gamma.objects.ObjectType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.risk.EvidenceMasker;
import com.gamma.risk.RiskScoreEvaluator;
import com.gamma.risk.RiskScoreModel;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ASSURE-PACK-PAYMENT-FRAUD-1 slice 1: the {@code payment-fraud} Space Template, RUN end to end from a verbatim
 * copy — the three Pipelines ingest the committed synthetic corpus, the four {@code sql.template} feature Jobs
 * build their Datasets, the {@code payment_account} Risk Score is evaluated as the {@code risk.score} Job does,
 * and every shipped Alert Rule is swept by the production {@link AlertService} + {@link DatasetMeasureProbe}.
 * The golden assertion is EXACT per typology: the planted offenders and nothing else — every planted look-alike
 * stays silent.
 */
class PaymentFraudTemplateGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "spaces", "_templates", "payment-fraud").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("payment_attempts", "sim_changes", "disputes");
    private static final List<String> FEATURE_JOBS = List.of("pf_device_small_amounts", "pf_bin_declines",
            "pf_instrument_velocity", "pf_sim_swap_payments", "pf_account_activity");
    /** A well-known PUBLISHED test card number (Luhn-valid, 16 digits) — never a real instrument. */
    private static final String TEST_PAN = "4111111111111111";

    @Test
    void theCommittedCorpusIsExactlyTheSeededGeneratorsOutput() throws Exception {
        Map<String, String> generated = PaymentFraudCorpus.files();
        Path samples = TEMPLATE.resolve("data/samples");
        Set<String> committed = new TreeSet<>();
        try (Stream<Path> w = Files.walk(samples)) {
            w.filter(Files::isRegularFile).forEach(p -> committed.add(samples.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(new TreeSet<>(generated.keySet()), committed, "the sample files are the generator's, no more");
        for (Map.Entry<String, String> e : generated.entrySet())
            assertEquals(e.getValue(), Files.readString(samples.resolve(e.getKey())).replace("\r\n", "\n"),
                    e.getKey() + " drifted from PaymentFraudCorpus — regenerate it with PaymentFraudCorpus.main");
        assertEquals(generated, PaymentFraudCorpus.files(), "the fixed seed makes the corpus deterministic");
    }

    @Test
    void theGoldenCorpusRaisesExactlyThePlantedCasesPerTypology(@TempDir Path tmp) throws Exception {
        Path space = copyTemplate(tmp);
        Path cfg = space.resolve("config");
        Path data = space.resolve("data");
        for (String feed : FEEDS) {
            PipelineConfig pc = ingest(space, feed, true);
            assertEquals(0, count(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
        }
        // The tripwire look-alikes (a Luhn-INVALID 16-digit, a 12-digit and a 20-digit value) all landed.
        assertEquals(3L, scalar(data, "payment_attempts/database",
                "SELECT count(*) FROM \"s\" WHERE ACCOUNT_ID = 'acc_la_pan'"));

        Map<String, String> seeded = new TreeMap<>();
        for (String j : FEATURE_JOBS) seeded.put(j, schema(data, j));
        runFeatureJobs(space);
        for (String j : FEATURE_JOBS) {
            assertFalse(Files.exists(data.resolve(j).resolve("schema-seed.parquet")), j + ": the first run replaced the seed");
            assertEquals(seeded.get(j), schema(data, j), j + ": the shipped zero-row seed declares exactly the Job's schema");
        }

        ComponentStore store = new ComponentStore(cfg.resolve("registry"));
        Map<String, Object> modelContent = store.get("risk-score", "payment_account").orElseThrow().content();
        RiskScoreModel model = RiskScoreModel.fromMap("payment_account", modelContent);
        var run = RiskScoreEvaluator.evaluate(model, id -> DatasetRelation.relationSql(
                store.get("dataset", id).map(ComponentRegistry.Component::content).orElseThrow(), data, null),
                EvidenceMasker.of(store, cfg, model));
        RiskScoreEvaluator.write(data, model, RiskScoreEvaluator.version(modelContent), "golden", Instant.now(), run.scored());

        // The Risk Score's Alert Rule cannot ship in the template (its Dataset is the Job's own output, which does not
        // exist when the seed gate judges it) — it is added the documented way, after the first scoring run.
        store.write("dataset", "risk_scores_payment_account_latest", Map.of("physicalRef", "risk_scores_payment_account_latest"));
        store.write("alert-rule", "pf_high_risk_account", Map.of("name", "pf_high_risk_account",
                "dataset", "risk_scores_payment_account_latest", "measure", "max(score)",
                "by", List.of("model", "entity_key"), "comparator", "gte", "threshold", model.highThreshold(),
                "severity", "CRITICAL", "description", "High payment Risk Score"));
        Map<String, Set<String>> detections = sweep(store, cfg, data);
        assertEquals(Map.of(
                "pf_card_testing", Set.of("dev_ct_01"),
                "pf_bin_attack", Set.of("498765"),
                "pf_velocity_burst", Set.of("tok_vb_01", "tok_vb_02"),
                "pf_sim_swap_takeover", Set.of("acc_ss01", "acc_ss02"),
                "pf_high_risk_account", Set.of("acc_ss01", "acc_ss02", "acc_vb01")), detections,
                "exactly the planted offenders per typology — every look-alike stays silent");

        // The exact scores of the three high accounts, recomputed by hand from the default factor table.
        Map<String, Double> scores = new TreeMap<>();
        run.scored().stream().filter(s -> s.score() >= model.highThreshold()).forEach(s -> scores.put(s.entityKey(), s.score()));
        assertEquals(Map.of("acc_ss01", 65.0, "acc_ss02", 65.0, "acc_vb01", 87.0), scores,
                "ss: 60 (SIM swap) + 5 (velocity 1); vb01: 35 (velocity 7) + 12 (3 declines) + 40 (2 disputes)");
    }

    @Test
    void aLuhnValidCardNumberFailsTheBatchClosedAndLandsNothing(@TempDir Path tmp) throws Exception {
        // Positive control first: the SAME file with a token in that cell ingests both rows — so a zero below is the
        // tripwire's refusal, not a file the poll never picked up.
        for (String planted : List.of("tok_y", TEST_PAN, "4111 1111 1111 1111", "4111-1111-1111-1111")) {
            boolean control = planted.startsWith("tok_");
            Path space = copyTemplate(tmp.resolve("t" + Math.abs(planted.hashCode())));
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/payment_attempts/payment_attempts_pipeline.toon").toString());
            Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
            Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"),
                    "ATTEMPT_ID,ATTEMPT_TS,ATTEMPT_DATE,ACCOUNT_ID,INSTRUMENT_TOKEN,BIN,DEVICE_ID,MERCHANT_ID,AMOUNT,CURRENCY,OUTCOME\n"
                            + "pa_x1,2026-07-04 10:00:00,2026-07-04,acc_x,tok_x,402400,dev_x,m_01,10.00,EUR,APPROVED\n"
                            + "pa_x2,2026-07-04 10:05:00,2026-07-04,acc_x," + planted + ",411111,dev_x,m_01,10.00,EUR,APPROVED\n");
            CollectorProcessor.run(pc);
            assertTrue(Files.exists(Path.of(pc.dirs().statusFilePath())), "'" + planted + "': the poll picked the file up");
            if (control) {
                assertEquals(2L, scalar(space.resolve("data"), "payment_attempts/database", "SELECT count(*) FROM \"s\""),
                        "the control file lands both rows");
                assertTrue(Files.exists(Path.of(pc.dirs().markers(), "PAYMENT_ATTEMPTS_20260704.csv.processed")));
                continue;
            }
            assertEquals(0, count(Path.of(pc.dirs().database())), "'" + planted + "': not one row of the batch landed");
            assertTrue(Files.exists(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"))
                            || count(Path.of(pc.dirs().quarantine())) + count(Path.of(pc.dirs().errors())) > 0,
                    "'" + planted + "': the file is held (inbox / quarantine / errors), never backed up as processed");
            assertFalse(Files.exists(Path.of(pc.dirs().markers(), "PAYMENT_ATTEMPTS_20260704.csv.processed")),
                    "'" + planted + "': the file is not marked processed");
            String status = Files.exists(Path.of(pc.dirs().statusFilePath())) ? Files.readString(Path.of(pc.dirs().statusFilePath())) : "";
            assertFalse(status.contains("4111"), "the refusal never quotes the value: " + status);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────

    private static Path copyTemplate(Path tmp) throws Exception {
        Path space = tmp.resolve("spaces").resolve("payment-fraud");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        return space;
    }

    private static PipelineConfig ingest(Path space, String feed, boolean samples) throws Exception {
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        if (samples)
            try (Stream<Path> f = Files.list(space.resolve("data/samples/" + feed))) {
                for (Path p : f.toList()) Files.copy(p, inbox.resolve(p.getFileName()));
            }
        CollectorProcessor.run(pc);
        return pc;
    }

    private static void runFeatureJobs(Path space) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : FEATURE_JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (String j : FEATURE_JOBS) {
                assertTrue(js.triggerRun(j, null).isPresent(), j);
                JobRun r = null;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((r = js.lastRunOf(j).orElse(null)) == null && System.nanoTime() < deadline) Thread.sleep(50);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
    }

    /** Sweep every shipped Alert Rule once; rule name → the offending key(s) its Incidents name. */
    private static Map<String, Set<String>> sweep(ComponentStore store, Path cfg, Path data) {
        List<AlertRule> rules = store.list("alert-rule").stream().map(c -> AlertRule.fromMap(c.content())).toList();
        assertEquals(5, rules.size(), "the template's four typology Alert Rules + the Risk Score's");
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore(), objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
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
        var rows = QueryExecutor.run(new QueryExecutor.Request("s",
                DatasetRelation.relationSql(Map.of("physicalRef", ref), data, null), sql, 10, 0, List.of(), List.of())).rows();
        return ((Number) rows.get(0).values().iterator().next()).longValue();
    }

    /** The column names and DuckDB types of a sql.template sink, as {@code DESCRIBE} reports them. */
    private static String schema(Path data, String sink) throws Exception {
        com.gamma.util.DuckDbUtil.loadDriver();
        String glob = data.resolve(sink).toString().replace('\\', '/') + "/*.parquet";
        StringBuilder sb = new StringBuilder();
        try (var c = java.sql.DriverManager.getConnection("jdbc:duckdb:"); var st = c.createStatement();
             var rs = st.executeQuery("DESCRIBE SELECT * FROM read_parquet('" + glob + "')")) {
            while (rs.next()) sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(';');
        }
        return sb.toString();
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
