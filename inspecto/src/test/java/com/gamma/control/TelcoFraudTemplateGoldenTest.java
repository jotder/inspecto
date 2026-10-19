package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
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
import com.gamma.job.PlatformServiceRegistry;
import com.gamma.objects.ObjectAccess;
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
import com.gamma.access.Roles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ASSURE-PACK-TELCO-FRAUD-1} golden test. Boots the shipped {@code spaces/_templates/telco-fraud} Space
 * Template through {@code POST /spaces} (every seed gate), ingests the synthetic corpus through the template's
 * own three Pipelines on the production Consignment path ({@link CollectorProcessor#run}), runs all fourteen
 * {@code sql.template} detection Jobs as authored, then evaluates the per-entity Alert Rules with the production
 * grouped Measure probe and {@link AlertService}. Each Alert Rule must raise EXACTLY its planted offenders
 * (including one just above its threshold) and no planted look-alike (including one exactly at it).
 */
class TelcoFraudTemplateGoldenTest {

    /** Expected Alerts per Alert Rule — the golden counts. */
    private static final Map<String, Integer> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("fraud_irsf", 5), Map.entry("fraud_wangiri", 4), Map.entry("fraud_simbox", 4),
            Map.entry("fraud_premium_rate", 5), Map.entry("fraud_roaming", 4), Map.entry("fraud_sim_swap", 4),
            Map.entry("fraud_identity", 3), Map.entry("fraud_dealer", 3), Map.entry("fraud_voucher", 4),
            Map.entry("fraud_reversal", 4), Map.entry("fraud_recharge", 5), Map.entry("fraud_data_bypass", 5),
            Map.entry("fraud_internal", 4), Map.entry("fraud_adjustment", 4)));
    private static final int TOTAL = 58;

    private static final Map<String, String> FEED_COLUMNS = Map.of(
            "cdr", "{'record_id':'VARCHAR','start_ts':'TIMESTAMP','direction':'VARCHAR','service':'VARCHAR',"
                    + "'a_number':'VARCHAR','b_number':'VARCHAR','duration_s':'INTEGER','charge':'DOUBLE',"
                    + "'roaming':'VARCHAR','cell_id':'VARCHAR'}",
            "subscriber_events", "{'event_id':'VARCHAR','event_ts':'TIMESTAMP','event_type':'VARCHAR',"
                    + "'msisdn':'VARCHAR','dealer_id':'VARCHAR','id_doc':'VARCHAR'}",
            "payments", "{'txn_id':'VARCHAR','txn_ts':'TIMESTAMP','txn_type':'VARCHAR','msisdn':'VARCHAR',"
                    + "'amount':'DOUBLE','voucher_serial':'VARCHAR','ref_txn_id':'VARCHAR','agent_id':'VARCHAR'}");

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
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH,
                // the template carries Alert Rules: creating it runs each carried kind's own gate (TEMPLATE-RECOVERY-IMPORT-GATE-1)
                Roles.CAN_AUTHOR_ALERT_RULES))));
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        MetricRegistry.global().reset();
    }

    /** The golden day: the Jobs ship a rolling {@code $yesterday} window, so the golden runs pin day 1 explicitly
     *  (and the incremental Jobs' {@code day}, the partition the run replaces). */
    private static final Map<String, String> DAY1 = window(java.time.LocalDate.of(2026, 7, 1));

    private static Map<String, String> window(java.time.LocalDate day) {
        return Map.of("window_start", day + " 00:00:00", "window_end", day.plusDays(1) + " 00:00:00", "day", day.toString());
    }

    private static Map<String, String> day1With(String k, String v) {
        Map<String, String> m = new java.util.HashMap<>(DAY1);
        m.put(k, v);
        return m;
    }

    /** ASSURE-PACK-TELCO-FRAUD-1 (e): every detection Job runs daily and rolls its window with the clock. */
    @Test
    void everyDetectionJobRollsItsWindowOnADailySchedule(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        ingest(space);
        List<JobConfig> jobs = jobs(space.resolve("config"));
        assertEquals(14, jobs.size());
        for (JobConfig j : jobs) {
            assertEquals("0 2 * * *", j.cron(), j.name() + " cron");
            assertEquals("$yesterday", j.params().get("window_start"), j.name());
            assertEquals("$today", j.params().get("window_end"), j.name());
        }
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            for (JobConfig j : jobs)
                assertEquals("SUCCESS", runJob(js, j.name(), Map.of()).status(), j.name() + " rolling window");
        }
    }

    /** TELCO-FRAUD-DEMO-REHEARSAL-1: detection Jobs only write sink Datasets; without an {@code alert.evaluate} Job
     *  on a clock the Alert Rules never fire overnight. One ships, and it runs AFTER the 02:00 detection Jobs. */
    @Test
    void theAlertRulesAreEvaluatedOnAClockAfterTheDetectionJobs(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        List<JobConfig> evaluators = new ArrayList<>();
        try (Stream<Path> files = Files.list(space.resolve("config").resolve("jobs"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("_job.toon")).sorted().toList()) {
                JobConfig j = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(f.toString()), "job")));
                if ("alert.evaluate".equals(j.type())) evaluators.add(j);
            }
        }
        assertEquals(1, evaluators.size());
        assertEquals("30 2 * * *", evaluators.get(0).cron());
    }

    /** TELCO-FRAUD-STORY-GAPS-1 (a): the story beats beyond Alerts ship - Expectations on all three feeds, Decision Rules
     *  (stamped by the seed gate), a Case Rule and a scheduled Dataset export - so those pages are not empty. */
    @Test
    void theTemplateShipsExpectationsDecisionRulesACaseRuleAndAScheduledExport(@TempDir Path root) throws Exception {
        Path config = createSpace(root).resolve("config");
        ComponentStore store = new ComponentStore(config.resolve("registry"));
        Set<String> feeds = new TreeSet<>();
        List<ComponentRegistry.Component> expectations = store.list("expectation");
        assertEquals(7, expectations.size());
        for (ComponentRegistry.Component e : expectations) feeds.add(String.valueOf(e.content().get("target")));
        assertEquals(Set.of("cdr", "payments", "subscriber_events"), feeds);
        List<ComponentRegistry.Component> rules = store.list("decision-rule");
        assertEquals(2, rules.size());
        for (ComponentRegistry.Component r : rules)
            assertEquals("admin-1", r.content().get("createdBy"), r.name() + " is stamped by the seed gate");
        Map<String, Object> caseRule = ToonHelper.requireSection(
                ToonHelper.load(config.resolve("ops").resolve("fraud_critical_burst_caserule.toon").toString()), "case_rule");
        assertEquals("fraud_critical_burst", caseRule.get("name"));
        assertEquals("msisdn", caseRule.get("groupBy"), "one Case per offender, not one per burst");
        JobConfig export = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(
                ToonHelper.load(config.resolve("jobs").resolve("fraud_offender_export_job.toon").toString()), "job")));
        assertEquals("report", export.type());
        assertEquals("0 3 * * *", export.cron());
        assertEquals("../data/exports/fraud_offender_export", export.opt("out_dir", null),
                "the export lands in the Space data tree, not inside the config tree");
        JobConfig quality = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(
                ToonHelper.load(config.resolve("jobs").resolve("fraud_expectation_evaluate_job.toon").toString()), "job")));
        assertEquals("expectation.evaluate", quality.type());
        assertEquals("45 1 * * *", quality.cron(), "after the feeds land, before the 02:00 detection Jobs");
    }

    /** The nightly {@code expectation.evaluate} Job on the sample feeds: a seeded out-of-range CDR duration makes
     *  exactly that Expectation FAIL and open an ingest-quality Incident; the other six pass and raise nothing. */
    @Test
    void theNightlyExpectationJobRaisesAnIngestQualityIncidentForASeededBadRow(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path cdrCsv;
        try (Stream<Path> samples = Files.list(space.resolve("data").resolve("samples").resolve("cdr"))) {
            cdrCsv = samples.sorted().findFirst().orElseThrow();
        }
        Files.writeString(cdrCsv, "R9999999,2026-07-01 10:00:00,MO,VOICE,99970000001,071031508,999999,0.10,N,C101\n",
                java.nio.file.StandardOpenOption.APPEND);
        ingest(space);

        List<Map<String, String>> opened = new ArrayList<>();
        ObjectAccess objects = (ObjectAccess) java.lang.reflect.Proxy.newProxyInstance(
                ObjectAccess.class.getClassLoader(), new Class<?>[]{ObjectAccess.class}, (proxy, method, args) -> {
                    if (method.getName().equals("open")) {
                        @SuppressWarnings("unchecked") Map<String, String> attrs = (Map<String, String>) args[5];
                        opened.add(attrs);
                        return "INC-1";
                    }
                    Class<?> r = method.getReturnType();
                    return r == boolean.class ? Boolean.FALSE : r == long.class ? 0L : r == int.class ? 0
                            : r == Optional.class ? Optional.empty() : r == List.class ? List.of() : r == Map.class ? Map.of() : null;
                });
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        platform.register("objects", ObjectAccess.class, objects);
        // the built-in Job Types validate their own grants at registration; the Job under test only uses `objects`
        registerInert(platform, "notifications", com.gamma.notify.NotificationAccess.class);
        registerInert(platform, "mail", com.gamma.notify.MailAccess.class);
        registerInert(platform, "incidents", com.gamma.objects.IncidentAccess.class);
        registerInert(platform, "schema", com.gamma.pipeline.SchemaAccess.class);
        registerInert(platform, "consignment-status", com.gamma.consignment.ConsignmentStatusAccess.class);
        registerInert(platform, "datasets", com.gamma.query.DatasetAccess.class);
        registerInert(platform, "alerts", com.gamma.alert.AlertAccess.class);
        registerInert(platform, "link-index", com.gamma.linkindex.LinkIndexAccess.class);
        JobConfig job = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(space.resolve("config")
                .resolve("jobs").resolve("fraud_expectation_evaluate_job.toon").toString()), "job")));

        String priorWriteRoot = System.getProperty("assist.write.root"), priorData = System.getProperty("data.dir");
        System.setProperty("assist.write.root", space.resolve("config").toString());
        System.setProperty("data.dir", space.resolve("data").toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, space.resolve("data").toString(), null, platform)) {
            js.start();
            JobRun run = runJob(js, "fraud_expectation_evaluate", Map.of());
            assertEquals("SUCCESS", run.status(), run.toString());
        } finally {
            if (priorWriteRoot == null) System.clearProperty("assist.write.root"); else System.setProperty("assist.write.root", priorWriteRoot);
            if (priorData == null) System.clearProperty("data.dir"); else System.setProperty("data.dir", priorData);
        }

        assertEquals(1, opened.size(), "exactly the seeded breach raises an Incident: " + opened);
        assertEquals("tf_cdr_duration_in_range", opened.get(0).get("expectation"));
        assertEquals("1", opened.get(0).get("violations"));
        ComponentStore store = new ComponentStore(space.resolve("config").resolve("registry"));
        for (ComponentRegistry.Component e : store.list("expectation")) {
            Map<?, ?> last = (Map<?, ?>) e.content().get("lastResult");
            assertEquals("tf_cdr_duration_in_range".equals(e.name()) ? "FAILED" : "PASSED", last.get("status"), e.name());
        }
    }

    /** TELCO-FRAUD-DEMO-REHEARSAL-1: a viewer without {@code canRevealLinkEntities} sees {@code masked:} aliases only where a
     *  column is CLASSIFIED {@code MSISDN}; the feeds said {@code CONFIDENTIAL}, which no masked Entity Type claims, so a
     *  viewer read raw subscriber numbers in the feed Datasets. Pin the classification on the three feed schemas. (The sink
     *  Datasets classify their subscriber key too; the dashboard test proves a viewer still gets every bar, masked -
     *  TELCO-FRAUD-MASKED-DASHBOARD-1.) */
    @Test
    void everyFeedSubscriberNumberColumnIsClassifiedMsisdn(@TempDir Path root) throws Exception {
        Path config = createSpace(root).resolve("config");
        for (String[] f : new String[][]{{"cdr/cdr_schema.toon", "a_number", "b_number"},
                {"subscriber_events/subscriber_events_schema.toon", "msisdn"}, {"payments/payments_schema.toon", "msisdn"}}) {
            String text = Files.readString(config.resolve(f[0]));
            for (int i = 1; i < f.length; i++) {
                String col = f[i];
                assertTrue(text.lines().anyMatch(l -> l.strip().startsWith(col + ",") && l.endsWith("\"MSISDN\"")), f[0] + " " + col);
            }
        }
    }

    /** Runbook (operator 2026-10-10): every typology's Alert Rule links its own Runbook, and each one is valid. */
    @Test
    void everyAlertRuleLinksAValidRunbookOfItsOwnTypology(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(space.resolve("config").resolve("registry"));
        List<com.gamma.pipeline.ComponentRegistry.Component> rules = store.list("alert-rule");
        assertEquals(14, rules.size());
        for (com.gamma.pipeline.ComponentRegistry.Component r : rules) {
            assertEquals(r.name(), r.content().get("runbook"), r.name() + " links its own typology's Runbook");
            com.gamma.alert.Runbook rb = com.gamma.alert.Runbook.fromMap(r.name(),
                    store.get("runbook", r.name()).orElseThrow(() -> new AssertionError(r.name() + " runbook missing")).content());
            assertTrue(rb.steps().size() >= 3, r.name() + " has Detects/Check/Act steps");
            assertEquals(r.name(), rb.steps().get(0).linkId(), "the first step opens the typology's evidence Dataset");
        }
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
     * seeds the template — that save gate fails closed on a Dataset with no data yet — and so each Job that still
     * reads its own sink to keep earlier windows has a relation to read on its first run. An incremental Job's seed
     * sits in a {@code window_date=1970-01-01/} placeholder folder (its sink's day layout). Regenerated from each
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
                boolean incremental = j.params().containsKey("incremental.column");
                // An incremental Job's sink is laid out window_date=YYYY-MM-DD/ (the date lives in the folder name
                // only), so its seed is a zero-row file in one placeholder day folder the Job never replaces.
                Path seed = incremental
                        ? tpl.resolve("data").resolve(sink).resolve("window_date=1970-01-01").resolve("seed.parquet")
                        : tpl.resolve("data").resolve(sink).resolve("seed.parquet");
                String seedRel = "read_parquet('" + seed.toString().replace('\\', '/') + "')";
                String cur;
                if (incremental) {
                    assertEquals("window_date", j.params().get("incremental.column"), j.name());
                    assertEquals("$yesterday", j.params().get("day"), j.name() + " replaces the window's own day");
                    assertFalse(sql.contains(UNION) || List.of(j.params().get("sources").split(",")).contains(sink),
                            j.name() + ": an incremental Job does not read its own sink");
                    assertFalse(Files.exists(tpl.resolve("data").resolve(sink).resolve("seed.parquet")),
                            j.name() + ": a flat seed is refused by the incremental layout gate");
                    cur = "SELECT * EXCLUDE (window_date) FROM (" + sql + ")";
                    sql = "SELECT * EXCLUDE (window_date), window_date FROM (" + sql + ")";   // read back: folder column last
                } else {
                    assertTrue(sql.startsWith("WITH cur AS (") && sql.contains(UNION),
                            j.name() + " keeps earlier windows by reading its own sink");
                    cur = sql.substring("WITH cur AS (".length(), sql.lastIndexOf(UNION));
                }
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
        assertEquals(14, jobs.size(), "one detection Job per typology");
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
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), DAY1).status(), j.name());

            assertRaisesExactlyThePlanted(rules, probe, corpus);
            // The REAL Dataset read (the probe behind Alert Rules / KPIs / the save gates, over DatasetRelation) sees
            // the day-folder column: DatasetRelation leaves hive_partitioning unset and DuckDB detects window_date=.
            for (JobConfig j : jobs) {
                String sink = j.params().get("sink_dataset");
                List<String> cols = probe.columns(sink);
                assertEquals("window_date", cols.get(cols.size() - 1), sink + ": the folder column is read back, last");
                assertEquals("DATE", probe.columnType(sink, "window_date"), sink + ": window_date reads as a DATE");
            }
            Map<String, Integer> fired = new TreeMap<>();
            List<Alert> raised = svc.evaluateRules();
            assertEquals(EXPECTED.size(), raised.size(), "ONE Alert per typology rule, whatever its offender count");
            for (Alert a : raised) fired.merge(a.rule(), (int) a.value(), Integer::sum);
            assertEquals(EXPECTED, fired, "golden breached-key count per typology (an Alert's value)");
            assertEquals(TOTAL, fired.values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(List.of(), svc.evaluateRules(), "a re-evaluation raises nothing new");

            // Re-running the SAME window replaces that window's rows: no duplicates, no Alert change.
            Map<String, Long> rows = sinkRows(data, jobs);
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), DAY1).status(), j.name() + " re-run");
            assertEquals(rows, sinkRows(data, jobs), "a same-window re-run duplicates no row");
            assertRaisesExactlyThePlanted(rules, probe, corpus);
            assertEquals(List.of(), svc.evaluateRules(), "a same-window re-run changes no Alert");

            // The NEXT window's run keeps the earlier window's rows: its offenders do not vanish from the Dataset
            // (which would auto-resolve their Alerts with nobody acting), and nothing new is raised.
            Map<String, String> day2 = window(java.time.LocalDate.of(2026, 7, 2));
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), day2).status(), j.name() + " day 2");
            assertRaisesExactlyThePlanted(rules, probe, corpus);
            assertEquals(List.of(), svc.evaluateRules(), "the next window neither raises nor heals day 1's Alerts");
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(DISTINCT window_date) FROM read_parquet('"
                         + data.resolve("fraud_reversal").toString().replace('\\', '/') + "/**/*.parquet', hive_partitioning = true)")) {
                rs.next();
                assertEquals(2, rs.getLong(1), "the sink holds both windows (day 2 has one reversal-less payment)");
            }

            // Retention (incremental retain: 31) = the pre-migration horizon: the old SQL kept the current window plus
            // every window_date >= window_start - retention_days (30), so day 1 (07-01) survived the 07-31 run and
            // aged out on the 08-01 run. Same here: the 07-31 run keeps day 1 (no heal), the 08-01 run prunes it.
            for (JobConfig j : jobs) assertEquals("31", j.params().get("incremental.retain"), j.name());
            Map<String, String> day31 = window(java.time.LocalDate.of(2026, 7, 31));
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), day31).status(), j.name() + " day 31");
            assertRaisesExactlyThePlanted(rules, probe, corpus);
            assertEquals(List.of(), svc.evaluateRules(), "day 31 still holds day 1: nothing raised or healed");
            assertEquals(EXPECTED.size(), svc.openAlerts().size(), "one Alert per rule stays open on day 31");
            assertEquals(TOTAL, svc.openAlerts().stream().mapToInt(a -> (int) a.fired().value()).sum(),
                    "...still counting every day-1 offender");
            Map<String, String> day32 = window(java.time.LocalDate.of(2026, 8, 1));
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), day32).status(), j.name() + " day 32");
            for (JobConfig j : jobs) {
                Path sink = data.resolve(j.params().get("sink_dataset"));
                assertFalse(Files.exists(sink.resolve("window_date=2026-07-01")), j.name() + ": day 1 aged out");
                assertTrue(Files.exists(sink.resolve("window_date=1970-01-01").resolve("seed.parquet")), j.name() + ": seed kept");
            }
            for (AlertRule r : rules)
                assertEquals(List.of(), probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(), r.threshold(),
                        r.stormCap()).orElseThrow().keys(), r.name() + ": every day-1 key healed once its evidence aged out");
            assertEquals(EXPECTED.size(), svc.openAlerts().size(), "the rules' Alerts are open before the evaluation");
            assertEquals(List.of(), svc.evaluateRules(), "ageing out raises nothing");
            assertEquals(List.of(), svc.openAlerts(), "the aged-out keys resolve each rule's Alert");
        }
    }

    /**
     * Every tile on {@code telco_fraud_overview} renders after the golden run, through the routes the SPA uses:
     * a KPI tile through {@code GET /kpis/{id}/value}, a bar Widget through {@code POST /bi/query} grouped by its
     * x field — and each typology's bar shows every planted offender with a positive value.
     */
    @Test
    void everyDashboardTileRendersThePlantedOffendersAfterTheGoldenRun(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path data = space.resolve("data"), config = space.resolve("config");
        ingest(space);
        List<JobConfig> jobs = jobs(config);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (JobConfig j : jobs) assertEquals("SUCCESS", runJob(js, j.name(), DAY1).status(), j.name());
        }
        TelcoFraudCorpus corpus = new TelcoFraudCorpus().generate();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.gamma.mask.EvidenceMasker masker = com.gamma.mask.EvidenceMasker.forSpace(config);
        // "admin" may reveal entity keys; "viewer" holds no capability at all.
        Authenticators.forTest(ex -> Optional.of("Bearer viewer".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? new Subject("viewer", Set.of())
                : new Subject("admin-1", Set.of(Roles.CAN_ADMINISTER, Roles.CAN_REVEAL_LINK_ENTITIES))));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            String base = "http://localhost:" + api.port() + "/api/v1/spaces/fraud";
            com.fasterxml.jackson.databind.JsonNode dash = V1Body.of(
                    call(base + "/components/dashboard/telco_fraud_overview", null)).get("content");
            Set<String> typologiesShown = new TreeSet<>();
            int tiles = 0;
            for (com.fasterxml.jackson.databind.JsonNode tile : dash.get("tiles")) {
                tiles++;
                String id = tile.get("widgetId").asText();
                int span = tile.get("span").asInt();
                assertTrue(span == 1 || span == 2, id + ": a tile spans 1 or 2");
                com.fasterxml.jackson.databind.JsonNode w = V1Body.of(call(base + "/components/widget/" + id, null)).get("content");
                if ("kpi".equals(w.get("vizType").asText())) {
                    String kpi = w.get("options").get("kpi").get("kpiId").asText();
                    com.fasterxml.jackson.databind.JsonNode v = V1Body.of(call(base + "/kpis/" + kpi + "/value?asOf=2026-07-01", null));
                    assertTrue(v.get("value").asDouble() > 0, id + ": the KPI tile has a value: " + v);
                    continue;
                }
                if ("table".equals(w.get("vizType").asText())) {
                    // ANOMALY-DETECTION-RESIDUALS-1 (a): the top-10 anomaly tile reads the anomaly.score Job's _latest store,
                    // which this golden does not run (no anomaly module here); its masking and top-N are pinned over real
                    // HTTP by ControlApiBiQueryMaskingTest.anAnomalyTopTenTileIsMaskedOrderedAndLimitedForAViewer. Here: it
                    // is a top-10 by score over a Dataset that classifies the entity key, so a viewer cannot get the number.
                    assertEquals("tf_unusual_subscribers_top10", id);
                    assertEquals(10, w.get("options").get("limit").asInt(), id);
                    assertEquals("max_score", w.get("options").get("tableSort").get("field").asText(), id);
                    com.fasterxml.jackson.databind.JsonNode ds = V1Body.of(call(base + "/components/dataset/"
                            + w.get("datasetId").asText(), null)).get("content");
                    assertTrue(ds.get("columns").toString().contains("\"entity_key\",\"type\":\"string\",\"classification\":\"MSISDN\""),
                            id + ": the entity key is classified: " + ds);
                    continue;
                }
                assertEquals("bar", w.get("vizType").asText(), id);
                String dataset = w.get("datasetId").asText();
                String x = w.get("controls").get("x").get(0).get("field").asText();
                com.fasterxml.jackson.databind.JsonNode y = w.get("controls").get("y").get(0);
                String agg = y.get("agg").asText(), field = y.get("field").asText();
                // As the SPA's bar plugin sends it: the category order on the group key is cosmetic.
                String body = json.writeValueAsString(Map.of("dataset", dataset, "groupBy", List.of(x),
                        "measures", List.of(Map.of("agg", agg, "field", field)),
                        "orderBy", List.of(Map.of("field", x, "dir", "asc", "cosmetic", true))));
                Map<String, Double> bars = bars(call(base + "/bi/query", body), x, agg + "_" + field);
                for (String offender : corpus.offenders.get(dataset))
                    assertTrue(bars.getOrDefault(offender, 0.0) > 0, id + ": planted offender " + offender + " has a bar: " + bars);
                // TELCO-FRAUD-MASKED-DASHBOARD-1: a viewer without canRevealLinkEntities gets the SAME bars, a subscriber
                // key under its masked alias (never the number), every other key raw.
                String viewerBody = call(base + "/bi/query", body, "viewer");
                Map<String, Double> viewerBars = bars(viewerBody, x, agg + "_" + field);
                boolean subscriberKey = SUBSCRIBER_KEYS.contains(x);
                for (String offender : corpus.offenders.get(dataset)) {
                    String shown = subscriberKey ? masker.tokenFor(offender) : offender;
                    assertEquals(bars.get(offender), viewerBars.get(shown), id + ": the viewer's bar for " + offender + ": " + viewerBars);
                    if (subscriberKey) assertFalse(viewerBody.contains(offender), id + ": raw " + offender + " reached a viewer");
                }
                assertEquals(bars.size(), viewerBars.size(), id + ": one viewer bar per key");
                typologiesShown.add(dataset);
            }
            assertEquals(19, tiles, "four KPI tiles, fourteen typology Widgets and the anomaly top-10");
            assertEquals(EXPECTED.keySet(), typologiesShown, "every typology has a Widget on the dashboard");
        } finally {
            api.close();
            spaces.close();
        }
    }

    /** The sink group keys classified MSISDN, masked for a non-revealer. ({@code id_doc} is classified PII, which no
     *  telecom Entity Type claims, so the default {@code typed} masking mode leaves it raw.) */
    private static final Set<String> SUBSCRIBER_KEYS = Set.of("msisdn", "caller");

    private static Map<String, Double> bars(String body, String x, String measure) throws Exception {
        Map<String, Double> bars = new TreeMap<>();
        for (com.fasterxml.jackson.databind.JsonNode r : V1Body.of(body).get("rows")) bars.put(r.get(x).asText(), r.get(measure).asDouble());
        return bars;
    }

    private static String call(String url, String jsonBody) throws Exception {
        return call(url, jsonBody, "admin");
    }

    private static String call(String url, String jsonBody, String user) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + user);
        if (jsonBody != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), url + " -> " + r.body());
        return r.body();
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
            assertEquals("SUCCESS", runJob(js, "fraud_irsf", DAY1).status());
            JobRun bad = runJob(js, "fraud_irsf", day1With("irsf_prefixes", "882,+883"));
            assertNotEquals("SUCCESS", bad.status(), "a '+' in a prefix list entry must fail: " + bad.message());
            JobRun blank = runJob(js, "fraud_premium_rate", day1With("premium_prefixes", "999900,,999909"));
            assertNotEquals("SUCCESS", blank.status(), "an empty prefix list entry must fail: " + blank.message());
        }
    }

    private static void assertRaisesExactlyThePlanted(List<AlertRule> rules, DatasetMeasureProbe probe,
                                                      TelcoFraudCorpus corpus) {
        Set<String> everyRaisedKey = new HashSet<>();
        for (AlertRule r : rules) {
            assertEquals(1, r.by().size(), r.name() + " is keyed on the offender alone");
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
        // the known-risk pin: a made-up CORP- document is exempted. Changing that must be deliberate.
        for (Map.Entry<String, Set<String>> risk : corpus.knownRiskExempted.entrySet())
            for (String k : risk.getValue())
                assertFalse(everyRaisedKey.contains(k), "known-risk pin moved: " + k + " (" + risk.getKey() + ") now raises");
    }

    /**
     * Sixteen days of offenders at the corpus rate (each day a fresh copy of the corpus with every identifier
     * suffixed {@code -<day>} and every timestamp shifted), all retained. Each rule holds ONE
     * Alert (ALERT-PER-RULE-1) whose breached-key count grows to every distinct retained offender, each offender
     * counted once under its own key digest, and no rule collapses into a storm: the retained offender count stays
     * under {@code stormCap}.
     */
    @Test
    void sixteenRetainedDaysOfOffendersAreCountedInEachRulesOneAlertAndNoStorm(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path data = space.resolve("data"), config = space.resolve("config");
        ingest(space);
        int days = 16;
        simulateDays(data, days);
        List<JobConfig> jobs = jobs(config);
        List<AlertRule> rules = rules(config);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> config, () -> data);
        com.gamma.alert.InMemoryAlertStore alerts = new com.gamma.alert.InMemoryAlertStore();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore(),
                com.gamma.alert.AlertRecords.of(alerts, Optional.empty()),
                com.gamma.alert.AlertRecords.incidentsOf(Optional.empty()));
        svc.groupedMeasureProbe(r -> probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                r.threshold(), r.stormCap()));
        TelcoFraudCorpus corpus = new TelcoFraudCorpus().generate();
        StringBuilder pbx = new StringBuilder("99970001119");
        for (int d = 2; d <= days; d++) pbx.append(",99970001119-").append(d);   // the register lists each day's PBX line

        Map<String, Integer> raisedAlerts = new TreeMap<>();
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (int d = 1; d <= days; d++) {
                java.time.LocalDate day = java.time.LocalDate.of(2026, 7, 1).plusDays(d - 1);
                for (JobConfig j : jobs) {
                    Map<String, String> a = new java.util.HashMap<>(window(day));
                    if (j.name().equals("fraud_simbox")) a.put("exempt_msisdns", pbx.toString());
                    assertEquals("SUCCESS", runJob(js, j.name(), a).status(), j.name() + " day " + d);
                }
                for (Alert a : svc.evaluateRules()) {
                    assertFalse(a.message().contains("storm"), "day " + d + ": " + a.message());
                    raisedAlerts.merge(a.rule(), 1, Integer::sum);
                }
            }
        }
        for (Map.Entry<String, Integer> e : EXPECTED.entrySet()) {
            Set<String> expected = new TreeSet<>();
            for (int d = 1; d <= days; d++)
                for (String k : corpus.offenders.get(e.getKey())) expected.add(d == 1 ? k : k + "-" + d);
            assertEquals(1, raisedAlerts.getOrDefault(e.getKey(), 0), e.getKey() + ": ONE Alert over all sixteen days");
            List<com.gamma.alert.AlertStore.Row> rows = alerts.allActive().stream()
                    .filter(x -> e.getKey().equals(x.attributes().get("rule"))).toList();
            assertEquals(1, rows.size(), e.getKey() + ": one active Alert record");
            assertEquals("false", rows.get(0).attributes().get("storm"), e.getKey());
            assertEquals(String.valueOf(e.getValue() * days), rows.get(0).attributes().get("breachedKeys"),
                    e.getKey() + ": the Alert counts every distinct offender of the sixteen days");
            AlertRule r = rules.stream().filter(x -> x.name().equals(e.getKey())).findFirst().orElseThrow();
            Set<String> keys = new TreeSet<>();
            Set<String> digests = new TreeSet<>();
            for (DatasetMeasureProbe.Breach br : probe.breaches(r.dataset(), r.measure(), r.by(), r.comparator(),
                    r.threshold(), r.stormCap()).orElseThrow().keys()) {
                keys.add(String.valueOf(br.key().get(r.by().get(0))));
                digests.add(AlertService.keyDigest(AlertService.keyId(br.key())));
            }
            assertEquals(expected, keys, e.getKey() + ": every retained day's offenders, each once");
            assertEquals(digests, new TreeSet<>(List.of(rows.get(0).attributes().get(AlertService.DIGESTS).split(","))),
                    e.getKey() + ": the Alert holds exactly those offenders' digests");
        }
    }

    /**
     * The at-threshold look-alikes that RECUR: their identifiers are never suffixed, so each of them sits exactly at
     * its rule's threshold on all 16 retained days. Silent under {@code max}; a {@code sum} over the retained windows
     * would raise every one (the voucher rule has none: a serial redeemed again on another day IS the fraud).
     */
    private static final String RECURRING = "'99970001011', '+28900000005', '0028900000005', '99970001114', "
            + "'99970001211', '99970001311', '99970001412', 'DOC-F0002', 'D90', '99970001811', '99970001911', "
            + "'99970002611', 'zero.operator.apn', 'S111', 'HELPDESK', 'A211', 'BILLING-DESK'";

    /** Days 2..n: a copy of the ingested day-1 feeds with every identifier (bar {@link #RECURRING}) suffixed
     *  {@code -d}, shifted d-1 days. */
    private static void simulateDays(Path data, int days) throws Exception {
        Map<String, String[]> ids = Map.of(
                "cdr", new String[] {"start_ts", "record_id", "a_number", "b_number"},
                "subscriber_events", new String[] {"event_ts", "event_id", "msisdn", "dealer_id", "id_doc"},
                "payments", new String[] {"txn_ts", "txn_id", "msisdn", "voucher_serial", "ref_txn_id", "agent_id"});
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (Map.Entry<String, String[]> feed : ids.entrySet()) {
                Path db = data.resolve(feed.getKey()).resolve("database");
                st.execute("CREATE TABLE day1_" + feed.getKey() + " AS SELECT * FROM read_parquet('"
                        + db.toString().replace('\\', '/') + "/**/*.parquet')");
                Path sim = Files.createDirectories(db.resolve("simulated"));
                for (int d = 2; d <= days; d++) {
                    String[] cols = feed.getValue();
                    StringBuilder repl = new StringBuilder(cols[0] + " + INTERVAL " + (d - 1) + " DAY AS " + cols[0]);
                    for (int i = 1; i < cols.length; i++)
                        repl.append(", CASE WHEN ").append(cols[i]).append(" IS NULL OR ").append(cols[i])
                                .append(" = '' OR ").append(cols[i]).append(" IN (").append(RECURRING).append(") THEN ").append(cols[i]).append(" ELSE ").append(cols[i])
                                .append(" || '-").append(d).append("' END AS ").append(cols[i]);
                    st.execute("COPY (SELECT * REPLACE (" + repl + ") FROM day1_" + feed.getKey() + ") TO '"
                            + sim.resolve("day-" + d + ".parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                }
            }
        }
    }

    private static Map<String, Long> sinkRows(Path data, List<JobConfig> jobs) throws Exception {
        Map<String, Long> out = new TreeMap<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (JobConfig j : jobs) {
                String sink = j.params().get("sink_dataset");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('"
                        + data.resolve(sink).toString().replace('\\', '/') + "/**/*.parquet')")) {
                    rs.next();
                    out.put(sink, rs.getLong(1));
                }
            }
        }
        return out;
    }

    /** {@code POST /spaces} with the template (every seed gate); the Space is closed again before the test drives it. */
    private static <T> void registerInert(PlatformServiceRegistry platform, String id, Class<T> type) {
        platform.register(id, type, type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type}, (p, m, a) -> { throw new UnsupportedOperationException(id); })));
    }

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

    /** The detection Jobs ({@code sql.template}); the pack's {@code anomaly.score} Job needs the anomaly module. */
    private static List<JobConfig> jobs(Path config) throws Exception {
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
