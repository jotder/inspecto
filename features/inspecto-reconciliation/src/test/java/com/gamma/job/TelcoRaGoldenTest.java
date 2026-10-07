package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.recon.ReconRunJob;
import com.gamma.recon.ReconBreaks;
import com.gamma.recon.ReconStateStore;
import com.gamma.signal.SignalEmitter;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.RunLog;
import com.gamma.util.Scheduler;
import com.gamma.pipeline.SpaceConfigRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden test of the {@code telco-ra} Space Template ({@code ASSURE-PACK-TELCO-RA-1}): the template's OWN
 * shipped configuration (Reconciliations, {@code recon.run} and {@code sql.template} Jobs, their tolerances) runs
 * over the fixed-seed synthetic corpus, and every control flags EXACTLY its planted leakages — the exact count, the
 * exact keys, so every benign look-alike stays silent (zero false positives).
 *
 * <p>The corpus is loaded straight into the Pipelines' stores ({@code data/<feed>/database}) rather than ingested
 * through the eight Pipelines — the Pipelines' own shape is covered by {@code RepoSpacesConfigValidationTest}.
 * Regenerate the committed samples with {@code -Dtelcora.regenerate=true}.
 */
class TelcoRaGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "spaces", "_templates", "telco-ra").toAbsolutePath().normalize();
    private static final TelcoRaCorpus CORPUS = TelcoRaCorpus.generate();
    /** Rated calls under the duplicate PLAN_B DATA tariff row: a property of the fixed seed, pinned. */
    private static final long AMBIGUOUS = 12;

    @BeforeAll
    static void regenerateOnAsk() throws Exception {
        if (Boolean.getBoolean("telcora.regenerate")) CORPUS.writeTo(TEMPLATE.resolve("data").resolve("samples"));
    }

    /** {@code SpaceConfigRoot.current()} prefers a registered Space over {@code -Dassist.write.root}; in the shared
     *  {@code inspecto} test JVM an earlier class may have left one registered, which silently re-points this test. */
    @BeforeEach
    void startWithNoRegisteredSpace() {
        SpaceConfigRoot.clear();
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
        SpaceConfigRoot.clear();
    }

    @Test
    void theCommittedSamplesAreExactlyTheGeneratorsOutput() throws Exception {
        Path samples = TEMPLATE.resolve("data").resolve("samples");
        Set<String> committed;
        try (Stream<Path> s = Files.walk(samples)) {
            committed = s.filter(Files::isRegularFile).map(p -> samples.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertEquals(new TreeSet<>(CORPUS.files.keySet()), committed, "the sample file set");
        for (Map.Entry<String, String> f : CORPUS.files.entrySet())
            assertEquals(f.getValue(), Files.readString(samples.resolve(f.getKey()), StandardCharsets.UTF_8),
                    f.getKey() + " drifted from the fixed-seed generator — regenerate with -Dtelcora.regenerate=true");
        assertEquals(CORPUS.files, TelcoRaCorpus.generate().files, "the generator is deterministic");
    }

    @Test
    void everyControlFlagsExactlyItsPlantedLeakages(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("config");
        Path dataDir = dir.resolve("data");
        loadCorpus(dataDir);
        copyTree(TEMPLATE.resolve("config").resolve("registry"), writeRoot.resolve("registry"));
        System.setProperty("assist.write.root", writeRoot.toString());

        // ── Reconciliations, through the template's own recon.run Jobs ──
        for (String recon : List.of("ra_xdr_completeness", "ra_rated_vs_billed")) {
            JobConfig cfg = JobConfig.load(templateJob(recon).toString());
            assertEquals("recon.run", cfg.type());
            JobResult r = new ReconRunJob(cfg, dataDir.toString(), () -> null).run(new Ctx(cfg.params()));
            assertEquals("SUCCESS", r.status(), r.message());
            assertTrue(r.message().contains(": " + CORPUS.planted.get(recon).size() + " break(s)"),
                    recon + ": the run reports every pair's Breaks: " + r.message());
            Set<String> flagged = new TreeSet<>();
            for (ReconBreaks.Break b : new ReconStateStore(writeRoot).read(recon).breaks())
                flagged.add(b.pair() + "|" + b.type() + "|" + b.key());
            assertEquals(new TreeSet<>(CORPUS.planted.get(recon)), flagged, recon + ": exactly the planted Breaks");
            for (String benign : CORPUS.benign.get(recon))
                assertTrue(flagged.stream().noneMatch(k -> k.endsWith("|" + benign)), recon + " flagged benign " + benign);
        }

        // ── sql.template controls, through the template's own Jobs and tolerances ──
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : List.of("ra_xdr_lost", "ra_rerating", "ra_rollforward", "ra_settlement", "ra_leakage", "ra_data_quality"))
            jobs.add(JobConfig.load(templateJob(j).toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            for (JobConfig j : jobs) {
                assertTrue(js.triggerRun(j.name(), null).isPresent(), j.name());
                JobRun run = await(() -> js.lastRunOf(j.name()).orElse(null));
                assertEquals("SUCCESS", run.status(), j.name() + ": " + run.message());
            }
        }
        long plantedTotal = 0;
        for (String control : List.of("ra_xdr_lost", "ra_rerating", "ra_rollforward", "ra_settlement")) {
            String store = "ra_xdr_lost".equals(control) ? control : control + "_findings";
            List<String> rows = rows(dataDir, store, "ITEM_KEY || '|' || REASON");
            Set<String> flagged = new TreeSet<>(rows);
            assertEquals(flagged.size(), rows.size(), control + ": one row per finding, never a fan-out");
            assertEquals(new TreeSet<>(CORPUS.planted.get(control)), flagged, control + ": exactly the planted findings");
            for (String benign : CORPUS.benign.get(control))
                assertTrue(flagged.stream().noneMatch(k -> k.startsWith(benign + "|")),
                        control + " flagged benign look-alike " + benign);
            if (!"ra_xdr_lost".equals(control)) plantedTotal += CORPUS.planted.get(control).size();
        }
        // leakage and data quality are separate Datasets, with separate Alert Rules
        Set<String> dq = Set.of("ambiguous_tariff", "no_tariff", "null_value", "missing_statement", "split_statement");
        long plantedDq = 0;
        for (String control : List.of("ra_rerating", "ra_rollforward", "ra_settlement"))
            plantedDq += CORPUS.planted.get(control).stream()
                    .filter(k -> dq.contains(k.substring(k.lastIndexOf('|') + 1))).count();
        assertEquals(plantedTotal - plantedDq, rows(dataDir, "ra_leakage", "CONTROL").size(), "leakage rows only");
        assertEquals(List.of(), rows(dataDir, "ra_leakage", "ITEM_KEY WHERE LEAKAGE_AMOUNT IS NULL OR FINDING <> 'leakage'"));
        assertEquals(plantedDq, rows(dataDir, "ra_data_quality", "CONTROL").size(), "data-quality rows only");
        assertEquals(List.of(), rows(dataDir, "ra_data_quality", "ITEM_KEY WHERE FINDING <> 'data_quality'"));
        // every settlement finding carries its exact loss: billed total (all lines) − expected, or the whole total
        for (Map.Entry<String, String> loss : CORPUS.settlementLoss.entrySet())
            assertEquals(List.of(new java.math.BigDecimal(loss.getValue()).setScale(4).toPlainString()),
                    rows(dataDir, "ra_settlement_findings", "CAST(LEAKAGE_AMOUNT AS VARCHAR) WHERE ITEM_KEY = '"
                            + loss.getKey() + "'"), "settlement loss of " + loss.getKey());
        // a duplicate reports its LOWEST statement line, and whether any line also disagrees with the expected amount
        for (Map.Entry<String, String> low : CORPUS.settlementLowestLine.entrySet())
            assertEquals(List.of(low.getValue()), rows(dataDir, "ra_settlement_findings",
                    "STATEMENT_ID WHERE ITEM_KEY = '" + low.getKey() + "'"), "reported line of " + low.getKey());
        for (Map.Entry<String, Boolean> d : CORPUS.settlementLinesDisagree.entrySet())
            assertEquals(List.of(String.valueOf(d.getValue())), rows(dataDir, "ra_settlement_findings",
                    "CAST(LINES_DISAGREE AS VARCHAR) WHERE ITEM_KEY = '" + d.getKey() + "'"),
                    "lines-disagree flag of " + d.getKey() + " (an exact copy of a correct line is false)");
        assertEquals(3, CORPUS.settlementLinesDisagree.size());
        // a leakage finding always carries its amount, so a sum never silently drops one
        for (String control : List.of("ra_rerating", "ra_rollforward", "ra_settlement"))
            assertEquals(List.of(), rows(dataDir, control + "_findings",
                    "ITEM_KEY WHERE FINDING = 'leakage' AND LEAKAGE_AMOUNT IS NULL"), control);
        // money stays DECIMAL end to end, so a tolerance compares exact cents, never float noise
        for (String control : List.of("ra_rerating", "ra_rollforward", "ra_settlement"))
            for (String t : rows(dataDir, control + "_findings", "DISTINCT typeof(LEAKAGE_AMOUNT)"))
                assertTrue(t.startsWith("DECIMAL"), control + " LEAKAGE_AMOUNT is " + t);
        assertEquals(List.of("45.0000", "90.0000"), rows(dataDir, "ra_settlement_findings",
                "CAST(LEAKAGE_AMOUNT AS VARCHAR) WHERE REASON = 'unknown_partner' ORDER BY LEAKAGE_AMOUNT"),
                "an unknown partner's whole billed total is the leakage, every line of it");
        // Breaks double-count: 15 Breaks are 9 distinct lost or short xDRs
        Set<String> brokenXdrs = new TreeSet<>();
        for (ReconBreaks.Break b : new ReconStateStore(writeRoot).read("ra_xdr_completeness").breaks()) brokenXdrs.add(b.key());
        assertEquals(new TreeSet<>(CORPUS.lostOrShortXdrs), brokenXdrs);
        assertEquals(brokenXdrs, column(dataDir, "ra_xdr_lost", "ITEM_KEY"), "the lost-xDR Dataset counts xDRs, not Breaks");
    }

    /**
     * The findings Jobs are chained with {@code on_signal}, not cron-staggered: one trigger of {@code ra_rerating}
     * runs rollforward, settlement, then leakage and data quality, each only after its predecessor SUCCEEDED.
     */
    @Test
    void theFindingsJobsChainOnSignalFromOneTrigger(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        loadCorpus(dataDir);
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : List.of("ra_rerating", "ra_rollforward", "ra_settlement", "ra_leakage", "ra_data_quality"))
            jobs.add(JobConfig.load(templateJob(j).toString()));
        assertTrue(jobs.get(0).hasCron(), "the head of the chain keeps its cron");
        for (JobConfig j : jobs.subList(1, jobs.size()))
            assertTrue(j.hasSignal() && !j.hasCron(), j.name() + " is signal-triggered, never cron-staggered");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.eventLog(com.gamma.event.EventLog.global());
            js.start();
            assertTrue(js.triggerRun("ra_rerating", null).isPresent());
            for (String j : List.of("ra_rerating", "ra_rollforward", "ra_settlement", "ra_leakage", "ra_data_quality")) {
                // a SKIPPED run is the when guard turning an earlier link's completion away — wait it out
                JobRun run = await(() -> js.runsFor(j).stream().filter(r -> !"SKIPPED".equals(r.status())).findFirst().orElse(null));
                assertEquals("SUCCESS", run.status(), j + ": " + run.message());
            }
        }
        Set<String> dq = Set.of("ambiguous_tariff", "no_tariff", "null_value", "missing_statement", "split_statement");
        long total = 0, plantedDq = 0;
        for (String control : List.of("ra_rerating", "ra_rollforward", "ra_settlement")) {
            total += CORPUS.planted.get(control).size();
            plantedDq += CORPUS.planted.get(control).stream()
                    .filter(k -> dq.contains(k.substring(k.lastIndexOf('|') + 1))).count();
        }
        assertEquals(total - plantedDq, rows(dataDir, "ra_leakage", "CONTROL").size(), "leakage rows via the chain");
        assertEquals(plantedDq, rows(dataDir, "ra_data_quality", "CONTROL").size(), "data-quality rows via the chain");
    }

    /** The golden counts, pinned: a corpus change that moves them must be deliberate. */
    @Test
    void theGoldenCountsArePinned() {
        assertEquals(15, CORPUS.planted.get("ra_xdr_completeness").size(), "4 dropped at mediation (AB+AC), 3 at rating (AC), 2 truncated (AB+AC)");
        assertEquals(9, CORPUS.lostOrShortXdrs.size(), "the 15 Breaks are 9 distinct xDRs");
        assertEquals(9, CORPUS.planted.get("ra_xdr_lost").size());
        assertEquals(4, CORPUS.planted.get("ra_rated_vs_billed").size(), "1 unbilled, 3 under-billed");
        assertEquals(5, CORPUS.planted.get("ra_rerating").stream().filter(k -> k.endsWith("|rate_mismatch")).count(),
                "2 half-rated + 3 still on the pre-change rate");
        assertEquals(AMBIGUOUS, CORPUS.planted.get("ra_rerating").stream().filter(k -> k.endsWith("|ambiguous_tariff")).count(),
                "calls under the duplicate tariff row: data quality, one row each");
        assertEquals(6, CORPUS.planted.get("ra_rollforward").size(), "3 movement, 2 continuity, 1 null opening");
        assertEquals(10, CORPUS.planted.get("ra_settlement").size(),
                "2 over-billed, 1 missing statement, 2 unknown partner (one duplicated), 3 duplicate statements, 2 split statements");
        assertEquals(1, CORPUS.planted.get("ra_rerating").stream().filter(k -> k.endsWith("|no_tariff")).count());
        assertEquals(Map.of("ra_xdr_completeness", 4, "ra_xdr_lost", 4, "ra_rerating", 6, "ra_rated_vs_billed", 3,
                        "ra_rollforward", 5, "ra_settlement", 2),
                CORPUS.benign.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size())),
                "benign look-alikes per control");
    }

    /** The recovery view reads the impact ledger's LATEST snapshot only — summing every snapshot would overcount. */
    @Test
    void recoveryReadsOnlyTheLatestImpactLedgerSnapshot(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Path ledger = dataDir.resolve("impact_ledger");
        Files.createDirectories(ledger);
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (int snap = 1; snap <= 2; snap++)
                st.execute("COPY (SELECT 'INC-1' AS object_id, 'INCIDENT' AS object_type, 'RESOLVED' AS status,"
                        + " 'RECOVERED' AS disposition, 'revenue' AS category, 'USD' AS currency,"
                        + " 10.0::DECIMAL(21,6) AS suspected, 10.0::DECIMAL(21,6) AS confirmed,"
                        + " (" + snap + " * 4.0)::DECIMAL(21,6) AS recovered, 0.0::DECIMAL(21,6) AS prevented,"
                        + " (10.0 - " + snap + " * 4.0)::DECIMAL(21,6) AS outstanding,"
                        + " TIMESTAMP '2026-07-0" + snap + " 00:00:00' AS sampled_at) TO '"
                        + ledger.resolve("impact_" + snap + "_out.parquet").toString().replace('\\', '/') + "' (FORMAT PARQUET)");
        }
        JobConfig job = JobConfig.load(templateJob("ra_recovery").toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     dir.resolve("audit").toString(), null, null, dataDir.toString())) {
            js.start();
            js.triggerRun(job.name(), null);
            JobRun run = await(() -> js.lastRunOf(job.name()).orElse(null));
            assertEquals("SUCCESS", run.status(), run.message());
        }
        assertEquals(Set.of("8.000000"), column(dataDir, "ra_recovery", "CAST(recovered AS VARCHAR)"));
    }

    /**
     * The REAL path ({@code ASSURE-PACK-TELCO-RA-1} (3)): a Space created from the template, the corpus dropped into
     * each feed's inbox and ingested by the template's own eight Pipelines, then the template's Jobs, Alert Rules and
     * Expectations — no store is loaded by hand. Same pinned findings as the direct-load test, plus the outcomes.
     */
    @Test
    void theTemplateIngestsEvaluatesAndAlertsEndToEnd(@TempDir Path root) throws Exception {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        copyTree(TEMPLATE, root.resolve("_templates").resolve("telco-ra"));
        Path samples = TEMPLATE.resolve("data").resolve("samples");
        try (com.gamma.service.SpaceManager spaces = com.gamma.service.SpaceManager.discover(root)) {
            com.gamma.service.SpaceContext space = spaces.createFromTemplate(
                    com.gamma.service.SpaceId.of("ra"), null, null, "telco-ra");
            Path base = space.root().base();
            Path dataDir = base.resolve("data");
            com.gamma.service.CollectorService svc = space.service();

            // ── ingest: every feed through its own Pipeline ──
            for (String file : CORPUS.files.keySet()) {
                String feed = file.substring(0, file.indexOf('/'));
                Files.createDirectories(dataDir.resolve("inbox").resolve(feed));
                Files.copy(samples.resolve(file), dataDir.resolve("inbox").resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
            for (String feed : List.of("switch_xdr", "mediated_xdr", "rated_usage", "billed_invoice", "tariff",
                    "balance_ledger", "ic_rates", "ic_statement")) {
                var r = svc.runPipeline(feed);
                assertTrue(r.isPresent(), feed + ": the Pipeline is registered in the created Space");
                assertEquals(new com.gamma.inspector.MultiCollectorProcessor.RunResult(1, 0), r.get(), feed + " ingests its one file");
            }

            // ── Jobs ──
            Map<String, String> seeded = new java.util.TreeMap<>();
            for (String d : List.of("ra_leakage", "ra_data_quality")) seeded.put(d, schema(dataDir, d));
            com.gamma.job.JobService js = svc.jobService().orElseThrow();
            js.eventLog(com.gamma.event.EventLog.global());
            for (String j : List.of("ra_xdr_lost", "ra_xdr_completeness", "ra_rated_vs_billed", "ra_rerating")) {
                assertTrue(js.triggerRun(j, null).isPresent(), j);
                JobRun run = await(() -> js.runsFor(j).stream().filter(r -> !"SKIPPED".equals(r.status())).findFirst().orElse(null));
                assertEquals("SUCCESS", run.status(), j + ": " + run.message());
            }
            for (String j : List.of("ra_rollforward", "ra_settlement", "ra_leakage", "ra_data_quality")) {   // the on_signal chain
                JobRun run = await(() -> js.runsFor(j).stream().filter(r -> !"SKIPPED".equals(r.status())).findFirst().orElse(null));
                assertEquals("SUCCESS", run.status(), j + ": " + run.message());
            }
            for (String d : seeded.keySet()) {
                assertFalse(Files.exists(dataDir.resolve(d).resolve("schema-seed.parquet")), d + ": the first run replaced the seed");
                assertEquals(seeded.get(d), schema(dataDir, d), d + ": the shipped zero-row seed declares exactly the Job's schema");
            }
            assertEquals(17, rows(dataDir, "ra_leakage", "CONTROL").size(), "leakage rows, ingested end to end");
            assertEquals(17, rows(dataDir, "ra_data_quality", "CONTROL").size(), "data-quality rows, ingested end to end");
            for (String control : List.of("ra_xdr_lost", "ra_rerating", "ra_rollforward", "ra_settlement")) {
                String store = "ra_xdr_lost".equals(control) ? control : control + "_findings";
                assertEquals(new TreeSet<>(CORPUS.planted.get(control)),
                        new TreeSet<>(rows(dataDir, store, "ITEM_KEY || '|' || REASON")), control);
            }
            for (String recon : List.of("ra_xdr_completeness", "ra_rated_vs_billed")) {
                Set<String> flagged = new TreeSet<>();
                for (ReconBreaks.Break b : new ReconStateStore(base.resolve("config")).read(recon).breaks())
                    flagged.add(b.pair() + "|" + b.type() + "|" + b.key());
                assertEquals(new TreeSet<>(CORPUS.planted.get(recon)), flagged, recon);
            }

            // ── KPI definitions over the findings Datasets ──
            var kpis = new com.gamma.pipeline.ComponentStore(base.resolve("config/registry"));
            assertEquals(Set.of("leakage_found", "leakage_items", "data_quality_items"),
                    kpis.list("kpi").stream().map(com.gamma.pipeline.ComponentRegistry.Component::name).collect(Collectors.toSet()));
            java.time.LocalDate july = java.time.LocalDate.of(2026, 7, 31);   // every planted finding is dated in July 2026
            Map<String, Double> kpiValues = new java.util.TreeMap<>();
            for (var k : kpis.list("kpi")) {
                var def = com.gamma.query.KpiDefinition.fromMap(k.name(), k.content());
                var ds = kpis.get("dataset", def.dataset()).orElseThrow().content();
                kpiValues.put(k.name(), com.gamma.query.KpiEvaluator.evaluate(def,
                        com.gamma.query.DatasetRelation.relationSql(ds, dataDir, null), july).value());
            }
            assertEquals(Map.of("leakage_found", 161.16, "leakage_items", 17.0, "data_quality_items", 17.0), kpiValues,
                    "the KPI definitions read the pinned leakage / data-quality Datasets");

            // ── Alert Rules: one Alert per control (by CONTROL), never per entity (G-42) ──
            List<com.gamma.alert.Alert> alerts = svc.alertService().orElseThrow().evaluateRules();
            Map<String, Double> perControl = new java.util.TreeMap<>();
            for (com.gamma.alert.Alert a : alerts) {
                var m = java.util.regex.Pattern.compile("CONTROL=(\\w+)").matcher(a.message());
                assertTrue(m.find(), a.message());
                assertNull(perControl.put(a.rule() + "|" + a.severity() + "|" + m.group(1), a.value()), "one Alert per control: " + alerts);
            }
            assertEquals(Map.of(
                    "ra_leakage_found|CRITICAL|ra_rerating", 5.0, "ra_leakage_found|CRITICAL|ra_rollforward", 5.0,
                    "ra_leakage_found|CRITICAL|ra_settlement", 7.0,
                    "ra_data_quality|WARNING|ra_rerating", 13.0, "ra_data_quality|WARNING|ra_rollforward", 1.0,
                    "ra_data_quality|WARNING|ra_settlement", 3.0), perControl, "exactly one Alert per firing control and rule");

            // ── Expectations: the shipped two pass the clean corpus; a probe that would otherwise fail proves they can ──
            for (String name : List.of("ra_switch_xdr_id_non_null", "ra_rated_charge_non_negative")) {
                var exp = com.gamma.expectation.Expectation.fromMap(
                        com.gamma.util.ToonHelper.load(base.resolve("config/registry/expectations/" + name + ".toon").toString()));
                var res = com.gamma.expectation.ExpectationEvaluator.evaluate(exp, dataDir);
                assertEquals("PASSED", res.status(), name + " violations=" + res.violations());
            }
            var nullOpening = com.gamma.expectation.Expectation.fromMap(Map.of("name", "probe", "targetType", "pipeline",
                    "target", "balance_ledger", "column", "OPENING", "kind", "non_null", "severity", "MAJOR"));
            assertEquals(1, com.gamma.expectation.ExpectationEvaluator.evaluate(nullOpening, dataDir).violations(),
                    "the planted blank opening balance is a violation, so a non_null Expectation does fire");
        } finally {
            com.gamma.etl.EditionFeatures.overrideForTest(null);
        }
    }

    // ── helpers ──

    private static String schema(Path dataDir, String store) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("DESCRIBE SELECT * FROM read_parquet('"
                     + dataDir.resolve(store).toString().replace('\\', '/') + "/*.parquet')")) {
            while (rs.next()) sb.append(rs.getString(1)).append(' ').append(rs.getString(2)).append(';');
        }
        return sb.toString();
    }

    private static Path templateJob(String name) {
        return TEMPLATE.resolve("config").resolve("jobs").resolve(name + "_job.toon");
    }

    /** Each feed's CSV → {@code <dataDir>/<feed>/database/data.parquet}, typed as the feed's schema declares. */
    private static void loadCorpus(Path dataDir) throws Exception {
        Path samples = dir(dataDir.resolve("samples"));
        CORPUS.writeTo(samples);
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String file : CORPUS.files.keySet()) {
                String feed = file.substring(0, file.indexOf('/'));
                Path out = dir(dataDir.resolve(feed).resolve("database")).resolve("data.parquet");
                st.execute("COPY (SELECT * FROM read_csv('" + samples.resolve(file).toString().replace('\\', '/')
                        + "', header = true, types = " + types(feed) + ")) TO '"
                        + out.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
        }
    }

    /** The column types the feed's shipped schema declares, as a DuckDB {@code read_csv} types struct. */
    private static String types(String feed) throws Exception {
        List<String> lines = Files.readAllLines(TEMPLATE.resolve("config").resolve(feed).resolve(feed + "_schema.toon"));
        StringBuilder sb = new StringBuilder("{");
        boolean in = false;
        for (String l : lines) {
            if (l.trim().startsWith("fields[") && l.contains("{name,")) { in = true; continue; }
            if (in && !l.startsWith("    ")) break;
            if (in) {
                String[] parts = l.trim().split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (int i = 0; i < parts.length; i++) parts[i] = parts[i].replace("\"", "");
                if (sb.length() > 1) sb.append(", ");
                sb.append('\'').append(parts[0]).append("': '").append(parts[2]).append('\'');
            }
        }
        return sb.append('}').toString();
    }

    /** Every value of {@code expr} over the store, duplicates kept; {@code expr} may carry a trailing WHERE. */
    private static List<String> rows(Path dataDir, String store, String expr) throws Exception {
        String select = expr.contains(" WHERE ") ? expr.substring(0, expr.indexOf(" WHERE ")) : expr;
        String where = expr.contains(" WHERE ") ? expr.substring(expr.indexOf(" WHERE ")) : "";
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + select + " FROM read_parquet('"
                     + dataDir.resolve(store).toString().replace('\\', '/') + "/*.parquet')" + where)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Set<String> column(Path dataDir, String store, String expr) throws Exception {
        Set<String> out = new TreeSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT " + expr + " FROM read_parquet('"
                     + dataDir.resolve(store).toString().replace('\\', '/') + "/*.parquet')")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static Path dir(Path p) throws Exception {
        return Files.createDirectories(p);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 30s");
        return r;
    }

    /** A JobContext that swallows Signals — the Break state store is what this test reads. */
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
