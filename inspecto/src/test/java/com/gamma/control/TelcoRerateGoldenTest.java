package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code PACK-RERATING-1} slices R1 and R2 golden test (R2 adds the explanation Job {@code rr_explain}, the root-cause Job
 * {@code rr_root_cause}, the revenue-at-risk columns and KPIs, and the corpus rows that pin the R1 clauses it left open).
 *
 * <p>Slice R1: Boots the shipped {@code spaces/_templates/telco-rerating} Space
 * Template through {@code POST /spaces} (every seed gate), ingests the synthetic {@link RerateCorpus} through the
 * template's own Pipelines on the production Consignment path, runs {@code rr_tariff_hygiene} and {@code rr_rerate}
 * as authored, and holds their rows to
 * <ul>
 *   <li>an INDEPENDENT oracle (plain Java and {@link BigDecimal}, no SQL) for every contract column of every call and
 *       every column of every tariff finding;</li>
 *   <li>the planted set: exactly the planted leakage, over-billing, range and no-tariff calls, exactly the planted
 *       tariff defects, every look-alike silent; the twelve calls a duplicate same-rate row makes ambiguous in the
 *       starter control become zero call findings and one tariff finding;</li>
 *   <li>degradation (no PRIORITY column), the tolerance parameter, host-zone independence, idempotent re-runs, the
 *       on-signal chain, and</li>
 *   <li>a clause-by-clause mutation pass: every mutant of either statement changes the rows the oracle pins.</li>
 * </ul>
 */
class TelcoRerateGoldenTest {

    private static final Map<String, String> FEED_COLUMNS = new LinkedHashMap<>();
    static {
        FEED_COLUMNS.put("tariff_version", "{'TARIFF_VERSION_ID':'VARCHAR','PLAN':'VARCHAR','SERVICE':'VARCHAR','RATE_PER_UNIT':'DECIMAL(18,4)',"
                + "'EFFECTIVE_FROM':'TIMESTAMP','EFFECTIVE_TO':'TIMESTAMP','PRIORITY':'INTEGER'}");
        FEED_COLUMNS.put("rated_usage", "{'XDR_ID':'VARCHAR','EVENT_TS':'TIMESTAMP','EVENT_DATE':'DATE','SUBSCRIBER_ID':'VARCHAR','PLAN':'VARCHAR',"
                + "'SERVICE':'VARCHAR','USAGE_UNITS':'BIGINT','CHARGE':'DECIMAL(18,4)'}");
    }

    private static final LocalDate DAY1 = RerateCorpus.DAY;
    private static final Map<String, String> WINDOW1 = window(DAY1);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal WITHIN = new BigDecimal("0.9"), FLOOR = new BigDecimal("0.5");
    private static final LocalDateTime FAR = LocalDateTime.of(9999, 12, 31, 0, 0);

    private static boolean regenerate() { return "true".equals(System.getenv("TELCO_RERATING_REGENERATE")); }

    private static Map<String, String> window(LocalDate day) {
        return Map.of("window_start", day + " 00:00:00", "window_end", day.plusDays(1) + " 00:00:00", "day", day.toString());
    }

