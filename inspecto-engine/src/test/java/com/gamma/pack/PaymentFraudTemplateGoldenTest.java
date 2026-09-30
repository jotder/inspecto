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
                "pf_card_testing", Set.of("dev_ct_01", "dev_ct_02"),
                "pf_bin_attack", Set.of("498765", "498700"),
                "pf_velocity_burst", Set.of("tok_shared", "tok_vb_01", "tok_vb_02", "tok_vb_03"),
                "pf_sim_swap_takeover", Set.of("acc_ss01", "acc_ss02"),
                "pf_high_risk_account", Set.of("acc_ct_guest", "acc_ss01", "acc_ss02", "acc_vb01")), detections,
                "exactly the planted offenders per typology — every look-alike stays silent");

        // One instrument shared by two accounts: the velocity Alert Rule sees all 6 attempts on the INSTRUMENT, while
        // each account's velocity factor counts only its own 3 — the partition and the grouping agree on one key.
        Map<String, Double> shared = new TreeMap<>();
        run.scored().stream().filter(x -> x.entityKey().startsWith("acc_sh")).forEach(x -> shared.put(x.entityKey(), x.score()));
        assertEquals(Map.of("acc_sh1", 15.0, "acc_sh2", 15.0), shared, "3 own attempts x 5 each, not the instrument's 6");

        // The exact scores of the high accounts, recomputed by hand from the default factor table.
        Map<String, Double> scores = new TreeMap<>();
        run.scored().stream().filter(s -> s.score() >= model.highThreshold()).forEach(s -> scores.put(s.entityKey(), s.score()));
        assertEquals(Map.of("acc_ct_guest", 60.0, "acc_ss01", 65.0, "acc_ss02", 65.0, "acc_vb01", 87.0), scores,
                "ss: 60 (SIM swap) + 5 (velocity 1); vb01: 35 (velocity 7) + 12 (3 declines) + 40 (2 disputes); "
                        + "ct_guest: 40 (velocity 10, capped) + 20 (8 declines, capped)");
    }

    /** Trip probes: each hides the published test PAN in one cell, spelled as an adversary would. */
    private static final List<String[]> TRIPS = List.of(
            new String[]{"INSTRUMENT_TOKEN", TEST_PAN},
            new String[]{"INSTRUMENT_TOKEN", "4111 1111 1111 1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111-1111-1111-1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111.1111.1111.1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111/1111/1111/1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111_1111_1111_1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111\t1111\t1111\t1111"},
            new String[]{"INSTRUMENT_TOKEN", "4111 1111 1111 1111"},              // NBSP
            new String[]{"INSTRUMENT_TOKEN", "4111–1111–1111–1111"},              // en-dash
            new String[]{"INSTRUMENT_TOKEN", fold(TEST_PAN, '０')},                          // full-width
            new String[]{"INSTRUMENT_TOKEN", fold(TEST_PAN, '٠')},                          // Arabic-Indic
            new String[]{"INSTRUMENT_TOKEN", "+" + TEST_PAN},
            new String[]{"MERCHANT_ID", "card " + TEST_PAN + " exp"},
            new String[]{"MERCHANT_ID", "card " + TEST_PAN + " 12/27"},
            new String[]{"AMOUNT", TEST_PAN});                                                    // the numeric leak path
    /** True negatives: digit strings that are NOT Luhn-valid 13–19 digit numbers — each must ingest. */
    private static final List<String[]> PASSES = List.of(
            new String[]{"INSTRUMENT_TOKEN", "tok_y"},
            new String[]{"MERCHANT_ID", "4111111111111112"},
            new String[]{"MERCHANT_ID", "4111 1111 1111 1112"},
            new String[]{"MERCHANT_ID", "411111111111"},
            new String[]{"MERCHANT_ID", "41111111111111111111"},
            new String[]{"AMOUNT", "1234567.89"});

    private static String fold(String ascii, char zero) {
        StringBuilder sb = new StringBuilder();
        for (char c : ascii.toCharArray()) sb.append((char) (zero + (c - '0')));
        return sb.toString();
    }

    /** One planted file: a clean row plus a row whose {@code column} carries {@code value}; returns the config. */
    private static PipelineConfig plant(Path dir, String column, String value) throws Exception {
        Path space = copyTemplate(dir);
        PipelineConfig pc = PipelineConfig.load(space.resolve("config/payment_attempts/payment_attempts_pipeline.toon").toString());
        Path inbox = Files.createDirectories(Path.of(pc.dirs().poll()));
        Map<String, String> row = new java.util.LinkedHashMap<>();
        for (String[] kv : new String[][]{{"ATTEMPT_ID", "pa_x2"}, {"ATTEMPT_TS", "2026-07-04 10:05:00"},
                {"ATTEMPT_DATE", "2026-07-04"}, {"ACCOUNT_ID", "acc_x"}, {"INSTRUMENT_TOKEN", "tok_x"}, {"BIN", "402400"},
                {"DEVICE_ID", "dev_x"}, {"MERCHANT_ID", "m_01"}, {"AMOUNT", "10.00"}, {"CURRENCY", "EUR"}, {"OUTCOME", "APPROVED"}})
            row.put(kv[0], kv[1]);
        row.put(column, value);
        Files.writeString(inbox.resolve("PAYMENT_ATTEMPTS_20260704.csv"), String.join(",", row.keySet()) + "\n"
                + "pa_x1,2026-07-04 10:00:00,2026-07-04,acc_x,tok_x,402400,dev_x,m_01,10.00,EUR,APPROVED\n"
                + String.join(",", row.values()) + "\n", java.nio.charset.StandardCharsets.UTF_8);
        CollectorProcessor.run(pc);
        assertTrue(Files.exists(Path.of(pc.dirs().statusFilePath())), value + ": the poll picked the file up");
        return pc;
    }

    @Test
    void digitStringsThatAreNotCardNumbersIngest(@TempDir Path tmp) throws Exception {
        int i = 0;
        for (String[] p : PASSES) {
            PipelineConfig pc = plant(tmp.resolve("p" + i++), p[0], p[1]);
            assertEquals(2L, scalar(Path.of(pc.dirs().database()).getParent().getParent(), "payment_attempts/database",
                    "SELECT count(*) FROM \"s\""), p[0] + "='" + p[1] + "' is not a card number: both rows land");
        }
    }

    @Test
    void aCardNumberInAnySpellingOrColumnPurgesTheFileAndLandsNothing(@TempDir Path tmp) throws Exception {
        int i = 0;
        for (String[] p : TRIPS) {
            String what = p[0] + "='" + p[1] + "'";
            PipelineConfig pc = plant(tmp.resolve("t" + i++), p[0], p[1]);
            assertEquals(0, count(Path.of(pc.dirs().database())), what + ": not one row of the file landed");
            assertFalse(Files.exists(Path.of(pc.dirs().poll(), "PAYMENT_ATTEMPTS_20260704.csv")),
                    what + ": the file left the inbox (it is never re-polled)");
            for (String kept : new String[]{pc.dirs().quarantine(), pc.dirs().errors(), pc.dirs().backup(), pc.dirs().temp()})
                assertEquals(0, count(Path.of(kept)), what + ": no copy of the file is kept in " + kept);
            assertFalse(Files.exists(Path.of(pc.dirs().markers(), "PAYMENT_ATTEMPTS_20260704.csv.processed")), what);
            String status = Files.readString(Path.of(pc.dirs().statusFilePath()));
            assertTrue(status.contains("PURGED_REFUSED"), what + ": the purge is audited: " + status);
            assertFalse(status.contains("1111"), what + ": the audit never quotes the value: " + status);
        }
    }

    /**
     * 🔴 KNOWN GAP, pinned as evidence for {@code INGEST-REJECT-SIDECAR-RAW-PAN-1}: a card number inside a MALFORMED
     * row (a field-count reject) never reaches the mapping, so the tripwire cannot see it — {@code all_or_nothing}
     * quarantines the raw file and writes a rejects sidecar holding the row verbatim. When the platform fixes it,
     * this test goes red: flip it to the purge assertions above.
     */
    @Test
    void knownGapACardNumberInAMalformedRowIsKeptInQuarantine(@TempDir Path tmp) throws Exception {
        PipelineConfig pc = plant(tmp, "OUTCOME", "APPROVED," + TEST_PAN);   // one extra field → a parse reject
        assertEquals(0, count(Path.of(pc.dirs().database())));
        StringBuilder kept = new StringBuilder();
        for (String dir : new String[]{pc.dirs().quarantine(), pc.dirs().errors()})
            if (Files.isDirectory(Path.of(dir)))
                try (Stream<Path> w = Files.walk(Path.of(dir))) {
                    for (Path f : w.filter(Files::isRegularFile).toList()) kept.append(Files.readString(f));
                }
        assertTrue(kept.toString().contains(TEST_PAN), "the gap is real: the raw value is kept at rest");
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