    private static Path template() {
        Path p = Path.of("..", "spaces", "_templates", "telco-rerating").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(p), "the shipped template is missing: " + p);
        return p;
    }

    @BeforeEach
    void arm() {
        com.gamma.etl.EditionFeatures.overrideForTest(Set.of(com.gamma.etl.EditionFeatures.ALERT_DISPATCH));
        Authenticators.forTest(ex -> Optional.of(new Subject("admin-1",
                Set.of(Roles.CAN_ADMINISTER, Roles.CAN_AUTHOR_WORKBENCH, Roles.CAN_AUTHOR_ALERT_RULES))));
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        com.gamma.etl.EditionFeatures.overrideForTest(null);
        MetricRegistry.global().reset();
    }

    // ------------------------------------------------------------------------------------------- the shipped template

    @Test
    void theTemplateShipsFourChainedJobsAndSixDatasetsAndItsFilesReachTheSpace(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path config = space.resolve("config");
        Map<String, JobConfig> jobs = jobs(config);
        assertEquals(Set.of("rr_tariff_hygiene", "rr_rerate", "rr_explain", "rr_root_cause"), jobs.keySet(), "slice R2 ships exactly these four Jobs");
        JobConfig explain = jobs.get("rr_explain"), roots = jobs.get("rr_root_cause");
        assertTrue(explain.hasSignal() && !explain.hasCron(), "rr_explain runs when the re-rating has succeeded");
        assertEquals("job.run.completed", explain.onSignal());
        assertEquals("$signal.job == rr_rerate && $signal.outcome == SUCCESS", explain.when());
        assertEquals("$yesterday", explain.params().get("window_start"));
        assertEquals("window_date", explain.params().get("incremental.column"));
        assertEquals("31", explain.params().get("incremental.retain"));
        assertEquals("rerating_findings", explain.params().get("sink_dataset"));
        assertEquals(Set.of("rerated_usage", "tariff_version"), new TreeSet<>(List.of(explain.params().get("sources").split(","))));
        assertEquals("0.02", explain.params().get("tolerance"));
        assertEquals("0.9", explain.params().get("within_tolerance_confidence"));
        assertEquals("0.5", explain.params().get("confidence_floor"));
        for (String literal : List.of("0.02", "0.9)", "0.5)")) assertFalse(explain.params().get("sql").contains(literal), "no threshold literal in the statement: " + literal);
        assertTrue(roots.hasSignal() && !roots.hasCron(), "rr_root_cause runs when the explanation has succeeded");
        assertEquals("$signal.job == rr_explain && $signal.outcome == SUCCESS", roots.when());
        assertEquals("rerating_findings", roots.params().get("sources"));
        assertEquals("rerating_root_causes", roots.params().get("sink_dataset"));
        assertNull(roots.params().get("incremental.column"), "the root-cause table is a whole snapshot, replaced on every run");
        JobConfig hygiene = jobs.get("rr_tariff_hygiene"), rerate = jobs.get("rr_rerate");
        assertTrue(hygiene.hasCron() && !hygiene.hasSignal(), "the head of the chain keeps its cron");
        assertEquals("30 4 * * *", hygiene.cron());
        assertEquals("tariff_version", hygiene.params().get("sources"));
        assertEquals("rerating_tariff_findings", hygiene.params().get("sink_dataset"));
        assertTrue(rerate.hasSignal() && !rerate.hasCron(), "rr_rerate runs when the tariff table has been judged, never on its own timer");
        assertEquals("job.run.completed", rerate.onSignal());
        assertEquals("$signal.job == rr_tariff_hygiene && $signal.outcome == SUCCESS", rerate.when());
        assertEquals("$yesterday", rerate.params().get("window_start"));
        assertEquals("$today", rerate.params().get("window_end"));
        assertEquals("$yesterday", rerate.params().get("day"));
        assertEquals("window_date", rerate.params().get("incremental.column"));
        assertEquals("31", rerate.params().get("incremental.retain"));
        assertEquals("rerated_usage", rerate.params().get("sink_dataset"));
        assertEquals(Set.of("rated_usage", "tariff_version"), new TreeSet<>(List.of(rerate.params().get("sources").split(","))));
        assertEquals("0.02", rerate.params().get("tolerance"), "the tolerance is a Job parameter, not hidden in the SQL");
        assertFalse(rerate.params().get("sql").contains("0.02"), "no tolerance literal in the statement");
        try (Stream<Path> s = Files.list(config.resolve("registry").resolve("datasets"))) {
            assertEquals(Set.of("tariff_version.toon", "rated_usage.toon", "rerating_tariff_findings.toon", "rerated_usage.toon",
                    "rerating_findings.toon", "rerating_root_causes.toon"), new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()));
        }
        try (Stream<Path> s = Files.list(config.resolve("registry").resolve("kpis"))) {
            assertEquals(Set.of("revenue_at_risk.toon", "overbilled_amount.toon"), new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()));
        }
        String kpiRisk = Files.readString(config.resolve("registry").resolve("kpis").resolve("revenue_at_risk.toon")),
                kpiOver = Files.readString(config.resolve("registry").resolve("kpis").resolve("overbilled_amount.toon"));
        assertTrue(kpiRisk.contains("dataset: rerating_findings") && kpiRisk.contains("measure: sum(LEAKAGE_LOW)"), "revenue at risk is the LOW leakage, never the over-billing");
        assertTrue(kpiOver.contains("dataset: rerating_findings") && kpiOver.contains("measure: sum(OVERBILLED_AMOUNT)"));
        assertFalse(kpiRisk.contains("OVERBILLED"), "no over-billing in revenue at risk");
        assertTrue(Files.isRegularFile(config.resolve("runbooks").resolve("telco-rerating-runbooks.md")), "the runbook skeleton ships");
        for (String feed : FEED_COLUMNS.keySet())
            assertTrue(Files.isRegularFile(space.resolve("data").resolve(feed).resolve("database").resolve("schema-seed.parquet")), feed + " seed");
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("rerating_tariff_findings").resolve("schema-seed.parquet")));
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("rerated_usage").resolve("window_date=1970-01-01").resolve("seed.parquet")));
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("rerating_findings").resolve("window_date=1970-01-01").resolve("seed.parquet")));
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("rerating_root_causes").resolve("schema-seed.parquet")));
    }

    /**
     * Every Dataset a Job reads or writes ships a ZERO-row seed with exactly the Schema the real thing has, so both Jobs
     * run on a fresh Space and the Datasets have a Schema before their first run. The feed seeds are pinned to the
     * Pipelines' real output, the two Job-written seeds to the Jobs' own SQL.
     */
    @Test
    void everyDatasetShipsAnEmptySeedWithTheSchemaOfTheRealThing(@TempDir Path root) throws Exception {
        Path tpl = template();
        DuckDbUtil.loadDriver();
        Map<String, JobConfig> jobs = jobs(tpl.resolve("config"));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (Map.Entry<String, String> feed : FEED_COLUMNS.entrySet()) {
                Path seed = tpl.resolve("data").resolve(feed.getKey()).resolve("database").resolve("schema-seed.parquet");
                if (regenerate()) {
                    Files.createDirectories(seed.getParent());
                    st.execute("COPY (SELECT * FROM read_csv('" + emptyCsv(feed.getValue()) + "', header = true, columns = "
                            + feed.getValue() + ")) TO '" + seed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                }
                assertTrue(Files.exists(seed), feed.getKey() + " has no seed at " + seed);
                st.execute("CREATE OR REPLACE TABLE " + feed.getKey() + " AS SELECT * FROM read_parquet('" + seed.toString().replace('\\', '/') + "')");
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + feed.getKey())) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), feed.getKey() + ": a seed carries no rows");
                }
            }
            // the Job-written seeds: the Jobs' SQL, run over the empty feeds, describes exactly the seed
            Path hySeed = tpl.resolve("data").resolve("rerating_tariff_findings").resolve("schema-seed.parquet");
            String hySql = substitute(jobs.get("rr_tariff_hygiene").params().get("sql"), jobs.get("rr_tariff_hygiene").params());
            Path ruSeed = tpl.resolve("data").resolve("rerated_usage").resolve("window_date=1970-01-01").resolve("seed.parquet");
            Map<String, String> rp = new HashMap<>(jobs.get("rr_rerate").params());
            rp.putAll(WINDOW1);
            String ruSql = substitute(jobs.get("rr_rerate").params().get("sql"), rp);
            if (regenerate()) {
                Files.createDirectories(ruSeed.getParent());
                Files.createDirectories(hySeed.getParent());
                st.execute("COPY (SELECT * FROM (" + hySql + ") LIMIT 0) TO '" + hySeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                st.execute("COPY (SELECT * EXCLUDE (window_date) FROM (" + ruSql + ") LIMIT 0) TO '" + ruSeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            // the R2 seeds: the explanation reads the re-rating's empty seed, the root-cause Job the explanation's
            Path exSeed = tpl.resolve("data").resolve("rerating_findings").resolve("window_date=1970-01-01").resolve("seed.parquet");
            Path rcSeed = tpl.resolve("data").resolve("rerating_root_causes").resolve("schema-seed.parquet");
            Map<String, String> ep = new HashMap<>(jobs.get("rr_explain").params());
            ep.putAll(WINDOW1);
            String exSql = substitute(jobs.get("rr_explain").params().get("sql"), ep);
            String rcSql = substitute(jobs.get("rr_root_cause").params().get("sql"), jobs.get("rr_root_cause").params());
            if (regenerate()) {
                Files.createDirectories(exSeed.getParent());
                Files.createDirectories(rcSeed.getParent());
                st.execute("CREATE OR REPLACE TABLE rerated_usage AS SELECT * FROM read_parquet('" + ruSeed.toString().replace('\\', '/') + "', hive_partitioning = true)");
                st.execute("COPY (SELECT * EXCLUDE (window_date) FROM (" + exSql + ") LIMIT 0) TO '" + exSeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
                st.execute("CREATE OR REPLACE TABLE rerating_findings AS SELECT * FROM read_parquet('" + exSeed.toString().replace('\\', '/') + "', hive_partitioning = true)");
                st.execute("COPY (SELECT * FROM (" + rcSql + ") LIMIT 0) TO '" + rcSeed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            st.execute("CREATE OR REPLACE TABLE rerated_usage AS SELECT * FROM read_parquet('" + ruSeed.toString().replace('\\', '/') + "', hive_partitioning = true)");
            assertEquals(describe(st, "(SELECT * EXCLUDE (window_date), window_date FROM (" + exSql + ") LIMIT 0)"),
                    describe(st, "read_parquet('" + exSeed.toString().replace('\\', '/') + "', hive_partitioning = true)"),
                    "the rerating_findings seed drifted from the Job's SQL - regenerate with env TELCO_RERATING_REGENERATE=true");
            st.execute("CREATE OR REPLACE TABLE rerating_findings AS SELECT * FROM read_parquet('" + exSeed.toString().replace('\\', '/') + "', hive_partitioning = true)");
            assertEquals(describe(st, "(SELECT * FROM (" + rcSql + ") LIMIT 0)"), describe(st, "read_parquet('" + rcSeed.toString().replace('\\', '/') + "')"),
                    "the rerating_root_causes seed drifted from the Job's SQL - regenerate with env TELCO_RERATING_REGENERATE=true");
            assertEquals(describe(st, "(SELECT * FROM (" + hySql + ") LIMIT 0)"), describe(st, "read_parquet('" + hySeed.toString().replace('\\', '/') + "')"),
                    "the rerating_tariff_findings seed drifted from the Job's SQL - regenerate with env TELCO_RERATING_REGENERATE=true");
            assertEquals(describe(st, "(SELECT * EXCLUDE (window_date), window_date FROM (" + ruSql + ") LIMIT 0)"),
                    describe(st, "read_parquet('" + ruSeed.toString().replace('\\', '/') + "')") ,
                    "the rerated_usage seed drifted from the Job's SQL - regenerate with env TELCO_RERATING_REGENERATE=true");
        }
        // the feed seeds equal what the Pipelines really write
        Path space = createSpace(root);
        ingest(space, new RerateCorpus().generate().files(true));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String feed : FEED_COLUMNS.keySet()) {
                Path db = space.resolve("data").resolve(feed).resolve("database");
                List<String> files = new ArrayList<>();
                try (Stream<Path> w = Files.walk(db)) {
                    for (Path p : w.filter(f -> f.toString().endsWith(".parquet") && !f.getFileName().toString().equals("schema-seed.parquet")).toList())
                        files.add("'" + p.toString().replace('\\', '/') + "'");
                }
                assertFalse(files.isEmpty(), feed + ": the Pipeline wrote no file");
                assertEquals(describe(st, "read_parquet([" + String.join(",", files) + "], hive_partitioning = true)"),
                        describe(st, "read_parquet('" + db.resolve("schema-seed.parquet").toString().replace('\\', '/') + "')"),
                        feed + ": the seed's Schema is not the Pipeline's output Schema");
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the golden run

    /** Every contract column of every call equals an independent plain-Java re-rating; one row per call in the window. */
    @Test
    void everyReRatedCallEqualsTheIndependentOracle(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Map<String, Map<String, Object>> want = oracleCalls(corpus, TOLERANCE);
        assertEquals(want.keySet(), r.calls.keySet(), "one row per call in the window, and nothing else");
        for (String id : corpus.outsideWindow) assertFalse(r.calls.containsKey(id), id + " is outside the window");
        assertEquals(corpus.usage().size() - corpus.outsideWindow.size(), r.calls.size());
        for (String id : want.keySet()) assertSameRow(id, want.get(id), r.calls.get(id));
        assertEquals(21, r.calls.values().iterator().next().size(), "the contract is 21 columns incl. window_date");
        System.out.println("RERATE-R1 rerated_usage: " + r.calls.size() + " calls, " + corpus.tariffs().size() + " tariff rows, jobs "
                + r.hygieneMs + " + " + r.rerateMs + " ms");
    }

    /** The findings are exactly the planted ones, by class; every look-alike is silent and no amount nets against another. */
    @Test
    void theFindingsAreExactlyThePlantedSetAndNoLookAlike(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Map<String, String> got = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> e : r.calls.entrySet()) {
            Map<String, Object> row = e.getValue();
            if (row.get("FINDING") == null) {
                assertNull(row.get("REASON"), e.getKey());
                continue;
            }
            BigDecimal amount = row.get("FINDING").equals("leakage") ? (BigDecimal) row.get("UNDERCHARGED_AMOUNT")
                    : row.get("FINDING").equals("overbilled") ? (BigDecimal) row.get("OVERBILLED_AMOUNT") : null;
            got.put(e.getKey(), row.get("REASON") + "|" + row.get("FINDING") + "|" + (amount == null ? "" : amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString()));
        }
        assertEquals(corpus.planted, got, "the findings: exactly the planted set");
        Map<String, Long> byClass = new TreeMap<>();
        for (String v : got.values()) byClass.merge(v.substring(0, v.lastIndexOf('|')), 1L, Long::sum);
        assertEquals(Map.of("rate_mismatch|leakage", 33L, "rate_mismatch|overbilled", 8L, "ambiguous_tariff|data_quality", 9L, "no_tariff|data_quality", 11L,
                "unratable_call|data_quality", 2L), byClass);
        for (String id : corpus.benign) {
            assertNotNull(r.calls.get(id), id + " is in the window");
            assertNull(r.calls.get(id).get("FINDING"), id + " is a look-alike and must stay silent");
        }
        // no netting: an over-billed call carries no under-charge and the other way round, and the two sums are separate figures
        BigDecimal under = BigDecimal.ZERO, over = BigDecimal.ZERO;
        for (Map<String, Object> row : r.calls.values()) {
            BigDecimal u = (BigDecimal) row.get("UNDERCHARGED_AMOUNT"), o = (BigDecimal) row.get("OVERBILLED_AMOUNT");
            if (u == null) { assertNull(o); continue; }
            assertTrue(u.signum() == 0 || o.signum() == 0, row.get("ITEM_KEY") + ": a call is under-charged or over-billed, never both");
            assertTrue(u.signum() >= 0 && o.signum() >= 0);
            under = under.add(u);
            over = over.add(o);
        }
        assertEquals(0, plantedSum(corpus, "rate_mismatch|leakage|").compareTo(under), "revenue at risk of the planted leakage, nothing netted against over-billing");
        assertEquals(0, plantedSum(corpus, "rate_mismatch|overbilled|").compareTo(over), "the over-billed amount is its own figure");
        assertEquals(0, new BigDecimal("36.84").compareTo(under), "the planted leakage, summed by hand");
        assertEquals(0, new BigDecimal("10.76").compareTo(over), "the planted over-billing, summed by hand");
    }

    /**
     * The headline of the plan: the starter control raises 12 ambiguous-tariff rows for ONE duplicate tariff row with the SAME
     * rate. Here the twelve calls are judged against the one agreed amount (zero call findings) and the duplicate row is ONE
     * tariff finding. A wrong charge on that group is still found.
     */
    @Test
    void twelveSameRateAmbiguousCallsBecomeNoCallFindingsAndOneTariffFinding(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        assertEquals(12, corpus.archetypes.get("B_AGREE").size());
        for (String id : corpus.archetypes.get("B_AGREE")) {
            Map<String, Object> row = r.calls.get(id);
            assertEquals("agree", row.get("RESOLUTION"), id);
            assertEquals(2L, row.get("CANDIDATES"));
            assertEquals("B-D-1,B-D-1B", row.get("CANDIDATE_VERSIONS"));
            assertEquals("B-D-1", row.get("TARIFF_VERSION_ID"), "agree: the first version in id order stands for the group");
            assertNull(row.get("FINDING"), id + ": the duplicate row does not make the call a finding");
            assertEquals(0, ((BigDecimal) row.get("EXPECTED_LOW")).compareTo((BigDecimal) row.get("EXPECTED_HIGH")));
        }
        assertEquals("rate_mismatch|leakage|1.00", corpus.planted.get("XB_AGREE_LEAK-1"), "a wrong charge inside the agreed group is still leakage");
        assertEquals("leakage", r.calls.get("XB_AGREE_LEAK-1").get("FINDING"));
        Set<String> dupes = new TreeSet<>();
        for (Map<String, Object> t : r.tariff.values())
            if ("PLAN_B".equals(t.get("PLAN")) && "DATA".equals(t.get("SERVICE"))) dupes.add(t.get("REASON") + "|" + t.get("TARIFF_VERSION_ID") + "|" + t.get("OTHER_TARIFF_VERSION_ID"));
        assertEquals(Set.of("overlap_same_rate|B-D-1|B-D-1B"), dupes, "ONE tariff finding for the one duplicate row");
    }

    /** Different rates: a range, not a guess. Inside it (and AT its tolerated edges) is data quality; outside it is leakage against the nearest bound. */
    @Test
    void anOverlapWithDifferentRatesGivesARangeAndOnlyTheOutsideIsLeakage(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        for (String id : corpus.archetypes.get("C_RANGE")) {
            Map<String, Object> row = r.calls.get(id);
            assertEquals("disagree", row.get("RESOLUTION"), id);
            assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) row.get("EXPECTED_LOW")), id);
            assertEquals(0, new BigDecimal("3.00").compareTo((BigDecimal) row.get("EXPECTED_HIGH")), id);
            assertNull(row.get("EXPECTED_CHARGE"), "a range has no single expected charge");
            assertNull(row.get("TARIFF_VERSION_ID"), "no row stands for a disagreement");
            assertEquals("C-V-1,C-V-1X", row.get("CANDIDATE_VERSIONS"));
        }
        assertEquals("ambiguous_tariff", r.calls.get("XC_RANGE-1").get("REASON"));
        assertEquals("data_quality", r.calls.get("XC_RANGE-1").get("FINDING"), "inside the range: explained, not leakage");
        assertEquals("data_quality", r.calls.get("XC_RANGE-2").get("FINDING"), "at the high bound");
        assertEquals("data_quality", r.calls.get("XC_RANGE-4").get("FINDING"), "the low bound less the tolerance: still explained");
        assertEquals("data_quality", r.calls.get("XC_RANGE-6").get("FINDING"), "the high bound plus the tolerance: still explained");
        assertEquals("leakage", r.calls.get("XC_RANGE-5").get("FINDING"), "a cent beyond the tolerance below the range");
        assertEquals(0, new BigDecimal("0.03").compareTo((BigDecimal) r.calls.get("XC_RANGE-5").get("UNDERCHARGED_AMOUNT")), "measured to the NEAREST bound");
        assertEquals(0, new BigDecimal("1.50").compareTo((BigDecimal) r.calls.get("XC_RANGE-8").get("UNDERCHARGED_AMOUNT")));
        assertEquals("overbilled", r.calls.get("XC_RANGE-7").get("FINDING"));
        assertEquals(0, new BigDecimal("0.03").compareTo((BigDecimal) r.calls.get("XC_RANGE-7").get("OVERBILLED_AMOUNT")));
    }

    /** An explicit PRIORITY decides, a blank one counts as lowest, a tie decides nothing. */
    @Test
    void precedenceNeedsAnExplicitPriorityAndATieIsADisagreement(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        for (String id : corpus.archetypes.get("D_PRECEDENCE")) {
            Map<String, Object> row = r.calls.get(id);
            assertEquals("precedence", row.get("RESOLUTION"), id);
            assertEquals("D-S-2", row.get("TARIFF_VERSION_ID"), "priority 5 beats priority 1");
            assertEquals(0, new BigDecimal("4.00").compareTo((BigDecimal) row.get("EXPECTED_CHARGE")));
            assertEquals("D-S-1,D-S-2", row.get("CANDIDATE_VERSIONS"));
        }
        for (String id : corpus.archetypes.get("E_BLANK_PRIORITY")) {
            assertEquals("precedence", r.calls.get(id).get("RESOLUTION"), id);
            assertEquals("E-V-1", r.calls.get(id).get("TARIFF_VERSION_ID"), "an explicit priority beats a blank one");
        }
        for (String id : corpus.archetypes.get("E_TIE")) {
            assertEquals("disagree", r.calls.get(id).get("RESOLUTION"), id);
            assertNull(r.calls.get(id).get("TARIFF_VERSION_ID"));
        }
    }

    /** The call-start rule: the rate in force AT the start, FROM inclusive and TO exclusive, whatever the call runs past. */
    @Test
    void aCallIsRatedAtItsStartAndTheChangeInstantBelongsToTheNewVersion(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        assertEquals("A-V-2", r.calls.get("XA_AT_CHANGE_NEW-1").get("TARIFF_VERSION_ID"), "12:00:00 sharp is the new version");
        assertEquals("A-V-1", r.calls.get("XA_JUST_BEFORE-1").get("TARIFF_VERSION_ID"));
        assertEquals("A-V-1", r.calls.get("XA_SPANNING-1").get("TARIFF_VERSION_ID"), "a call that starts before the change is rated at the old rate");
        assertNull(r.calls.get("XA_SPANNING-1").get("FINDING"));
        assertEquals("single", r.calls.get("XA_AT_CHANGE_NEW-1").get("RESOLUTION"), "two versions that MEET are not two candidates");
        assertEquals("none", r.calls.get("XF_GAP-1").get("RESOLUTION"), "06:00:00 is the first second the old version no longer covers");
        assertEquals("single", r.calls.get("XF_EDGE-2").get("RESOLUTION"), "18:00:00 is the first second of the next version");
        assertEquals(0L, r.calls.get("XF_GAP-2").get("CANDIDATES"));
        assertNull(r.calls.get("XF_GAP-2").get("EXPECTED_LOW"));
    }

    /** Every tariff finding equals an independent plain-Java judgement of the tariff table; exactly the planted defects, the look-alikes silent. */
    @Test
    void theTariffFindingsAreExactlyThePlantedDefectsAndEqualTheOracle(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Map<String, Map<String, Object>> want = oracleTariff(corpus);
        assertEquals(want.keySet(), r.tariff.keySet(), "the tariff findings");
        for (String k : want.keySet()) assertSameRow(k, want.get(k), r.tariff.get(k));
        Set<String> got = new TreeSet<>();
        for (Map<String, Object> t : r.tariff.values()) {
            assertEquals("data_quality", t.get("FINDING"));
            got.add(t.get("REASON") + "|" + t.get("PLAN") + "|" + t.get("SERVICE") + "|" + t.get("TARIFF_VERSION_ID") + "|"
                    + (t.get("OTHER_TARIFF_VERSION_ID") == null ? "" : t.get("OTHER_TARIFF_VERSION_ID")));
        }
        assertEquals(corpus.plantedTariff, got);
        assertEquals(37, got.size(), "19 from R1 and 18 planted by R2");
        // look-alikes, named: versions that meet exactly (A, R's first two), a zero rate, an open row that outlives a closed one (no gap)
        for (String silent : List.of("PLAN_A|VOICE", "PLAN_H|DATA", "PLAN_J|VOICE", "PLAN_I|VOICE"))
            for (String f : got) assertFalse(f.contains("|" + silent + "|") && !f.startsWith("inverted_interval|PLAN_I"), silent + " must have no finding but " + f);
        assertTrue(got.stream().noneMatch(f -> f.startsWith("gap|PLAN_O") || f.startsWith("gap|PLAN_P") || f.startsWith("gap|PLAN_A")), "no false gap");
        Map<String, Object> gap = r.tariff.get("gap|PLAN_F|SMS|F-S-2|F-S-1");
        assertEquals("2026-07-01 06:00:00", gap.get("PERIOD_FROM"));
        assertEquals("2026-07-01 18:00:00", gap.get("PERIOD_TO"));
        assertEquals(Boolean.TRUE, r.tariff.get("overlap_different_rate|PLAN_D|SMS|D-S-1|D-S-2").get("PRIORITY_ORDERED"), "priority orders it: flagged, but resolvable");
        assertEquals(Boolean.FALSE, r.tariff.get("overlap_different_rate|PLAN_C|VOICE|C-V-1|C-V-1X").get("PRIORITY_ORDERED"));
        assertEquals(Boolean.FALSE, r.tariff.get("overlap_different_rate|PLAN_E|SMS|E-S-1|E-S-2").get("PRIORITY_ORDERED"), "a tie orders nothing");
        assertEquals(Boolean.TRUE, r.tariff.get("overlap_different_rate|PLAN_E|VOICE|E-V-1|E-V-2").get("PRIORITY_ORDERED"), "explicit against blank orders it");
        assertNull(r.tariff.get("overlap_same_rate|PLAN_B|DATA|B-D-1|B-D-1B").get("PRIORITY_ORDERED"), "only a rate conflict needs ordering");
        Map<String, Object> gapV = r.tariff.get("gap|PLAN_V|VOICE|V-V-3|V-V-2"), gapW = r.tariff.get("gap|PLAN_W|VOICE|W-V-3|W-V-1");
        assertEquals("2026-03-01 00:00:00", gapV.get("PERIOD_FROM"), "two rows end together: the gap names the greater id");
        assertEquals("2026-06-01 00:00:00", gapW.get("PERIOD_FROM"), "the gap starts where the row that ends last ends");
    }

    // ------------------------------------------------------------------------------------------- degradation, parameters

    /** A feed with NO PRIORITY column still runs: the rows that relied on it become disagreements, nothing else moves. */
    @Test
    void aTariffFeedWithoutThePriorityColumnStillRunsAndLosesOnlyPrecedence(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run base = run(root.resolve("base"), corpus.files(true), Map.of());
        Run bare = run(root.resolve("bare"), corpus.files(false), Map.of());
        assertEquals(base.calls.keySet(), bare.calls.keySet());
        Set<String> moved = new TreeSet<>();
        for (String id : base.calls.keySet()) {
            Map<String, Object> a = base.calls.get(id), b = bare.calls.get(id);
            if (!sameRow(a, b)) moved.add(id);
        }
        Set<String> relied = new TreeSet<>(corpus.archetypes.get("D_PRECEDENCE"));
        relied.addAll(corpus.archetypes.get("E_BLANK_PRIORITY"));
        relied.addAll(corpus.archetypes.get("S_DEARER_WINNER"));
        relied.addAll(corpus.archetypes.get("Y_PRECEDENCE"));
        relied.addAll(corpus.archetypes.get("BF_BOTH"));
        relied.addAll(corpus.archetypes.get("BC_DIFFERENT"));
        assertEquals(relied, moved, "only the calls that needed an explicit PRIORITY move");
        for (String id : relied) assertEquals("disagree", bare.calls.get(id).get("RESOLUTION"), id);
        assertEquals(base.tariff.keySet(), bare.tariff.keySet(), "the tariff findings are the same rows");
        for (String k : base.tariff.keySet()) {
            Map<String, Object> a = new HashMap<>(base.tariff.get(k)), b = new HashMap<>(bare.tariff.get(k));
            a.remove("PRIORITY_ORDERED");
            b.remove("PRIORITY_ORDERED");
            assertSameRow(k, a, b);
        }
    }

    /** The tolerance is the Job parameter: widening it silences the three-cent cases and nothing wider. */
    @Test
    void theToleranceParameterIsHonoured(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run wide = run(root, corpus.files(true), Map.of("tolerance", "0.05"));
        Map<String, Map<String, Object>> want = oracleCalls(corpus, new BigDecimal("0.05"));
        for (String id : want.keySet()) assertSameRow(id, want.get(id), wide.calls.get(id));
        for (String id : List.of("XJ_TOLERANCE-5", "XJ_TOLERANCE-6", "XC_RANGE-5", "XC_RANGE-7"))
            assertTrue(wide.calls.get(id).get("FINDING") == null || "data_quality".equals(wide.calls.get(id).get("FINDING")), id + " is inside 0.05");
        assertEquals("leakage", wide.calls.get("XJ_TOLERANCE-8").get("FINDING"), "a two-unit leak is not a rounding difference");
        Map<String, Map<String, Object>> wantExplain = oracleExplain(corpus, want, DAY1, new BigDecimal("0.05"), WITHIN, FLOOR);
        assertEquals(wantExplain.keySet(), wide.findings.keySet());
        for (String id : wantExplain.keySet()) assertSameRow(id, wantExplain.get(id), wide.findings.get(id));
    }

    // ------------------------------------------------------------------------------------------- time zones, windows, re-runs, chain

    /** The statements read naive timestamps only: identical rows under two host session zones. */
    @Test
    void theRowsAreIdenticalUnderTwoHostZones(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            registerViews(st, r.space);
            Map<String, String> p = new HashMap<>(r.jobs.get("rr_rerate").params());
            p.putAll(WINDOW1);
            String ru = substitute(r.jobs.get("rr_rerate").params().get("sql"), p);
            String hy = substitute(r.jobs.get("rr_tariff_hygiene").params().get("sql"), r.jobs.get("rr_tariff_hygiene").params());
            st.execute("SET TimeZone = 'Asia/Kolkata'");
            st.execute("CREATE TABLE ru_a AS " + ru);
            st.execute("CREATE TABLE hy_a AS " + hy);
            st.execute("SET TimeZone = 'America/Los_Angeles'");
            st.execute("CREATE TABLE ru_b AS " + ru);
            st.execute("CREATE TABLE hy_b AS " + hy);
            Map<String, Map<String, Object>> a = readRows(st, "ru_a", "ITEM_KEY"), b = readRows(st, "ru_b", "ITEM_KEY");
            assertEquals(r.calls.keySet(), a.keySet());
            for (String id : a.keySet()) { assertSameRow(id, a.get(id), b.get(id)); assertSameRow(id, r.calls.get(id), a.get(id)); }
            Map<String, String> ep = new HashMap<>(r.jobs.get("rr_explain").params());
            ep.putAll(WINDOW1);
            String ex = substitute(r.jobs.get("rr_explain").params().get("sql"), ep);
            String rc = substitute(r.jobs.get("rr_root_cause").params().get("sql"), Map.of());
            st.execute("SET TimeZone = 'Asia/Kolkata'");
            st.execute("CREATE TABLE ex_a AS " + ex);
            st.execute("CREATE TABLE rc_a AS " + rc);
            st.execute("SET TimeZone = 'America/Los_Angeles'");
            st.execute("CREATE TABLE ex_b AS " + ex);
            st.execute("CREATE TABLE rc_b AS " + rc);
            Map<String, Map<String, Object>> ea = readRows(st, "ex_a", "ITEM_KEY"), eb = readRows(st, "ex_b", "ITEM_KEY");
            assertEquals(r.findings.keySet(), ea.keySet());
            for (String id : ea.keySet()) { assertSameRow(id, ea.get(id), eb.get(id)); assertSameRow(id, r.findings.get(id), ea.get(id)); }
            Map<String, Map<String, Object>> ca = readRows(st, "rc_a", "ROOT_CAUSE_ID"), cb = readRows(st, "rc_b", "ROOT_CAUSE_ID");
            assertEquals(r.causes.keySet(), ca.keySet());
            for (String id : ca.keySet()) { assertSameRow(id, ca.get(id), cb.get(id)); assertSameRow(id, r.causes.get(id), ca.get(id)); }
            Map<String, Map<String, Object>> ha = readRows(st, "hy_a", "FINDING_KEY"), hb = readRows(st, "hy_b", "FINDING_KEY");
            assertEquals(r.tariff.keySet(), ha.keySet());
            for (String k : ha.keySet()) { assertSameRow(k, ha.get(k), hb.get(k)); assertSameRow(k, r.tariff.get(k), ha.get(k)); }
        }
    }

    /** A same-window re-run duplicates no row; the next window keeps the earlier one; the hygiene snapshot is replaced, never doubled. */
    @Test
    void aSameWindowRerunDuplicatesNothingAndTheNextWindowKeepsTheEarlierOne(@TempDir Path root) throws Exception {
        Run r = run(root, new RerateCorpus().generate().files(true), Map.of());
        Path data = r.space.resolve("data");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.copyOf(r.jobs.values()), new ConsignmentEventBus(), s, null,
                     root.resolve("audit2").toString(), null, null, data.toString())) {
            js.start();
            assertEquals("SUCCESS", runJob(js, "rr_rerate", WINDOW1).status());
            assertEquals(r.calls.size(), sinkRows(data, "rerated_usage", "window_date = DATE '2026-07-01'"), "a same-window re-run duplicates no row");
            assertEquals("SUCCESS", runJob(js, "rr_rerate", window(DAY1.plusDays(1))).status(), "the next window");
            assertEquals(r.calls.size(), sinkRows(data, "rerated_usage", "window_date = DATE '2026-07-01'"), "day 1 survives the next window's run");
            assertEquals(2, sinkRows(data, "rerated_usage", "window_date = DATE '2026-07-02'"), "the next window holds only the two calls the corpus keeps for it: one at midnight, one stale-version call");
            assertEquals("SUCCESS", runJob(js, "rr_explain", WINDOW1).status());
            assertEquals(r.findings.size(), sinkRows(data, "rerating_findings", "window_date = DATE '2026-07-01'"), "a same-window re-run of the explanation duplicates no row");
            assertEquals("SUCCESS", runJob(js, "rr_root_cause", Map.of()).status());
            assertEquals(r.causes.size(), sinkRows(data, "rerating_root_causes", "true"), "the root-cause snapshot is replaced, not doubled");
            assertTrue(Files.exists(data.resolve("rerated_usage").resolve("window_date=1970-01-01").resolve("seed.parquet")), "the seed is kept");
            assertEquals("SUCCESS", runJob(js, "rr_tariff_hygiene", Map.of()).status());
            assertEquals(r.tariff.size(), sinkRows(data, "rerating_tariff_findings", "true"), "the hygiene snapshot is replaced, not doubled");
        }
    }

    /** The two Jobs are one chain: the tariff table is judged first, and its completion (and only its) runs the re-rating. */
    @Test
    void theHygieneJobRunsTheReRatingOnSignal(@TempDir Path root) throws Exception {
        Run r = run(root, new RerateCorpus().generate().files(true), Map.of());
        Path data = r.space.resolve("data");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.copyOf(r.jobs.values()), new ConsignmentEventBus(), s, null,
                     root.resolve("audit3").toString(), null, null, data.toString())) {
            js.eventLog(com.gamma.event.EventLog.global());
            js.start();
            assertTrue(js.triggerRun("rr_tariff_hygiene", null).isPresent());
            JobRun run = await(() -> js.runsFor("rr_rerate").stream().filter(x -> !"SKIPPED".equals(x.status())).findFirst().orElse(null));
            assertEquals("SUCCESS", run.status(), run.message());
            for (String next : List.of("rr_explain", "rr_root_cause")) {
                JobRun link = await(() -> js.runsFor(next).stream().filter(x -> !"SKIPPED".equals(x.status())).findFirst().orElse(null));
                assertEquals("SUCCESS", link.status(), next + ": " + link.message());
            }
        }
    }

    // ------------------------------------------------------------------------------------------- R2: explanation, root causes, risk

    /** Hand-declared causes of every planted mismatch ({@code CAUSE|CAUSE_CONFIDENCE|CAUSE_ALTERNATIVE}); a data-quality finding carries its own reason. */
    private static final Map<String, String> PLANTED_CAUSE = new TreeMap<>();
    static {
        for (String id : List.of("XA_STALE-1", "XA_STALE-2", "XA_STALE-3", "XA_STALE-4", "XA_STALE-5", "XA_AT_CHANGE_STALE-1", "XSV_STALE-1"))
            PLANTED_CAUSE.put(id, "stale_version|1.0000|");
        PLANTED_CAUSE.put("XA_EARLY_NEW-1", "early_version|1.0000|");
        for (String id : List.of("XD_PRECEDENCE-2", "XS_DEARER_WINNER-2", "XY_PRECEDENCE-2", "XY_PRECEDENCE-3")) PLANTED_CAUSE.put(id, "lower_priority_rate|1.0000|");
        PLANTED_CAUSE.put("XE_BLANK_PRIORITY-2", "lower_priority_rate|0.5000|");        // one of the two over-billed calls on its plan fits: support 1/2, AT the floor
        PLANTED_CAUSE.put("XBC_DIFFERENT-1", "lower_priority_rate|1.0000|stale_version");   // exact to the losing row, within tolerance of the ended version
        PLANTED_CAUSE.put("XBF_BOTH-1", "stale_version|1.0000|lower_priority_rate");        // both exact: the fixed order breaks the tie, both are kept
        PLANTED_CAUSE.put("XBF_BOTH-2", "stale_version|1.0000|lower_priority_rate");
        PLANTED_CAUSE.put("XST_STALE-1", "stale_version|0.8333|");          // exact (1.0) times support 5/6
        for (String id : List.of("XST_STALE-2", "XST_STALE-3", "XST_STALE-4", "XST_STALE-5")) PLANTED_CAUSE.put(id, "stale_version|0.7500|");   // within tolerance (0.9) times 5/6
        for (String id : List.of("XST_STALE-6", "XB_AGREE_LEAK-1", "XC_RANGE-5", "XC_RANGE-7", "XC_RANGE-8", "XD_PRECEDENCE-3", "XE_TIE-2", "XE_BLANK_PRIORITY-4",
                "XJ_TOLERANCE-5", "XJ_TOLERANCE-6", "XJ_TOLERANCE-7", "XJ_TOLERANCE-8", "XI_INVERTED_OVER-1", "XST_EARLY_LEAK-1", "XNG_NEGATIVE_STALE-1",
                "XWK_WEAK-1", "XWK_WEAK-2", "XWK_WEAK-3", "XWK_WEAK-4", "XWK_WEAK-5"))
            PLANTED_CAUSE.put(id, "unexplained|0.0000|");
    }

    private static BigDecimal plantedSum(RerateCorpus corpus, String prefix) {
        BigDecimal sum = BigDecimal.ZERO;
        for (String v : corpus.planted.values()) if (v.startsWith(prefix)) sum = sum.add(new BigDecimal(v.substring(prefix.length())));
        return sum;
    }

    /** Every column of every finding equals an independent plain-Java explanation; one row per finding, nothing else. */
    @Test
    void everyFindingIsExplainedAsTheIndependentOracleExplainsIt(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Map<String, Map<String, Object>> want = oracleExplain(corpus, oracleCalls(corpus, TOLERANCE), DAY1, TOLERANCE, WITHIN, FLOOR);
        assertEquals(want.keySet(), r.findings.keySet(), "one row per finding");
        assertEquals(63, r.findings.size(), "33 leakage + 8 over-billed + 9 range + 11 no tariff + 2 unratable");
        for (String id : want.keySet()) assertSameRow(id, want.get(id), r.findings.get(id));
        assertEquals(23, r.findings.values().iterator().next().size());
        // the columns the starter leakage union reads are all there, and the amount it sums is the revenue at risk
        for (String col : List.of("CONTROL", "EVENT_DATE", "ITEM_KEY", "REASON", "FINDING", "LEAKAGE_AMOUNT")) assertTrue(r.findings.values().iterator().next().containsKey(col), col);
        for (Map<String, Object> row : r.findings.values()) assertSame(row.get("ITEM_KEY") + ".LEAKAGE_AMOUNT", row.get("LEAKAGE_LOW"), row.get("LEAKAGE_AMOUNT"));
    }

    /** Each planted mismatch gets its planted cause at the planted confidence; nothing unplanted is explained; a data-quality finding carries its reason. */
    @Test
    void everyPlantedMismatchGetsItsPlantedCauseAndAnUnplantedOneIsUnexplained(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        for (Map.Entry<String, String> p : corpus.planted.entrySet()) {
            Map<String, Object> row = r.findings.get(p.getKey());
            assertNotNull(row, p.getKey() + " is a finding");
            String finding = p.getValue().split("\\|")[1], reason = p.getValue().split("\\|")[0];
            String got = row.get("CAUSE") + "|" + ((BigDecimal) row.get("CAUSE_CONFIDENCE")).setScale(4, RoundingMode.UNNECESSARY).toPlainString() + "|"
                    + (row.get("CAUSE_ALTERNATIVE") == null ? "" : row.get("CAUSE_ALTERNATIVE"));
            if (finding.equals("data_quality")) assertEquals(reason + "|1.0000|", got, p.getKey() + ": a data-quality finding is its own cause");
            else {
                assertNotNull(PLANTED_CAUSE.get(p.getKey()), p.getKey() + " has no planted cause");
                assertEquals(PLANTED_CAUSE.get(p.getKey()), got, p.getKey());
            }
        }
        assertEquals(corpus.planted.keySet(), r.findings.keySet(), "a finding only where something was planted");
        for (String id : PLANTED_CAUSE.keySet()) {
            Map<String, Object> row = r.findings.get(id);
            if ("unexplained".equals(row.get("CAUSE"))) {
                assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("CAUSE_CONFIDENCE")), id);
                assertNull(row.get("CAUSE_ALTERNATIVE"), id);
                assertNull(row.get("CAUSE_TARIFF_VERSION_ID"), id);
            } else assertNotNull(row.get("CAUSE_TARIFF_VERSION_ID"), id + ": the version the cause points at");
        }
        assertEquals("A-V-1", r.findings.get("XA_STALE-1").get("CAUSE_TARIFF_VERSION_ID"));
        assertEquals("SV-V-2", r.findings.get("XSV_STALE-1").get("CAUSE_TARIFF_VERSION_ID"), "the latest version that ENDED, not the first");
        assertEquals("A-V-2", r.findings.get("XA_EARLY_NEW-1").get("CAUSE_TARIFF_VERSION_ID"));
        assertEquals("D-S-1", r.findings.get("XD_PRECEDENCE-2").get("CAUSE_TARIFF_VERSION_ID"));
        assertEquals("Y-S-2", r.findings.get("XY_PRECEDENCE-2").get("CAUSE_TARIFF_VERSION_ID"));
        assertEquals("Y-S-3", r.findings.get("XY_PRECEDENCE-3").get("CAUSE_TARIFF_VERSION_ID"), "the best-fitting losing row");
        assertEquals("BC-V-3", r.findings.get("XBC_DIFFERENT-1").get("CAUSE_TARIFF_VERSION_ID"), "the exact fit outranks the within-tolerance one");
        assertEquals("BF-V-1", r.findings.get("XBF_BOTH-1").get("CAUSE_TARIFF_VERSION_ID"));
        assertEquals("ST-V-1", r.findings.get("XST_STALE-3").get("CAUSE_TARIFF_VERSION_ID"));
    }

    /** Two hypotheses that both fit are both kept; a weak cohort is unexplained until the floor is lowered; the thresholds are parameters. */
    @Test
    void bothFittingCausesAreKeptAndTheConfidenceFloorIsAParameter(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run base = run(root.resolve("base"), corpus.files(true), Map.of());
        assertEquals("stale_version", base.findings.get("XBF_BOTH-1").get("CAUSE"));
        assertEquals("lower_priority_rate", base.findings.get("XBF_BOTH-1").get("CAUSE_ALTERNATIVE"), "the second fitting cause is kept, never dropped");
        assertEquals("lower_priority_rate", base.findings.get("XBC_DIFFERENT-1").get("CAUSE"), "ranked by confidence: the exact fit leads");
        for (int i = 1; i <= 5; i++) {
            Map<String, Object> row = base.findings.get("XWK_WEAK-" + i);
            assertEquals("unexplained", row.get("CAUSE"), "support 2 of 5 is below the floor of 0.5");
            assertNull(row.get("CAUSE_ALTERNATIVE"));
        }
        Run low = run(root.resolve("low"), corpus.files(true), Map.of("confidence_floor", "0.3"));
        Map<String, Map<String, Object>> wantLow = oracleExplain(corpus, oracleCalls(corpus, TOLERANCE), DAY1, TOLERANCE, WITHIN, new BigDecimal("0.3"));
        for (String id : wantLow.keySet()) assertSameRow(id, wantLow.get(id), low.findings.get(id));
        assertEquals("stale_version", low.findings.get("XWK_WEAK-1").get("CAUSE"));
        assertEquals(0, new BigDecimal("0.4000").compareTo((BigDecimal) low.findings.get("XWK_WEAK-1").get("CAUSE_CONFIDENCE")), "exact (1.0) times support 2/5");
        assertEquals(0, new BigDecimal("0.3600").compareTo((BigDecimal) low.findings.get("XWK_WEAK-5").get("CAUSE_CONFIDENCE")), "within tolerance (0.9) times support 2/5");
        for (int i = 2; i <= 4; i++) assertEquals("unexplained", low.findings.get("XWK_WEAK-" + i).get("CAUSE"));
        Run strict = run(root.resolve("strict"), corpus.files(true), Map.of("within_tolerance_confidence", "0.8"));
        Map<String, Map<String, Object>> wantStrict = oracleExplain(corpus, oracleCalls(corpus, TOLERANCE), DAY1, TOLERANCE, new BigDecimal("0.8"), FLOOR);
        for (String id : wantStrict.keySet()) assertSameRow(id, wantStrict.get(id), strict.findings.get(id));
        assertEquals(0, new BigDecimal("0.6667").compareTo((BigDecimal) strict.findings.get("XST_STALE-2").get("CAUSE_CONFIDENCE")), "0.8 times 5/6, to four places");
    }

    /** Revenue at risk and over-billing are separate classes: over-billing never enters the revenue-at-risk columns, and LEAKAGE_HIGH bounds LEAKAGE_LOW. */
    @Test
    void overBillingNeverEntersRevenueAtRiskAndTheRangeBoundsTheLeakage(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        BigDecimal risk = BigDecimal.ZERO, high = BigDecimal.ZERO, over = BigDecimal.ZERO;
        for (Map<String, Object> row : r.findings.values()) {
            BigDecimal lo = (BigDecimal) row.get("LEAKAGE_LOW"), hi = (BigDecimal) row.get("LEAKAGE_HIGH"), ov = (BigDecimal) row.get("OVERBILLED_AMOUNT");
            assertTrue(lo.signum() >= 0 && hi.signum() >= 0 && ov.signum() >= 0, row.get("ITEM_KEY").toString());
            assertTrue(hi.compareTo(lo) >= 0, row.get("ITEM_KEY") + ": the dearest candidate bounds the nearest one");
            if (row.get("FINDING").equals("overbilled")) { assertEquals(0, lo.signum(), row.get("ITEM_KEY") + ": an over-billed call is no revenue at risk"); assertEquals(0, hi.signum()); assertTrue(ov.signum() > 0); }
            else assertEquals(0, ov.signum(), String.valueOf(row.get("ITEM_KEY")));
            if (row.get("FINDING").equals("leakage")) assertTrue(lo.signum() > 0);
            risk = risk.add(lo);
            high = high.add(hi);
            over = over.add(ov);
        }
        assertEquals(0, plantedSum(corpus, "rate_mismatch|leakage|").compareTo(risk), "revenue at risk equals the planted leakage");
        assertEquals(0, plantedSum(corpus, "rate_mismatch|overbilled|").compareTo(over), "the over-billed amount is its own figure");
        assertTrue(high.compareTo(risk) > 0, "an ambiguous tariff adds exposure above the revenue at risk");
        // a range: C_RANGE-1 charges the LOW bound (2.00 of [2.00, 3.00]): nothing is at risk, up to 1.00 would be if the dearer row is the right one
        Map<String, Object> inside = r.findings.get("XC_RANGE-1");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) inside.get("LEAKAGE_LOW")));
        assertEquals(0, new BigDecimal("1.00").compareTo((BigDecimal) inside.get("LEAKAGE_HIGH")));
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) r.findings.get("XC_RANGE-9").get("LEAKAGE_HIGH")), "a shortfall AT the tolerance is not exposure");
        assertEquals(0, new BigDecimal("0.03").compareTo((BigDecimal) r.findings.get("XC_RANGE-5").get("LEAKAGE_LOW")));
        assertEquals(0, new BigDecimal("1.03").compareTo((BigDecimal) r.findings.get("XC_RANGE-5").get("LEAKAGE_HIGH")));
    }

    /** Root causes equal an independent grouping; three-plus stale-version calls are ONE root cause; the amounts are the planted sums. */
    @Test
    void theFindingsGroupIntoRootCausesAsTheOracleGroupsThem(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Map<String, Map<String, Object>> want = oracleRoot(new ArrayList<>(oracleExplain(corpus, oracleCalls(corpus, TOLERANCE), DAY1, TOLERANCE, WITHIN, FLOOR).values()));
        assertEquals(want.keySet(), r.causes.keySet());
        for (String id : want.keySet()) assertSameRow(id, want.get(id), r.causes.get(id));
        long total = 0;
        for (Map<String, Object> c : r.causes.values()) total += (Long) c.get("CALLS");
        assertEquals(r.findings.size(), total, "every finding is in exactly one root cause");
        Map<String, Object> st = r.causes.get("stale_version|leakage|PLAN_ST|VOICE|ST-V-2|ST-V-1");
        assertNotNull(st, "the stale-version calls on one plan are ONE root cause");
        assertEquals(5L, st.get("CALLS"));
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) st.get("LEAKAGE_LOW")));
        assertEquals(0, new BigDecimal("0.7500").compareTo((BigDecimal) st.get("CAUSE_CONFIDENCE")), "the weakest call of the group");
        assertEquals("XST_STALE-1,XST_STALE-2,XST_STALE-3", st.get("EXAMPLE_ITEM_KEYS"));
        assertEquals("2026-07-01", st.get("FIRST_DAY"));
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) st.get("RUN_RATE_PER_DAY")), "one day: the whole amount");
        Map<String, Object> a = r.causes.get("stale_version|leakage|PLAN_A|VOICE|A-V-2|A-V-1");
        assertEquals(6L, a.get("CALLS"));
        assertEquals(0, new BigDecimal("1.70").compareTo((BigDecimal) a.get("LEAKAGE_LOW")));
        Map<String, Object> bf = r.causes.get("stale_version|leakage|PLAN_BF|VOICE|BF-V-2,BF-V-3|BF-V-1");
        assertEquals(2L, bf.get("CALLS"));
        assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) bf.get("LEAKAGE_LOW")));
        BigDecimal risk = BigDecimal.ZERO, over = BigDecimal.ZERO;
        for (Map<String, Object> c : r.causes.values()) {
            risk = risk.add((BigDecimal) c.get("LEAKAGE_LOW"));
            over = over.add((BigDecimal) c.get("OVERBILLED_AMOUNT"));
            if (c.get("FINDING").equals("overbilled")) {
                assertEquals(0, ((BigDecimal) c.get("LEAKAGE_LOW")).signum(), c.get("ROOT_CAUSE_ID") + ": no revenue at risk");
                assertEquals(0, ((BigDecimal) c.get("RUN_RATE_PER_DAY")).signum());
            }
            assertFalse(c.get("ROOT_CAUSE_ID").toString().matches(".*\\d{4}-\\d{2}-\\d{2}.*"), "no date in a root cause id");
        }
        assertEquals(0, plantedSum(corpus, "rate_mismatch|leakage|").compareTo(risk), "revenue at risk of all root causes is the planted leakage");
        assertEquals(0, plantedSum(corpus, "rate_mismatch|overbilled|").compareTo(over));
        System.out.println("RERATE-R2 " + r.findings.size() + " findings, " + r.causes.size() + " root causes, explain " + r.explainMs + " ms, root cause " + r.rootMs + " ms");
    }

    /** A cause keeps its id across days and re-runs; a second day adds to it. */
    @Test
    void aRootCauseKeepsItsIdAcrossRunsAndDays(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Day2 d2 = dayTwo(r, root);
        assertTrue(d2.causes.keySet().containsAll(r.causes.keySet()), "every day-1 cause keeps its id");
        Map<String, Object> st = d2.causes.get("stale_version|leakage|PLAN_ST|VOICE|ST-V-2|ST-V-1");
        assertEquals(6L, st.get("CALLS"), "day 2 adds one call to the same cause");
        assertEquals("2026-07-01", st.get("FIRST_DAY"));
        assertEquals("2026-07-02", st.get("LAST_DAY"));
        assertEquals(0, new BigDecimal("6.00").compareTo((BigDecimal) st.get("LEAKAGE_LOW")));
        assertEquals(0, new BigDecimal("3.00").compareTo((BigDecimal) st.get("RUN_RATE_PER_DAY")), "6.00 over two calendar days");
        assertNotNull(d2.causes.get("unexplained|leakage|PLAN_A|SMS|A-S-1"), "a new cause appears under a new id");
        List<Map<String, Object>> both = new ArrayList<>(oracleExplain(corpus, oracleCalls(corpus, TOLERANCE), DAY1, TOLERANCE, WITHIN, FLOOR).values());
        both.addAll(oracleExplain(corpus, oracleCalls(corpus, TOLERANCE, DAY1.plusDays(1)), DAY1.plusDays(1), TOLERANCE, WITHIN, FLOOR).values());
        Map<String, Map<String, Object>> want = oracleRoot(both);
        assertEquals(want.keySet(), d2.causes.keySet());
        for (String id : want.keySet()) assertSameRow(id, want.get(id), d2.causes.get(id));
        // the same Job again over the same data: identical rows
        Path data = r.space.resolve("data");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.copyOf(r.jobs.values()), new ConsignmentEventBus(), s, null,
                     root.resolve("audit-again").toString(), null, null, data.toString())) {
            js.start();
            assertEquals("SUCCESS", runJob(js, "rr_root_cause", Map.of()).status());
        }
        Map<String, Map<String, Object>> again = readCauses(r.space);
        assertEquals(signature(d2.causes), signature(again), "a second run of the same Job gives the same ids and rows");
    }

    /** No verdict depends on the order of the tariff file. */
    @Test
    void theOrderOfTheTariffFileChangesNothing(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run a = run(root.resolve("a"), corpus.files(true), Map.of());
        Run b = run(root.resolve("b"), corpus.files(true, true), Map.of());
        assertEquals(signature(a.tariff), signature(b.tariff), "tariff findings, including the row a gap names when two rows end together");
        assertEquals(signature(a.calls), signature(b.calls));
        assertEquals(signature(a.findings), signature(b.findings));
        assertEquals(signature(a.causes), signature(b.causes));
    }

    /** What R1 left open, decided and pinned: a negative rate is never rated against, an incomplete call is data quality, agree outranks precedence. */
    @Test
    void aNegativeRateIsNeverRatedAgainstAnIncompleteCallCannotBeJudgedAndAgreeOutranksPrecedence(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        for (String id : corpus.archetypes.get("G_NEGATIVE")) {
            Map<String, Object> row = r.calls.get(id);
            assertEquals("none", row.get("RESOLUTION"), id);
            assertEquals(0L, row.get("CANDIDATES"));
            assertEquals("no_tariff", row.get("REASON"));
            assertEquals("data_quality", row.get("FINDING"), "a call on a negative-rate row has no usable tariff; the hygiene Job reports the row");
            assertNull(row.get("EXPECTED_LOW"));
        }
        assertEquals("single", r.calls.get("XH_ZERO-1").get("RESOLUTION"), "a ZERO rate is a free plan and is still rated");
        assertNull(r.calls.get("XH_ZERO-1").get("FINDING"));
        Map<String, Object> noCharge = r.calls.get("XJ_NULL_CHARGE-1"), noUnits = r.calls.get("XJ_NULL_UNITS-1");
        for (Map<String, Object> row : List.of(noCharge, noUnits)) {
            assertEquals("single", row.get("RESOLUTION"));
            assertEquals("unratable_call", row.get("REASON"));
            assertEquals("data_quality", row.get("FINDING"), "an unratable call is never silent");
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("UNDERCHARGED_AMOUNT")));
            assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) row.get("OVERBILLED_AMOUNT")));
        }
        assertNull(noCharge.get("ACTUAL_CHARGE"));
        assertNull(noUnits.get("USAGE_UNITS"));
        assertNull(noUnits.get("EXPECTED_LOW"));
        Map<String, Object> zero = r.calls.get("XD_ZERO_UNITS-1");
        assertEquals("agree", zero.get("RESOLUTION"), "equal amounts agree, whatever the priorities");
        assertEquals("D-S-1", zero.get("TARIFF_VERSION_ID"), "agree names the first version in id order, not the highest priority");
        assertEquals("precedence", r.calls.get("XD_PRECEDENCE-1").get("RESOLUTION"), "with units the same plan resolves by priority");
        assertNull(zero.get("FINDING"));
        Map<String, Object> inverted = r.findings.get("XI_INVERTED_OVER-1");
        assertEquals("unexplained", inverted.get("CAUSE"), "an inverted row explains nothing");
    }

    /** PRIORITY_ORDERED is a fact about one PAIR; the verdict for a call is its RESOLUTION. They differ on a tied top and on a tied pair below a lone winner. */
    @Test
    void priorityOrderedIsAboutThePairAndTheCallVerdictIsAuthoritative(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        assertEquals(Boolean.FALSE, r.tariff.get("overlap_different_rate|PLAN_X|SMS|X-S-1|X-S-2").get("PRIORITY_ORDERED"));
        assertEquals(Boolean.TRUE, r.tariff.get("overlap_different_rate|PLAN_X|SMS|X-S-1|X-S-3").get("PRIORITY_ORDERED"));
        assertEquals(Boolean.TRUE, r.tariff.get("overlap_different_rate|PLAN_X|SMS|X-S-2|X-S-3").get("PRIORITY_ORDERED"));
        Map<String, Object> x = r.calls.get("XX_TIE_BLANK-1");
        assertEquals("disagree", x.get("RESOLUTION"), "two rows tied at the top: no winner, although both pairs with the blank row are ordered");
        assertEquals(0, new BigDecimal("3.00").compareTo((BigDecimal) x.get("EXPECTED_LOW")));
        assertEquals(0, new BigDecimal("5.00").compareTo((BigDecimal) x.get("EXPECTED_HIGH")));
        assertEquals(Boolean.TRUE, r.tariff.get("overlap_different_rate|PLAN_Y|SMS|Y-S-1|Y-S-2").get("PRIORITY_ORDERED"));
        assertEquals(Boolean.TRUE, r.tariff.get("overlap_different_rate|PLAN_Y|SMS|Y-S-1|Y-S-3").get("PRIORITY_ORDERED"));
        assertEquals(Boolean.FALSE, r.tariff.get("overlap_different_rate|PLAN_Y|SMS|Y-S-2|Y-S-3").get("PRIORITY_ORDERED"));
        for (String id : corpus.archetypes.get("Y_PRECEDENCE")) {
            assertEquals("precedence", r.calls.get(id).get("RESOLUTION"), "a lone winner above a tied pair resolves, although that pair is unordered");
            assertEquals("Y-S-1", r.calls.get(id).get("TARIFF_VERSION_ID"));
        }
    }

    // ------------------------------------------------------------------------------------------- the mutation pass

    /**
     * Every clause of both statements gets a mutant that must change the rows the oracle pins. The unmutated statement is
     * first run through the SAME harness and must equal the Job's own output, so a mutant that survives is a clause nothing
     * pins - and a probe that could not have succeeded cannot pass.
     */
    @Test
    void everyClauseMutantChangesTheRowsTheOraclePins(@TempDir Path root) throws Exception {
        RerateCorpus corpus = new RerateCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        Day2 d2 = dayTwo(r, root);   // the sinks also hold day 2, so a window or a day-range mutant has something to leak
        String rerate = r.jobs.get("rr_rerate").params().get("sql"), hygiene = r.jobs.get("rr_tariff_hygiene").params().get("sql");
        String explainSql = r.jobs.get("rr_explain").params().get("sql"), rootSql = r.jobs.get("rr_root_cause").params().get("sql");
        Map<String, String> ep = new HashMap<>(r.jobs.get("rr_explain").params());
        ep.putAll(WINDOW1);
        Map<String, String> p = new HashMap<>(r.jobs.get("rr_rerate").params());
        p.putAll(WINDOW1);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            registerViews(st, r.space);
            String baseCalls = signature(readRows(st, "(" + substitute(rerate, p) + ")", "ITEM_KEY"));
            String baseTariff = signature(readRows(st, "(" + substitute(hygiene, Map.of()) + ")", "FINDING_KEY"));
            assertEquals(signature(r.calls), baseCalls, "the harness reproduces the Job's own rows");
            assertEquals(signature(r.tariff), baseTariff, "the harness reproduces the hygiene Job's own rows");
            String baseExplain = signature(readRows(st, "(" + substitute(explainSql, ep) + ")", "ITEM_KEY"));
            String baseRoot = signature(readRows(st, "(" + substitute(rootSql, Map.of()) + ")", "ROOT_CAUSE_ID"));
            assertEquals(signature(r.findings), baseExplain, "the harness reproduces the explanation Job's own rows");
            assertEquals(signature(d2.causes), baseRoot, "the harness reproduces the root-cause Job's own rows (both days)");

            List<String[]> mutants = new ArrayList<>(List.of(
                    new String[]{"segment from inclusive", "t.EFFECTIVE_FROM <= s.SEG_FROM", "t.EFFECTIVE_FROM < s.SEG_FROM"},
                    new String[]{"segment to exclusive", "s.SEG_FROM < t.EFFECTIVE_TO", "s.SEG_FROM <= t.EFFECTIVE_TO"},
                    new String[]{"open to", "(t.EFFECTIVE_TO IS NULL OR s.SEG_FROM < t.EFFECTIVE_TO)", "(s.SEG_FROM < t.EFFECTIVE_TO)"},
                    new String[]{"segment service join", "t.PLAN = s.PLAN AND t.SERVICE = s.SERVICE", "t.PLAN = s.PLAN"},
                    new String[]{"segment plan join", "t.PLAN = s.PLAN AND t.SERVICE = s.SERVICE", "t.SERVICE = s.SERVICE"},
                    new String[]{"boundaries from the end", "UNION SELECT PLAN, SERVICE, EFFECTIVE_TO AS T FROM tv WHERE EFFECTIVE_TO IS NOT NULL", "UNION SELECT PLAN, SERVICE, EFFECTIVE_FROM AS T FROM tv"},
                    new String[]{"segment end is the next boundary", "lead(T) OVER", "lag(T) OVER"},
                    new String[]{"segments per service", "PARTITION BY PLAN, SERVICE ORDER BY T", "PARTITION BY PLAN ORDER BY T"},
                    new String[]{"call service join", "c.PLAN = u.PLAN AND c.SERVICE = u.SERVICE", "c.PLAN = u.PLAN"},
                    new String[]{"call plan join", "c.PLAN = u.PLAN AND c.SERVICE = u.SERVICE", "c.SERVICE = u.SERVICE"},
                    new String[]{"call at the segment start", "c.SEG_FROM <= u.EVENT_TS", "c.SEG_FROM < u.EVENT_TS"},
                    new String[]{"call at the segment end", "u.EVENT_TS < c.SEG_TO", "u.EVENT_TS <= c.SEG_TO"},
                    new String[]{"open last segment", "(c.SEG_TO IS NULL OR u.EVENT_TS < c.SEG_TO)", "(u.EVENT_TS < c.SEG_TO)"},
                    new String[]{"window start", "EVENT_TS >= CAST($window_start AS TIMESTAMP)", "EVENT_TS > CAST($window_start AS TIMESTAMP)"},
                    new String[]{"window end", "EVENT_TS < CAST($window_end AS TIMESTAMP)", "EVENT_TS <= CAST($window_end AS TIMESTAMP)"},
                    new String[]{"negative rate excluded", "WHERE RATE_PER_UNIT >= 0", "WHERE TRUE"},
                    new String[]{"zero rate is rated", "RATE_PER_UNIT >= 0", "RATE_PER_UNIT > 0"},
                    new String[]{"unratable needs the charge", "WHEN CHARGE IS NULL OR USAGE_UNITS IS NULL THEN 'unratable_call'", "WHEN USAGE_UNITS IS NULL THEN 'unratable_call'"},
                    new String[]{"unratable needs the units", "WHEN CHARGE IS NULL OR USAGE_UNITS IS NULL THEN 'unratable_call'", "WHEN CHARGE IS NULL THEN 'unratable_call'"},
                    new String[]{"unratable is data quality", "WHEN CHARGE IS NULL OR USAGE_UNITS IS NULL THEN 'data_quality'", "WHEN CHARGE IS NULL OR USAGE_UNITS IS NULL THEN 'leakage'"},
                    new String[]{"agree outranks precedence", "WHEN CAND_LOW = CAND_HIGH THEN 'agree' WHEN (len(TOP2) = 1 OR TOP2[1] > TOP2[2]) THEN 'precedence'", "WHEN (len(TOP2) = 1 OR TOP2[1] > TOP2[2]) THEN 'precedence' WHEN CAND_LOW = CAND_HIGH THEN 'agree'"},
                    new String[]{"rounding of the range", "ROUND(u.USAGE_UNITS * c.MIN_RATE, 2)", "ROUND(u.USAGE_UNITS * c.MIN_RATE, 1)"},
                    new String[]{"rounding of the winner", "ROUND(u.USAGE_UNITS * c.TOP_RATE, 2) AS TOP_EXPECTED", "ROUND(u.USAGE_UNITS * c.TOP_RATE, 1) AS TOP_EXPECTED"},
                    new String[]{"low is the least", "least(ROUND(u.USAGE_UNITS * c.MIN_RATE, 2), ROUND(u.USAGE_UNITS * c.MAX_RATE, 2)) AS CAND_LOW", "greatest(ROUND(u.USAGE_UNITS * c.MIN_RATE, 2), ROUND(u.USAGE_UNITS * c.MAX_RATE, 2)) AS CAND_LOW"},
                    new String[]{"low needs both ends (a reversal flips them)", "least(ROUND(u.USAGE_UNITS * c.MIN_RATE, 2), ROUND(u.USAGE_UNITS * c.MAX_RATE, 2)) AS CAND_LOW", "ROUND(u.USAGE_UNITS * c.MIN_RATE, 2) AS CAND_LOW"},
                    new String[]{"high is the greatest", "greatest(ROUND(u.USAGE_UNITS * c.MIN_RATE, 2), ROUND(u.USAGE_UNITS * c.MAX_RATE, 2)) AS CAND_HIGH", "least(ROUND(u.USAGE_UNITS * c.MIN_RATE, 2), ROUND(u.USAGE_UNITS * c.MAX_RATE, 2)) AS CAND_HIGH"},
                    new String[]{"none", "WHEN CANDIDATES = 0 THEN 'none' WHEN CANDIDATES = 1", "WHEN CANDIDATES = 99 THEN 'none' WHEN CANDIDATES = 1"},
                    new String[]{"single", "WHEN CANDIDATES = 1 THEN 'single'", "WHEN CANDIDATES = 99 THEN 'single'"},
                    new String[]{"agree", "WHEN CAND_LOW = CAND_HIGH THEN 'agree'", "WHEN CAND_LOW < CAND_LOW THEN 'agree'"},
                    new String[]{"tie is no winner", "TOP2[1] > TOP2[2]", "TOP2[1] >= TOP2[2]"},
                    new String[]{"lone explicit priority", "(len(TOP2) = 1 OR", "(FALSE OR"},
                    new String[]{"top two", "max(t.PRIORITY, 2)", "max(t.PRIORITY, 1)"},
                    new String[]{"winner rate", "arg_max(t.RATE_PER_UNIT, t.PRIORITY)", "arg_min(t.RATE_PER_UNIT, t.PRIORITY)"},
                    new String[]{"winner version", "arg_max(t.TARIFF_VERSION_ID, t.PRIORITY)", "arg_min(t.TARIFF_VERSION_ID, t.PRIORITY)"},
                    new String[]{"disagree has no version", "WHEN 'disagree' THEN NULL ELSE FIRST_VERSION", "WHEN 'disagree' THEN FIRST_VERSION ELSE FIRST_VERSION"},
                    new String[]{"precedence version", "CASE RESOLUTION WHEN 'precedence' THEN TOP_VERSION", "CASE RESOLUTION WHEN 'precedence' THEN FIRST_VERSION"},
                    new String[]{"first version", "min(t.TARIFF_VERSION_ID) AS FIRST_VERSION", "max(t.TARIFF_VERSION_ID) AS FIRST_VERSION"},
                    new String[]{"candidate order", "ORDER BY t.TARIFF_VERSION_ID) AS CANDIDATE_VERSIONS", "ORDER BY t.TARIFF_VERSION_ID DESC) AS CANDIDATE_VERSIONS"},
                    new String[]{"precedence low", "WHEN 'precedence' THEN TOP_EXPECTED ELSE CAND_LOW END AS EXPECTED_LOW", "WHEN 'precedence' THEN CAND_LOW ELSE CAND_LOW END AS EXPECTED_LOW"},
                    new String[]{"precedence high", "WHEN 'precedence' THEN TOP_EXPECTED ELSE CAND_HIGH END AS EXPECTED_HIGH", "WHEN 'precedence' THEN CAND_HIGH ELSE CAND_HIGH END AS EXPECTED_HIGH"},
                    new String[]{"under strict", "EXPECTED_LOW - CHARGE > CAST($tolerance AS DECIMAL(18,4))", "EXPECTED_LOW - CHARGE >= CAST($tolerance AS DECIMAL(18,4))"},
                    new String[]{"over strict", "CHARGE - EXPECTED_HIGH > CAST($tolerance AS DECIMAL(18,4))", "CHARGE - EXPECTED_HIGH >= CAST($tolerance AS DECIMAL(18,4))"},
                    new String[]{"under bound", "THEN EXPECTED_LOW - CHARGE ELSE 0", "THEN EXPECTED_HIGH - CHARGE ELSE 0"},
                    new String[]{"over bound", "THEN CHARGE - EXPECTED_HIGH ELSE 0", "THEN CHARGE - EXPECTED_LOW ELSE 0"},
                    new String[]{"no netting under", "THEN UNDER END AS UNDERCHARGED_AMOUNT", "THEN UNDER - OVER END AS UNDERCHARGED_AMOUNT"},
                    new String[]{"no netting over", "THEN OVER END AS OVERBILLED_AMOUNT", "THEN OVER - UNDER END AS OVERBILLED_AMOUNT"},
                    new String[]{"reason order", "WHEN UNDER > 0 OR OVER > 0 THEN 'rate_mismatch' WHEN RESOLUTION = 'disagree' THEN 'ambiguous_tariff'", "WHEN RESOLUTION = 'disagree' THEN 'ambiguous_tariff' WHEN UNDER > 0 OR OVER > 0 THEN 'rate_mismatch'"},
                    new String[]{"overbilled is not leakage", "WHEN UNDER > 0 THEN 'leakage' WHEN OVER > 0 THEN 'overbilled'", "WHEN UNDER > 0 OR OVER > 0 THEN 'leakage'"},
                    new String[]{"no tariff is data quality", "CASE WHEN CANDIDATES = 0 THEN 'data_quality' WHEN CHARGE", "CASE WHEN CANDIDATES = 0 THEN 'leakage' WHEN CHARGE"},
                    new String[]{"range is data quality", "WHEN RESOLUTION = 'disagree' THEN 'data_quality' END AS FINDING", "WHEN RESOLUTION = 'disagree' THEN 'leakage' END AS FINDING"},
                    new String[]{"window date", "CAST($window_start AS DATE) AS window_date", "CAST($window_end AS DATE) AS window_date"}));
            Map<String, String> pm = p;
            // the R2 mutants must be red FOR THE NAMED REASON: the row (or a row whose key contains the text) the planted case lives on must change
            Map<String, String> why = new LinkedHashMap<>();
            why.put("negative rate excluded", "XG_NEGATIVE-1");
            why.put("zero rate is rated", "XH_ZERO-1");
            why.put("unratable needs the charge", "XJ_NULL_CHARGE-1");
            why.put("unratable needs the units", "XJ_NULL_UNITS-1");
            why.put("unratable is data quality", "XJ_NULL_CHARGE-1");
            why.put("agree outranks precedence", "XD_ZERO_UNITS-1");
            why.put("gap previous id is the row that ends last", "gap|PLAN_W");
            why.put("gap previous id: ties go to the greater id", "gap|PLAN_V");
            why.put("pair order is by start", "PLAN_U|VOICE");
            why.put("pair order ties go to the smaller id", "PLAN_UT|VOICE");
            why.put("usable pair, the earlier row", "PLAN_KB|VOICE");
            why.put("no negative rate explains", "XNG_NEGATIVE_STALE-1");
            why.put("no inverted row explains", "XI_INVERTED_OVER-1");
            why.put("stale: ended at the call start counts", "XA_AT_CHANGE_STALE-1");
            why.put("stale: the latest ended version", "XSV_STALE-1");
            why.put("lower priority: only a precedence call", "XD_PRECEDENCE-2");
            why.put("lower priority: an open row is in force", "XY_PRECEDENCE-2");
            why.put("lower priority: the best fit", "XY_PRECEDENCE-3");
            why.put("early: starts after the call", "XA_EARLY_NEW-1");
            why.put("fit at the tolerance", "XST_STALE-4");
            why.put("within tolerance is its own confidence", "XST_STALE-2");
            why.put("exact is full confidence", "XA_STALE-1");
            why.put("support counts the same cause", "XBF_BOTH-1");
            why.put("the floor keeps a confidence AT it", "XE_BLANK_PRIORITY-2");
            why.put("the most confident leads", "XBC_DIFFERENT-1");
            why.put("a tie goes to the fixed order", "XBF_BOTH-1");
            why.put("alternatives exclude the leader", "XBF_BOTH-1");
            why.put("cohort is per finding", "XD_PRECEDENCE-2");
            why.put("cohort is per candidate set", "XST_STALE-1");
            why.put("leakage high is strict", "XC_RANGE-9");
            why.put("run rate counts calendar days inclusively", "stale_version|leakage|PLAN_ST");
            why.put("first day", "stale_version|leakage|PLAN_ST");
            Set<String> whyChecked = new TreeSet<>();
            for (String[] m : mutants) {
                assertTrue(count(rerate, m[1]) >= 1, m[0] + ": the clause text is not in the statement: " + m[1]);
                String mutated = rerate.replace(m[1], m[2]);
                assertNotEquals(rerate, mutated, m[0]);
                assertNotEquals(baseCalls, mutantSignature(st, substitute(mutated, pm), "ITEM_KEY"), "rr_rerate mutant survived: " + m[0]);
                if (assertWhy(why, m[0], st, substitute(mutated, pm), "ITEM_KEY", r.calls)) whyChecked.add(m[0]);
            }
            List<String[]> hyMutants = List.of(
                    new String[]{"missing start label", "CASE WHEN EFFECTIVE_FROM IS NULL THEN 'missing_start'", "CASE WHEN FALSE THEN 'missing_start'"},
                    new String[]{"missing start is a defect", "WHERE EFFECTIVE_FROM IS NULL OR NOT VALID_INTERVAL", "WHERE NOT VALID_INTERVAL"},
                    new String[]{"pair order", "a.RN < b.RN", "a.RN <> b.RN"},
                    new String[]{"overlap meets", "b.EFFECTIVE_FROM < coalesce(a.EFFECTIVE_TO,", "b.EFFECTIVE_FROM <= coalesce(a.EFFECTIVE_TO,"},
                    new String[]{"same plan", "a.PLAN = b.PLAN AND a.SERVICE = b.SERVICE", "a.SERVICE = b.SERVICE"},
                    new String[]{"same service", "a.PLAN = b.PLAN AND a.SERVICE = b.SERVICE", "a.PLAN = b.PLAN"},
                    new String[]{"usable pair", "AND a.USABLE AND b.USABLE", "AND a.USABLE"},
                    new String[]{"usable interval", "RATE_PER_UNIT IS NOT NULL AND EFFECTIVE_FROM IS NOT NULL AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO > EFFECTIVE_FROM)) AS USABLE", "RATE_PER_UNIT IS NOT NULL AND EFFECTIVE_FROM IS NOT NULL) AS USABLE"},
                    new String[]{"valid interval strict", "(EFFECTIVE_TO IS NULL OR EFFECTIVE_TO > EFFECTIVE_FROM) AS VALID_INTERVAL", "(EFFECTIVE_TO IS NULL OR EFFECTIVE_TO >= EFFECTIVE_FROM) AS VALID_INTERVAL"},
                    new String[]{"same rate label", "WHEN a.RATE_PER_UNIT = b.RATE_PER_UNIT THEN 'overlap_same_rate'", "WHEN a.RATE_PER_UNIT <> b.RATE_PER_UNIT THEN 'overlap_same_rate'"},
                    new String[]{"overlap start", "greatest(a.EFFECTIVE_FROM, b.EFFECTIVE_FROM)", "least(a.EFFECTIVE_FROM, b.EFFECTIVE_FROM)"},
                    new String[]{"overlap end", "ELSE least(a.EFFECTIVE_TO, b.EFFECTIVE_TO)", "ELSE greatest(a.EFFECTIVE_TO, b.EFFECTIVE_TO)"},
                    new String[]{"overlap open a", "WHEN a.EFFECTIVE_TO IS NULL THEN b.EFFECTIVE_TO", "WHEN a.EFFECTIVE_TO IS NULL THEN a.EFFECTIVE_TO"},
                    new String[]{"overlap open b", "WHEN b.EFFECTIVE_TO IS NULL THEN a.EFFECTIVE_TO", "WHEN b.EFFECTIVE_TO IS NULL THEN b.EFFECTIVE_TO"},
                    new String[]{"priority ordered", "a.PRIORITY IS DISTINCT FROM b.PRIORITY", "a.PRIORITY <> b.PRIORITY"},
                    new String[]{"priority only for a conflict", "CASE WHEN a.RATE_PER_UNIT <> b.RATE_PER_UNIT THEN a.PRIORITY IS DISTINCT FROM b.PRIORITY END", "a.PRIORITY IS DISTINCT FROM b.PRIORITY"},
                    new String[]{"negative", "OR RATE_PER_UNIT < 0", "OR RATE_PER_UNIT <= 0"},
                    new String[]{"missing", "WHEN RATE_PER_UNIT IS NULL THEN 'missing_rate'", "WHEN RATE_PER_UNIT IS NULL THEN 'negative_rate'"},
                    new String[]{"inverted label", "WHEN NOT VALID_INTERVAL THEN 'inverted_interval'", "WHEN NOT VALID_INTERVAL THEN 'missing_rate'"},
                    new String[]{"gap needs closed history", "WHERE NOT PREV_OPEN", "WHERE TRUE"},
                    new String[]{"gap strict", "AND EFFECTIVE_FROM > PREV_END", "AND EFFECTIVE_FROM >= PREV_END"},
                    new String[]{"gap usable only", "FROM t WHERE USABLE WINDOW", "FROM t WINDOW"},
                    new String[]{"gap window order", "ORDER BY EFFECTIVE_FROM, TARIFF_VERSION_ID ROWS", "ORDER BY EFFECTIVE_FROM DESC, TARIFF_VERSION_ID ROWS"},
                    new String[]{"gap previous id", "arg_max(TARIFF_VERSION_ID, (EFFECTIVE_TO, TARIFF_VERSION_ID)) OVER w", "arg_min(TARIFF_VERSION_ID, (EFFECTIVE_TO, TARIFF_VERSION_ID)) OVER w"},
                    new String[]{"gap previous id is the row that ends last", "arg_max(TARIFF_VERSION_ID, (EFFECTIVE_TO, TARIFF_VERSION_ID)) OVER w", "arg_max(TARIFF_VERSION_ID, EFFECTIVE_FROM) OVER w"},
                    new String[]{"gap previous id: ties go to the greater id", "(EFFECTIVE_TO, TARIFF_VERSION_ID)) OVER w", "(EFFECTIVE_TO, -ascii(right(TARIFF_VERSION_ID, 1)))) OVER w"},
                    new String[]{"pair order is by start", "ORDER BY PLAN, SERVICE, EFFECTIVE_FROM, TARIFF_VERSION_ID) AS RN", "ORDER BY PLAN, SERVICE, TARIFF_VERSION_ID) AS RN"},
                    new String[]{"pair order ties go to the smaller id", "ORDER BY PLAN, SERVICE, EFFECTIVE_FROM, TARIFF_VERSION_ID) AS RN", "ORDER BY PLAN, SERVICE, EFFECTIVE_FROM, TARIFF_VERSION_ID DESC) AS RN"},
                    new String[]{"usable pair, the later row", "AND a.USABLE AND b.USABLE", "AND a.USABLE"},
                    new String[]{"usable pair, the earlier row", "AND a.USABLE AND b.USABLE", "AND b.USABLE"},
                    new String[]{"gap period", "PREV_END AS PERIOD_FROM, EFFECTIVE_FROM AS PERIOD_TO", "EFFECTIVE_FROM AS PERIOD_FROM, PREV_END AS PERIOD_TO"},
                    new String[]{"gap end", "max(EFFECTIVE_TO) OVER w AS PREV_END", "min(EFFECTIVE_TO) OVER w AS PREV_END"},
                    new String[]{"finding key", "concat_ws('|', REASON, PLAN, SERVICE, TARIFF_VERSION_ID, OTHER_TARIFF_VERSION_ID)", "concat_ws('|', REASON, PLAN, SERVICE, TARIFF_VERSION_ID)"});
            for (String[] m : hyMutants) {
                assertTrue(count(hygiene, m[1]) >= 1, m[0] + ": the clause text is not in the statement: " + m[1]);
                String mutated = hygiene.replace(m[1], m[2]);
                assertNotEquals(hygiene, mutated, m[0]);
                assertNotEquals(baseTariff, mutantSignature(st, substitute(mutated, Map.of()), "FINDING_KEY"), "rr_tariff_hygiene mutant survived: " + m[0]);
                if (assertWhy(why, m[0], st, substitute(mutated, Map.of()), "FINDING_KEY", r.tariff)) whyChecked.add(m[0]);
            }
            List<String[]> exMutants = List.of(
                    new String[]{"window", "CAST(window_date AS DATE) = CAST($window_start AS DATE)", "CAST(window_date AS DATE) <> CAST($window_start AS DATE)"},
                    new String[]{"findings only", "WHERE FINDING IS NOT NULL AND CAST(window_date", "WHERE CAST(window_date"},
                    new String[]{"mismatch class", "WHERE FINDING IN ('leakage', 'overbilled')", "WHERE FINDING IN ('leakage')"},
                    new String[]{"cohort is per finding", "PARTITION BY PLAN, SERVICE, CANDIDATE_VERSIONS, FINDING) AS COHORT_N", "PARTITION BY PLAN, SERVICE, CANDIDATE_VERSIONS) AS COHORT_N"},
                    new String[]{"cohort is per candidate set", "PARTITION BY PLAN, SERVICE, CANDIDATE_VERSIONS, FINDING) AS COHORT_N", "PARTITION BY PLAN, SERVICE, FINDING) AS COHORT_N"},
                    new String[]{"no negative rate explains", "WHERE RATE_PER_UNIT >= 0 AND (EFFECTIVE_TO", "WHERE (EFFECTIVE_TO"},
                    new String[]{"no inverted row explains", "RATE_PER_UNIT >= 0 AND (EFFECTIVE_TO IS NULL OR EFFECTIVE_TO > EFFECTIVE_FROM) ),", "RATE_PER_UNIT >= 0 ),"},
                    new String[]{"stale: ended at the call start counts", "t.EFFECTIVE_TO <= m.EVENT_TS QUALIFY", "t.EFFECTIVE_TO < m.EVENT_TS QUALIFY"},
                    new String[]{"stale: the latest ended version", "ORDER BY t.EFFECTIVE_TO DESC, t.TARIFF_VERSION_ID) = 1", "ORDER BY t.EFFECTIVE_TO, t.TARIFF_VERSION_ID) = 1"},
                    new String[]{"lower priority: only a precedence call", "WHERE m.RESOLUTION = 'precedence'", "WHERE m.RESOLUTION = 'single'"},
                    new String[]{"lower priority: an open row is in force", "AND (t.EFFECTIVE_TO IS NULL OR m.EVENT_TS < t.EFFECTIVE_TO) AND t.TARIFF_VERSION_ID <>", "AND (m.EVENT_TS < t.EFFECTIVE_TO) AND t.TARIFF_VERSION_ID <>"},
                    new String[]{"lower priority: the best fit", "ORDER BY abs(ROUND(m.USAGE_UNITS * t.RATE_PER_UNIT, 2) - m.ACTUAL_CHARGE), t.TARIFF_VERSION_ID) = 1", "ORDER BY t.TARIFF_VERSION_ID) = 1"},
                    new String[]{"early: starts after the call", "t.EFFECTIVE_FROM > m.EVENT_TS QUALIFY", "t.EFFECTIVE_FROM < m.EVENT_TS QUALIFY"},
                    new String[]{"fit at the tolerance", "WHERE abs(cf.CF_AMOUNT - m.ACTUAL_CHARGE) <= CAST($tolerance AS DECIMAL(18,4))", "WHERE abs(cf.CF_AMOUNT - m.ACTUAL_CHARGE) < CAST($tolerance AS DECIMAL(18,4))"},
                    new String[]{"within tolerance is its own confidence", "ELSE CAST($within_tolerance_confidence AS DOUBLE) END", "ELSE 1.0 END"},
                    new String[]{"exact is full confidence", "CASE WHEN DIFF = 0 THEN 1.0", "CASE WHEN DIFF = 0 THEN 0.5"},
                    new String[]{"support counts the same cause", "FINDING, CAUSE) / COHORT_N", "FINDING) / COHORT_N"},
                    new String[]{"support is a share of the cohort", "/ COHORT_N, 4) AS CONF", "/ 1, 4) AS CONF"},
                    new String[]{"confidence is rounded to four places", "/ COHORT_N, 4) AS CONF", "/ COHORT_N, 1) AS CONF"},
                    new String[]{"the floor keeps a confidence AT it", "WHERE CONF >= CAST($confidence_floor AS DOUBLE)", "WHERE CONF > CAST($confidence_floor AS DOUBLE)"},
                    new String[]{"the most confident leads", "ORDER BY CONF DESC, CAUSE_RANK) AS RN", "ORDER BY CONF, CAUSE_RANK) AS RN"},
                    new String[]{"a tie goes to the fixed order", "ORDER BY CONF DESC, CAUSE_RANK) AS RN", "ORDER BY CONF DESC, CAUSE_RANK DESC) AS RN"},
                    new String[]{"alternatives exclude the leader", "FROM ranked WHERE RN > 1 GROUP BY", "FROM ranked WHERE RN > 0 GROUP BY"},
                    new String[]{"the leader is the cause", "AND b.RN = 1", "AND b.RN = 2"},
                    new String[]{"data quality is its own cause", "WHEN f.FINDING = 'data_quality' THEN f.REASON ELSE", "WHEN f.FINDING = 'data_quality' THEN 'unexplained' ELSE"},
                    new String[]{"unexplained label", "coalesce(b.CAUSE, 'unexplained')", "coalesce(b.CAUSE, 'unknown')"},
                    new String[]{"data quality confidence", "WHEN f.FINDING = 'data_quality' THEN 1 ELSE", "WHEN f.FINDING = 'data_quality' THEN 0 ELSE"},
                    new String[]{"unexplained confidence is zero", "coalesce(b.CONF, 0)", "coalesce(b.CONF, 1)"},
                    new String[]{"cause version", "b.CF_VERSION AS CAUSE_TARIFF_VERSION_ID", "b.CAUSE AS CAUSE_TARIFF_VERSION_ID"},
                    new String[]{"leakage low is the shortfall", "coalesce(f.UNDERCHARGED_AMOUNT, 0) AS LEAKAGE_LOW", "coalesce(f.OVERBILLED_AMOUNT, 0) AS LEAKAGE_LOW"},
                    new String[]{"leakage high is strict", "f.EXPECTED_HIGH - f.ACTUAL_CHARGE > CAST($tolerance AS DECIMAL(18,4))", "f.EXPECTED_HIGH - f.ACTUAL_CHARGE >= CAST($tolerance AS DECIMAL(18,4))"},
                    new String[]{"leakage high is against the dearest", "THEN f.EXPECTED_HIGH - f.ACTUAL_CHARGE ELSE 0", "THEN f.EXPECTED_LOW - f.ACTUAL_CHARGE ELSE 0"},
                    new String[]{"overbilled amount", "coalesce(f.OVERBILLED_AMOUNT, 0) AS OVERBILLED_AMOUNT", "coalesce(f.UNDERCHARGED_AMOUNT, 0) AS OVERBILLED_AMOUNT"},
                    new String[]{"leakage amount repeats the low", "LEAKAGE_LOW AS LEAKAGE_AMOUNT", "LEAKAGE_HIGH AS LEAKAGE_AMOUNT"});
            for (String[] m : exMutants) {
                assertTrue(count(explainSql, m[1]) >= 1, m[0] + ": the clause text is not in the statement: " + m[1]);
                String mutated = explainSql.replace(m[1], m[2]);
                assertNotEquals(explainSql, mutated, m[0]);
                assertNotEquals(baseExplain, mutantSignature(st, substitute(mutated, ep), "ITEM_KEY"), "rr_explain mutant survived: " + m[0]);
                if (assertWhy(why, m[0], st, substitute(mutated, ep), "ITEM_KEY", r.findings)) whyChecked.add(m[0]);
            }
            List<String[]> rcMutants = List.of(
                    new String[]{"the id names the plan", "concat_ws('|', CAUSE, FINDING, PLAN, SERVICE,", "concat_ws('|', CAUSE, FINDING, SERVICE,"},
                    new String[]{"the id names the finding class", "concat_ws('|', CAUSE, FINDING, PLAN,", "concat_ws('|', CAUSE, PLAN,"},
                    new String[]{"the id names the version the cause points at", "CANDIDATE_VERSIONS, CAUSE_TARIFF_VERSION_ID) AS ROOT_CAUSE_ID", "CANDIDATE_VERSIONS) AS ROOT_CAUSE_ID"},
                    new String[]{"calls", "count(*) AS CALLS", "count(DISTINCT PLAN) AS CALLS"},
                    new String[]{"first day", "min(EVENT_DATE) AS FIRST_DAY", "max(EVENT_DATE) AS FIRST_DAY"},
                    new String[]{"last day", "max(EVENT_DATE) AS LAST_DAY", "min(EVENT_DATE) AS LAST_DAY"},
                    new String[]{"leakage low", "sum(LEAKAGE_LOW) AS LEAKAGE_LOW", "sum(LEAKAGE_HIGH) AS LEAKAGE_LOW"},
                    new String[]{"leakage high", "sum(LEAKAGE_HIGH) AS LEAKAGE_HIGH", "sum(LEAKAGE_LOW) AS LEAKAGE_HIGH"},
                    new String[]{"overbilled", "sum(OVERBILLED_AMOUNT) AS OVERBILLED_AMOUNT", "sum(LEAKAGE_LOW) AS OVERBILLED_AMOUNT"},
                    new String[]{"run rate is revenue at risk", "ROUND(sum(LEAKAGE_LOW) /", "ROUND(sum(OVERBILLED_AMOUNT) /"},
                    new String[]{"run rate counts calendar days inclusively", "max(EVENT_DATE)) + 1), 2)", "max(EVENT_DATE)) + 2), 2)"},
                    new String[]{"weakest confidence", "min(CAUSE_CONFIDENCE) AS CAUSE_CONFIDENCE", "max(CAUSE_CONFIDENCE) AS CAUSE_CONFIDENCE"},
                    new String[]{"three examples", "[1:3]", "[1:2]"},
                    new String[]{"examples in key order", "list(ITEM_KEY ORDER BY ITEM_KEY)", "list(ITEM_KEY ORDER BY ITEM_KEY DESC)"});
            for (String[] m : rcMutants) {
                assertTrue(count(rootSql, m[1]) >= 1, m[0] + ": the clause text is not in the statement: " + m[1]);
                String mutated = rootSql.replace(m[1], m[2]);
                assertNotEquals(rootSql, mutated, m[0]);
                assertNotEquals(baseRoot, mutantSignature(st, substitute(mutated, Map.of()), "ROOT_CAUSE_ID"), "rr_root_cause mutant survived: " + m[0]);
                if (assertWhy(why, m[0], st, substitute(mutated, Map.of()), "ROOT_CAUSE_ID", d2.causes)) whyChecked.add(m[0]);
            }
            assertEquals(why.keySet(), whyChecked, "every named reason was checked against a mutant that exists");
            System.out.println("RERATE-R2 mutation pass: " + mutants.size() + " rr_rerate + " + hyMutants.size() + " rr_tariff_hygiene + " + exMutants.size()
                    + " rr_explain + " + rcMutants.size() + " rr_root_cause mutants, all killed");
        }
    }

    /**
     * The mutant is red for the NAMED reason: some row whose key contains {@code why.get(name)} differs from the base rows (or is
     * missing, or new). A mutant that only broke the one-row-per-key contract fails here. Returns false when the mutant has no named reason.
     */
    private static boolean assertWhy(Map<String, String> why, String name, Statement st, String sql, String key, Map<String, Map<String, Object>> base) throws Exception {
        String want = why.get(name);
        if (want == null) return false;
        Map<String, Map<String, Object>> got;
        try { got = readRows(st, "(" + sql + ")", key); }
        catch (AssertionError e) { throw new AssertionError(name + ": the mutant broke the one-row-per-key contract instead of changing " + want + ": " + e.getMessage()); }
        Set<String> changed = new TreeSet<>();
        Set<String> all = new TreeSet<>(base.keySet());
        all.addAll(got.keySet());
        for (String k : all) if (!base.containsKey(k) || !got.containsKey(k) || !sameRow(base.get(k), got.get(k))) changed.add(k);
        assertTrue(changed.stream().anyMatch(k -> k.contains(want)), name + ": expected the row " + want + " to change, but the changed rows are " + changed);
        return true;
    }

    /** The rows a mutated statement gives; a mutant that breaks the one-row-per-key contract is as dead as one that changes a value. */
    private static String mutantSignature(Statement st, String sql, String key) throws Exception {
        try { return signature(readRows(st, "(" + sql + ")", key)); }
        catch (AssertionError e) { return "CONTRACT BROKEN: " + e.getMessage(); }
    }

    private static int count(String text, String part) {
        int n = 0, i = 0;
        while ((i = text.indexOf(part, i)) >= 0) { n++; i += part.length(); }
        return n;
    }

    /** A stable text form of a row set: key, then every column in order. */
    private static String signature(Map<String, Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, Object>> e : new TreeMap<>(rows).entrySet()) {
            sb.append(e.getKey()).append('{');
            for (Map.Entry<String, Object> c : e.getValue().entrySet())
                sb.append(c.getKey()).append('=').append(c.getValue() instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(c.getValue())).append(';');
            sb.append('}').append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- the oracles

    private static BigDecimal rateOf(RerateCorpus.Tariff t) { return new BigDecimal(t.rate()); }

    private static boolean usable(RerateCorpus.Tariff t) { return t.rate() != null && t.from() != null && (t.to() == null || t.to().isAfter(t.from())); }

    /** Independent re-rating of every call in the window, per the plan's definitions, with plain loops and BigDecimal. */
    private static Map<String, Map<String, Object>> oracleCalls(RerateCorpus corpus, BigDecimal tolerance) { return oracleCalls(corpus, tolerance, DAY1); }

    private static Map<String, Map<String, Object>> oracleCalls(RerateCorpus corpus, BigDecimal tolerance, LocalDate day) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        LocalDateTime from = day.atStartOfDay(), to = day.plusDays(1).atStartOfDay();
        for (RerateCorpus.Usage u : corpus.usage()) {
            if (u.ts().isBefore(from) || !u.ts().isBefore(to)) continue;
            List<RerateCorpus.Tariff> cands = new ArrayList<>();
            for (RerateCorpus.Tariff t : corpus.tariffs())
                if (t.rate() != null && rateOf(t).signum() >= 0 && t.from() != null && t.plan().equals(u.plan()) && t.service().equals(u.service()) && !t.from().isAfter(u.ts())
                        && (t.to() == null || u.ts().isBefore(t.to()))) cands.add(t);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ITEM_KEY", u.id());
            row.put("EVENT_DATE", u.ts().toLocalDate().toString());
            row.put("EVENT_TS", TS.format(u.ts()));
            row.put("SUBSCRIBER_ID", u.subscriber());
            row.put("PLAN", u.plan());
            row.put("SERVICE", u.service());
            row.put("USAGE_UNITS", u.units());
            row.put("ACTUAL_CHARGE", u.charge());
            row.put("CANDIDATES", (long) cands.size());
            String resolution = "none", version = null, versions = null;
            BigDecimal low = null, high = null;
            if (!cands.isEmpty() && u.units() == null) {
                // no units: nothing can be expected (the corpus plants this on a single-row plan only)
                assertEquals(1, cands.size(), "the oracle models a call with no units on a single-candidate plan only");
                resolution = "single";
                version = cands.get(0).id();
                versions = version;
            } else if (!cands.isEmpty()) {
                List<BigDecimal> exp = new ArrayList<>();
                for (RerateCorpus.Tariff t : cands) exp.add(RerateCorpus.expected(u.units(), t.rate()));
                BigDecimal min = exp.stream().min(Comparator.naturalOrder()).get(), max = exp.stream().max(Comparator.naturalOrder()).get();
                List<String> ids = new ArrayList<>(cands.stream().map(RerateCorpus.Tariff::id).sorted().toList());
                versions = String.join(",", ids);
                Integer top = cands.stream().map(RerateCorpus.Tariff::priority).filter(x -> x != null).max(Comparator.naturalOrder()).orElse(null);
                long atTop = top == null ? 0 : cands.stream().filter(t -> top.equals(t.priority())).count();
                if (cands.size() == 1) { resolution = "single"; version = ids.get(0); low = min; high = max; }
                else if (min.compareTo(max) == 0) { resolution = "agree"; version = ids.get(0); low = min; high = max; }
                else if (atTop == 1) {
                    resolution = "precedence";
                    int i = 0;
                    for (; i < cands.size(); i++) if (top.equals(cands.get(i).priority())) break;
                    version = cands.get(i).id();
                    low = exp.get(i);
                    high = exp.get(i);
                } else { resolution = "disagree"; low = min; high = max; }
            }
            BigDecimal under = null, over = null;
            String reason = null, finding = null;
            if (!cands.isEmpty()) {
                if (low == null || u.charge() == null) { under = BigDecimal.ZERO; over = BigDecimal.ZERO; }
                else {
                    BigDecimal a = low.subtract(u.charge()), b = u.charge().subtract(high);
                    under = a.compareTo(tolerance) > 0 ? a : BigDecimal.ZERO;
                    over = b.compareTo(tolerance) > 0 ? b : BigDecimal.ZERO;
                }
            }
            if (cands.isEmpty()) { reason = "no_tariff"; finding = "data_quality"; }
            else if (u.charge() == null || u.units() == null) { reason = "unratable_call"; finding = "data_quality"; }
            else if (under.signum() > 0) { reason = "rate_mismatch"; finding = "leakage"; }
            else if (over.signum() > 0) { reason = "rate_mismatch"; finding = "overbilled"; }
            else if (resolution.equals("disagree")) { reason = "ambiguous_tariff"; finding = "data_quality"; }
            row.put("RESOLUTION", resolution);
            row.put("TARIFF_VERSION_ID", version);
            row.put("CANDIDATE_VERSIONS", versions);
            row.put("EXPECTED_CHARGE", low != null && low.compareTo(high) == 0 ? low : null);
            row.put("EXPECTED_LOW", low);
            row.put("EXPECTED_HIGH", high);
            row.put("UNDERCHARGED_AMOUNT", under);
            row.put("OVERBILLED_AMOUNT", over);
            row.put("REASON", reason);
            row.put("FINDING", finding);
            row.put("CONTROL", "rr_rerate");
            row.put("window_date", day.toString());
            out.put(u.id(), row);
        }
        return out;
    }

    private record Fit(String cause, int rank, String version, BigDecimal diff) {}

    private static boolean valid(RerateCorpus.Tariff t) {
        return t.rate() != null && rateOf(t).signum() >= 0 && t.from() != null && (t.to() == null || t.to().isAfter(t.from()));
    }

    /**
     * Independent explanation of one window's findings (plain loops and {@link BigDecimal}): the counterfactual amounts of three
     * hypotheses, the fit within the tolerance, exactness times the support in the cohort, the floor, the leader and its alternatives.
     */
    private static Map<String, Map<String, Object>> oracleExplain(RerateCorpus corpus, Map<String, Map<String, Object>> calls, LocalDate day,
                                                                  BigDecimal tol, BigDecimal within, BigDecimal floor) {
        Map<String, RerateCorpus.Usage> usage = new HashMap<>();
        for (RerateCorpus.Usage u : corpus.usage()) usage.put(u.id(), u);
        Map<String, List<String>> cohorts = new TreeMap<>();
        Map<String, Map<String, Fit>> fits = new TreeMap<>();
        for (Map<String, Object> c : calls.values()) {
            String finding = (String) c.get("FINDING");
            if (!"leakage".equals(finding) && !"overbilled".equals(finding)) continue;
            String id = (String) c.get("ITEM_KEY");
            cohorts.computeIfAbsent(c.get("PLAN") + "|" + c.get("SERVICE") + "|" + c.get("CANDIDATE_VERSIONS") + "|" + finding, k -> new ArrayList<>()).add(id);
            RerateCorpus.Usage u = usage.get(id);
            BigDecimal charge = (BigDecimal) c.get("ACTUAL_CHARGE");
            Map<String, Fit> mine = new TreeMap<>();
            List<RerateCorpus.Tariff> same = corpus.tariffs().stream().filter(t -> valid(t) && t.plan().equals(u.plan()) && t.service().equals(u.service())).toList();
            // stale_version: the version that ended most recently (ties: the smaller id)
            same.stream().filter(t -> t.to() != null && !t.to().isAfter(u.ts()))
                    .min(Comparator.comparing(RerateCorpus.Tariff::to, Comparator.reverseOrder()).thenComparing(RerateCorpus.Tariff::id))
                    .ifPresent(t -> fit(mine, "stale_version", 1, t, u, charge, tol));
            // lower_priority_rate: a row in force that did not win; the best fit
            if ("precedence".equals(c.get("RESOLUTION")))
                same.stream().filter(t -> !t.from().isAfter(u.ts()) && (t.to() == null || u.ts().isBefore(t.to())) && !t.id().equals(c.get("TARIFF_VERSION_ID")))
                        .min(Comparator.comparing((RerateCorpus.Tariff t) -> RerateCorpus.expected(u.units(), t.rate()).subtract(charge).abs()).thenComparing(RerateCorpus.Tariff::id))
                        .ifPresent(t -> fit(mine, "lower_priority_rate", 2, t, u, charge, tol));
            // early_version: the next version, billed before it starts
            same.stream().filter(t -> t.from().isAfter(u.ts()))
                    .min(Comparator.comparing(RerateCorpus.Tariff::from).thenComparing(RerateCorpus.Tariff::id))
                    .ifPresent(t -> fit(mine, "early_version", 3, t, u, charge, tol));
            fits.put(id, mine);
        }
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Map<String, Object> c : calls.values()) {
            String finding = (String) c.get("FINDING");
            if (finding == null) continue;
            String id = (String) c.get("ITEM_KEY");
            String cause, alt = null, causeVersion = null;
            BigDecimal conf;
            if (finding.equals("data_quality")) { cause = (String) c.get("REASON"); conf = BigDecimal.ONE; }
            else {
                List<String> cohort = cohorts.get(c.get("PLAN") + "|" + c.get("SERVICE") + "|" + c.get("CANDIDATE_VERSIONS") + "|" + finding);
                record Scored(Fit fit, BigDecimal conf) {}
                List<Scored> scored = new ArrayList<>();
                for (Fit f : fits.get(id).values()) {
                    long support = cohort.stream().filter(o -> fits.get(o).containsKey(f.cause())).count();
                    BigDecimal exact = f.diff().signum() == 0 ? BigDecimal.ONE : within;
                    BigDecimal cf = exact.multiply(BigDecimal.valueOf(support)).divide(BigDecimal.valueOf(cohort.size()), 4, RoundingMode.HALF_UP);
                    if (cf.compareTo(floor) >= 0) scored.add(new Scored(f, cf));
                }
                scored.sort(Comparator.comparing((Scored x) -> x.conf()).reversed().thenComparingInt(x -> x.fit().rank()));
                if (scored.isEmpty()) { cause = "unexplained"; conf = BigDecimal.ZERO; }
                else {
                    cause = scored.get(0).fit().cause();
                    conf = scored.get(0).conf();
                    causeVersion = scored.get(0).fit().version();
                    if (scored.size() > 1) alt = String.join(",", scored.subList(1, scored.size()).stream().map(x -> x.fit().cause()).toList());
                }
            }
            BigDecimal high = (BigDecimal) c.get("EXPECTED_HIGH"), charge = (BigDecimal) c.get("ACTUAL_CHARGE");
            BigDecimal leakHigh = high != null && charge != null && high.subtract(charge).compareTo(tol) > 0 ? high.subtract(charge) : BigDecimal.ZERO;
            BigDecimal low = c.get("UNDERCHARGED_AMOUNT") == null ? BigDecimal.ZERO : (BigDecimal) c.get("UNDERCHARGED_AMOUNT");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ITEM_KEY", id);
            row.put("EVENT_DATE", c.get("EVENT_DATE"));
            row.put("EVENT_TS", c.get("EVENT_TS"));
            row.put("SUBSCRIBER_ID", c.get("SUBSCRIBER_ID"));
            row.put("PLAN", c.get("PLAN"));
            row.put("SERVICE", c.get("SERVICE"));
            row.put("REASON", c.get("REASON"));
            row.put("FINDING", finding);
            row.put("CAUSE", cause);
            row.put("CAUSE_CONFIDENCE", conf);
            row.put("CAUSE_ALTERNATIVE", alt);
            row.put("CAUSE_TARIFF_VERSION_ID", causeVersion);
            row.put("TARIFF_VERSION_ID", c.get("TARIFF_VERSION_ID"));
            row.put("CANDIDATE_VERSIONS", c.get("CANDIDATE_VERSIONS"));
            row.put("ACTUAL_CHARGE", charge);
            row.put("EXPECTED_LOW", c.get("EXPECTED_LOW"));
            row.put("EXPECTED_HIGH", high);
            row.put("LEAKAGE_LOW", low);
            row.put("LEAKAGE_HIGH", leakHigh);
            row.put("LEAKAGE_AMOUNT", low);
            row.put("OVERBILLED_AMOUNT", c.get("OVERBILLED_AMOUNT") == null ? BigDecimal.ZERO : c.get("OVERBILLED_AMOUNT"));
            row.put("CONTROL", "rr_explain");
            row.put("window_date", day.toString());
            out.put(id, row);
        }
        return out;
    }

    private static void fit(Map<String, Fit> mine, String cause, int rank, RerateCorpus.Tariff t, RerateCorpus.Usage u, BigDecimal charge, BigDecimal tol) {
        BigDecimal diff = RerateCorpus.expected(u.units(), t.rate()).subtract(charge).abs();
        if (diff.compareTo(tol) <= 0) mine.put(cause, new Fit(cause, rank, t.id(), diff));
    }

    /** Independent grouping of explained findings (any number of days) into root causes. */
    private static Map<String, Map<String, Object>> oracleRoot(List<Map<String, Object>> findings) {
        Map<String, List<Map<String, Object>>> groups = new TreeMap<>();
        for (Map<String, Object> f : findings) {
            List<String> parts = new ArrayList<>();
            for (String k : List.of("CAUSE", "FINDING", "PLAN", "SERVICE", "CANDIDATE_VERSIONS", "CAUSE_TARIFF_VERSION_ID")) if (f.get(k) != null) parts.add((String) f.get(k));
            groups.computeIfAbsent(String.join("|", parts), k -> new ArrayList<>()).add(f);
        }
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> g : groups.entrySet()) {
            List<Map<String, Object>> rows = g.getValue();
            Map<String, Object> first = rows.get(0);
            LocalDate min = rows.stream().map(x -> LocalDate.parse((String) x.get("EVENT_DATE"))).min(Comparator.naturalOrder()).get();
            LocalDate max = rows.stream().map(x -> LocalDate.parse((String) x.get("EVENT_DATE"))).max(Comparator.naturalOrder()).get();
            BigDecimal low = BigDecimal.ZERO, high = BigDecimal.ZERO, over = BigDecimal.ZERO, conf = null;
            for (Map<String, Object> x : rows) {
                low = low.add((BigDecimal) x.get("LEAKAGE_LOW"));
                high = high.add((BigDecimal) x.get("LEAKAGE_HIGH"));
                over = over.add((BigDecimal) x.get("OVERBILLED_AMOUNT"));
                BigDecimal c = (BigDecimal) x.get("CAUSE_CONFIDENCE");
                if (conf == null || c.compareTo(conf) < 0) conf = c;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ROOT_CAUSE_ID", g.getKey());
            for (String k : List.of("CAUSE", "FINDING", "PLAN", "SERVICE", "CANDIDATE_VERSIONS", "CAUSE_TARIFF_VERSION_ID")) row.put(k, first.get(k));
            row.put("CALLS", (long) rows.size());
            row.put("FIRST_DAY", min.toString());
            row.put("LAST_DAY", max.toString());
            row.put("LEAKAGE_LOW", low);
            row.put("LEAKAGE_HIGH", high);
            row.put("OVERBILLED_AMOUNT", over);
            row.put("RUN_RATE_PER_DAY", low.divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(min, max) + 1), 2, RoundingMode.HALF_UP));
            row.put("CAUSE_CONFIDENCE", conf);
            row.put("EXAMPLE_ITEM_KEYS", String.join(",", rows.stream().map(x -> (String) x.get("ITEM_KEY")).sorted().limit(3).toList()));
            row.put("CONTROL", "rr_root_cause");
            out.put(g.getKey(), row);
        }
        return out;
    }

    /** Independent judgement of the tariff table: pairs, sweeps and singles, with plain loops. */
    private static Map<String, Map<String, Object>> oracleTariff(RerateCorpus corpus) {
        List<RerateCorpus.Tariff> sorted = new ArrayList<>(corpus.tariffs());
        sorted.sort(Comparator.comparing(RerateCorpus.Tariff::plan).thenComparing(RerateCorpus.Tariff::service)
                .thenComparing(RerateCorpus.Tariff::from, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(RerateCorpus.Tariff::id));
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (RerateCorpus.Tariff t : sorted) {
            boolean valid = t.from() != null && (t.to() == null || t.to().isAfter(t.from()));
            String reason = t.from() == null ? "missing_start" : !valid ? "inverted_interval" : t.rate() == null ? "missing_rate" : rateOf(t).signum() < 0 ? "negative_rate" : null;
            if (reason != null) put(out, reason, t, null, t.from(), t.to(), null);
        }
        for (int i = 0; i < sorted.size(); i++)
            for (int j = i + 1; j < sorted.size(); j++) {
                RerateCorpus.Tariff a = sorted.get(i), b = sorted.get(j);
                if (!usable(a) || !usable(b) || !a.plan().equals(b.plan()) || !a.service().equals(b.service())) continue;
                if (!b.from().isBefore(a.to() == null ? FAR : a.to())) continue;
                boolean same = rateOf(a).compareTo(rateOf(b)) == 0;
                LocalDateTime start = a.from().isAfter(b.from()) ? a.from() : b.from();
                LocalDateTime end = a.to() == null ? b.to() : b.to() == null ? a.to() : a.to().isBefore(b.to()) ? a.to() : b.to();
                Boolean ordered = same ? null : !java.util.Objects.equals(a.priority(), b.priority());
                put(out, same ? "overlap_same_rate" : "overlap_different_rate", a, b, start, end, ordered);
            }
        Map<String, List<RerateCorpus.Tariff>> groups = new TreeMap<>();
        for (RerateCorpus.Tariff t : sorted) if (usable(t)) groups.computeIfAbsent(t.plan() + "|" + t.service(), k -> new ArrayList<>()).add(t);
        for (List<RerateCorpus.Tariff> g : groups.values()) {
            for (int i = 1; i < g.size(); i++) {
                boolean open = false;
                LocalDateTime end = null;
                RerateCorpus.Tariff endRow = null;
                for (int k = 0; k < i; k++) {
                    RerateCorpus.Tariff p = g.get(k);
                    if (p.to() == null) open = true;
                    else if (end == null || p.to().isAfter(end) || (p.to().isEqual(end) && p.id().compareTo(endRow.id()) > 0)) { end = p.to(); endRow = p; }
                }
                RerateCorpus.Tariff cur = g.get(i);
                if (!open && end != null && cur.from().isAfter(end)) put(out, "gap", cur, endRow, end, cur.from(), null);
            }
        }
        return out;
    }

    private static void put(Map<String, Map<String, Object>> out, String reason, RerateCorpus.Tariff a, RerateCorpus.Tariff b,
                            LocalDateTime from, LocalDateTime to, Boolean ordered) {
        Map<String, Object> row = new LinkedHashMap<>();
        StringBuilder key = new StringBuilder(reason).append('|').append(a.plan()).append('|').append(a.service()).append('|').append(a.id());
        if (b != null) key.append('|').append(b.id());
        row.put("FINDING_KEY", key.toString());
        row.put("REASON", reason);
        row.put("FINDING", "data_quality");
        row.put("PLAN", a.plan());
        row.put("SERVICE", a.service());
        row.put("TARIFF_VERSION_ID", a.id());
        row.put("OTHER_TARIFF_VERSION_ID", b == null ? null : b.id());
        row.put("RATE_PER_UNIT", a.rate() == null ? null : rateOf(a));
        row.put("OTHER_RATE_PER_UNIT", b == null || reason.equals("gap") || b.rate() == null ? null : rateOf(b));
        row.put("PERIOD_FROM", from == null ? null : TS.format(from));
        row.put("PERIOD_TO", to == null ? null : TS.format(to));
        row.put("PRIORITY_ORDERED", ordered);
        row.put("CONTROL", "rr_tariff_hygiene");
        assertNull(out.put(key.toString(), row), "duplicate tariff finding " + key);
    }

    private static void assertSameRow(String key, Map<String, Object> want, Map<String, Object> got) {
        assertEquals(want.keySet(), got.keySet(), key + ": columns");
        for (String col : want.keySet()) assertSame(key + "." + col, want.get(col), got.get(col));
    }

    private static boolean sameRow(Map<String, Object> a, Map<String, Object> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String col : a.keySet()) {
            Object x = a.get(col), y = b.get(col);
            if (x == null || y == null) { if (x != y) return false; continue; }
            if (x instanceof BigDecimal p && y instanceof BigDecimal q) { if (p.compareTo(q) != 0) return false; }
            else if (!x.equals(y)) return false;
        }
        return true;
    }

    private static void assertSame(String at, Object want, Object got) {
        if (want == null || got == null) { assertNull(want, at + " want null"); assertNull(got, at + " want " + want + " got null"); return; }
        if (want instanceof BigDecimal w && got instanceof BigDecimal g) assertEquals(0, w.compareTo(g), at + " want " + w + " got " + g);
        else assertEquals(want, got, at);
    }

    // ------------------------------------------------------------------------------------------- the run

    private record Run(Path space, Map<String, JobConfig> jobs, Map<String, Map<String, Object>> calls,
                       Map<String, Map<String, Object>> tariff, Map<String, Map<String, Object>> findings,
                       Map<String, Map<String, Object>> causes, long hygieneMs, long rerateMs, long explainMs, long rootMs) {}

    private record Day2(Map<String, Map<String, Object>> findings, Map<String, Map<String, Object>> causes) {}

    /** Run the three day-bound Jobs for day 2, then the root-cause snapshot over both days. */
    private static Day2 dayTwo(Run r, Path root) throws Exception {
        Map<String, String> w2 = window(DAY1.plusDays(1));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.copyOf(r.jobs.values()), new ConsignmentEventBus(), s, null,
                     root.resolve("audit-day2").toString(), null, null, r.space.resolve("data").toString())) {
            js.start();
            assertEquals("SUCCESS", runJob(js, "rr_rerate", w2).status());
            assertEquals("SUCCESS", runJob(js, "rr_explain", w2).status());
            assertEquals("SUCCESS", runJob(js, "rr_root_cause", Map.of()).status());
        }
        return new Day2(readFindings(r.space, DAY1.plusDays(1)), readCauses(r.space));
    }

    /** Create the Space, ingest the files through the template's Pipelines, run the hygiene Job then the re-rating Job for day 1, read both sinks. */
    private static Run run(Path root, Map<String, String> files, Map<String, String> overrides) throws Exception {
        Files.createDirectories(root);
        Path space = createSpace(root);
        ingest(space, files);
        Map<String, JobConfig> jobs = jobs(space.resolve("config"));
        Map<String, String> args = new HashMap<>(WINDOW1);
        args.putAll(overrides);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.copyOf(jobs.values()), new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            JobRun hy = runJob(js, "rr_tariff_hygiene", Map.of());
            assertEquals("SUCCESS", hy.status(), hy.message());
            Map<String, String> rerateArgs = new HashMap<>(WINDOW1);
            if (overrides.containsKey("tolerance")) rerateArgs.put("tolerance", overrides.get("tolerance"));
            JobRun rr = runJob(js, "rr_rerate", rerateArgs);
            assertEquals("SUCCESS", rr.status(), rr.message());
            JobRun ex = runJob(js, "rr_explain", args);
            assertEquals("SUCCESS", ex.status(), ex.message());
            JobRun rc = runJob(js, "rr_root_cause", Map.of());
            assertEquals("SUCCESS", rc.status(), rc.message());
            return new Run(space, jobs, readCalls(space), readTariff(space), readFindings(space, DAY1), readCauses(space),
                    hy.durationMs(), rr.durationMs(), ex.durationMs(), rc.durationMs());
        }
    }

    private static Map<String, Map<String, Object>> readCalls(Path space) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW sink AS SELECT * FROM read_parquet('" + space.resolve("data").resolve("rerated_usage").toString().replace('\\', '/')
                    + "/**/*.parquet', hive_partitioning = true) WHERE window_date = DATE '2026-07-01'");
            return readRows(st, "sink", "ITEM_KEY");
        }
    }

    private static Map<String, Map<String, Object>> readFindings(Path space, LocalDate day) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW sink AS SELECT * FROM read_parquet('" + space.resolve("data").resolve("rerating_findings").toString().replace('\\', '/')
                    + "/**/*.parquet', hive_partitioning = true) WHERE window_date = DATE '" + day + "'");
            return readRows(st, "sink", "ITEM_KEY");
        }
    }

    private static Map<String, Map<String, Object>> readCauses(Path space) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW sink AS SELECT * FROM read_parquet('" + space.resolve("data").resolve("rerating_root_causes").toString().replace('\\', '/')
                    + "/**/*.parquet')");
            return readRows(st, "sink", "ROOT_CAUSE_ID");
        }
    }

    private static Map<String, Map<String, Object>> readTariff(Path space) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW sink AS SELECT * FROM read_parquet('" + space.resolve("data").resolve("rerating_tariff_findings").toString().replace('\\', '/')
                    + "/**/*.parquet')");
            return readRows(st, "sink", "FINDING_KEY");
        }
    }

    /** Read a relation into {@code key -> column -> value}: decimals as BigDecimal, integers as Long, everything else as text. */
    private static Map<String, Map<String, Object>> readRows(Statement st, String relation, String keyColumn) throws Exception {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + relation)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String type = md.getColumnTypeName(i).toUpperCase();
                    Object v;
                    if (type.startsWith("DECIMAL")) v = rs.getBigDecimal(i);
                    else if (type.equals("BIGINT") || type.equals("INTEGER")) { long l = rs.getLong(i); v = rs.wasNull() ? null : (Object) l; }
                    else if (type.equals("TIMESTAMP")) { LocalDateTime t = rs.getObject(i, LocalDateTime.class); v = t == null ? null : TS.format(t); }
                    else if (type.equals("BOOLEAN")) { boolean b = rs.getBoolean(i); v = rs.wasNull() ? null : (Object) b; }
                    else v = rs.getString(i);
                    row.put(md.getColumnLabel(i), v);
                }
                assertNull(out.put((String) row.get(keyColumn), row), "one row per " + keyColumn + ": " + row.get(keyColumn));
            }
        }
        return out;
    }

    private static long sinkRows(Path data, String dataset, String where) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + data.resolve(dataset).toString().replace('\\', '/')
                     + "/**/*.parquet', hive_partitioning = true) WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void registerViews(Statement st, Path space) throws Exception {
        for (String feed : FEED_COLUMNS.keySet()) {
            List<String> files = new ArrayList<>();
            try (Stream<Path> w = Files.walk(space.resolve("data").resolve(feed).resolve("database"))) {
                for (Path f : w.filter(x -> x.toString().endsWith(".parquet")).toList()) files.add("'" + f.toString().replace('\\', '/') + "'");
            }
            st.execute("CREATE VIEW " + feed + " AS SELECT * FROM read_parquet([" + String.join(",", files) + "], union_by_name = true, hive_partitioning = true)");
        }
        for (String sink : List.of("rerated_usage", "rerating_findings"))
            st.execute("CREATE VIEW " + sink + " AS SELECT * FROM read_parquet('" + space.resolve("data").resolve(sink).toString().replace('\\', '/')
                    + "/**/*.parquet', hive_partitioning = true)");
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private static Path createSpace(Path root) throws Exception {
        copyTree(template(), root.resolve("_templates").resolve("telco-rerating"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"rerating\",\"template\":\"telco-rerating\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }
        return root.resolve("rerating");
    }

    /** Drop each feed's files in its poll dir and run its Collector; every CSV row must land in the feed's Parquet. */
    private static void ingest(Path space, Map<String, String> files) throws Exception {
        DuckDbUtil.loadDriver();
        Map<String, Long> rows = new TreeMap<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            String feed = f.getKey().substring(0, f.getKey().indexOf('/'));
            PipelineConfig cfg = PipelineConfig.load(space.resolve("config").resolve(feed).resolve(feed + "_pipeline.toon").toString());
            Path poll = Path.of(cfg.dirs().poll()).toAbsolutePath().normalize();
            assertTrue(poll.startsWith(space), feed + " polls outside its Space: " + poll);
            Files.createDirectories(poll);
            Files.writeString(poll.resolve(f.getKey().substring(f.getKey().indexOf('/') + 1)), f.getValue());
            rows.merge(feed, f.getValue().lines().count() - 1, Long::sum);
        }
        for (Map.Entry<String, Long> e : rows.entrySet()) {
            PipelineConfig cfg = PipelineConfig.load(space.resolve("config").resolve(e.getKey()).resolve(e.getKey() + "_pipeline.toon").toString());
            CollectorProcessor.run(cfg);
            Path db = Path.of(cfg.dirs().database()).toAbsolutePath().normalize();
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + db.toString().replace('\\', '/')
                         + "/**/*.parquet', union_by_name = true)")) {
                rs.next();
                assertEquals(e.getValue().longValue(), rs.getLong(1), e.getKey() + ": the Pipeline must ingest every row");
            }
        }
    }

    private static JobRun runJob(JobService js, String name, Map<String, String> args) throws Exception {
        String prior = js.lastRunOf(name).map(JobRun::runId).orElse(null);
        assertTrue(js.triggerRun(name, null, args).isPresent(), name);
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            Optional<JobRun> r = js.lastRunOf(name);
            if (r.isPresent() && !r.get().runId().equals(prior)) return r.get();
            Thread.sleep(50);
        }
        throw new AssertionError(name + ": no run within 120s");
    }

    private static JobRun await(Supplier<JobRun> s) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        JobRun r;
        while ((r = s.get()) == null && System.nanoTime() < deadline) Thread.sleep(50);
        assertNotNull(r, "expected a job run within 60s");
        return r;
    }

    private static Map<String, JobConfig> jobs(Path config) throws Exception {
        Map<String, JobConfig> jobs = new TreeMap<>();
        try (Stream<Path> files = Files.list(config.resolve("jobs"))) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith("_job.toon")).sorted().toList()) {
                JobConfig j = JobConfig.fromMap(Map.of("job", ToonHelper.requireSection(ToonHelper.load(f.toString()), "job")));
                if ("sql.template".equals(j.type())) jobs.put(j.name(), j);
            }
        }
        return jobs;
    }

    /** {@code $name} to quoted literal, longest name first (as the Job framework substitutes). */
    private static String substitute(String sql, Map<String, String> params) {
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String n : names) {
            String v = params.get(n);
            if (v.startsWith("$")) v = WINDOW1.getOrDefault(n, v);   // the rolling $yesterday / $today tokens: pin day 1
            sql = sql.replace("$" + n, "'" + v.replace("'", "''") + "'");
        }
        return sql;
    }

    private static String emptyCsv(String columns) throws Exception {
        Path f = Files.createTempFile("telco-rerating-empty", ".csv");
        f.toFile().deleteOnExit();
        StringBuilder header = new StringBuilder();
        for (String part : columns.substring(1, columns.length() - 1).split(",(?=')")) {
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
}
