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
 * {@code PACK-RERATING-1} slice R1 golden test. Boots the shipped {@code spaces/_templates/telco-rerating} Space
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
    void theTemplateShipsTwoChainedJobsAndFourDatasetsAndItsFilesReachTheSpace(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path config = space.resolve("config");
        Map<String, JobConfig> jobs = jobs(config);
        assertEquals(Set.of("rr_tariff_hygiene", "rr_rerate"), jobs.keySet(), "slice R1 ships exactly these two Jobs");
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
            assertEquals(Set.of("tariff_version.toon", "rated_usage.toon", "rerating_tariff_findings.toon", "rerated_usage.toon"),
                    new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()));
        }
        assertTrue(Files.isRegularFile(config.resolve("runbooks").resolve("telco-rerating-runbooks.md")), "the runbook skeleton ships");
        for (String feed : FEED_COLUMNS.keySet())
            assertTrue(Files.isRegularFile(space.resolve("data").resolve(feed).resolve("database").resolve("schema-seed.parquet")), feed + " seed");
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("rerating_tariff_findings").resolve("schema-seed.parquet")));
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("rerated_usage").resolve("window_date=1970-01-01").resolve("seed.parquet")));
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
        assertEquals(Map.of("rate_mismatch|leakage", 14L, "rate_mismatch|overbilled", 6L, "ambiguous_tariff|data_quality", 7L, "no_tariff|data_quality", 9L), byClass);
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
        assertEquals(0, new BigDecimal("0.30").multiply(BigDecimal.valueOf(5)).add(new BigDecimal("0.20")).add(new BigDecimal("1.00")).add(new BigDecimal("0.03"))
                .add(new BigDecimal("1.50")).add(new BigDecimal("1.00")).add(new BigDecimal("1.00")).add(new BigDecimal("0.03")).add(new BigDecimal("2.00")).add(new BigDecimal("2.00")).compareTo(under), "revenue at risk of the planted leakage, nothing netted against over-billing");
        assertEquals(0, new BigDecimal("0.20").add(new BigDecimal("0.03")).add(new BigDecimal("1.00")).add(new BigDecimal("1.00")).add(new BigDecimal("0.03")).add(new BigDecimal("2.00")).compareTo(over), "the over-billed amount is its own figure");
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
        assertEquals(19, got.size());
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
            assertEquals(1, sinkRows(data, "rerated_usage", "window_date = DATE '2026-07-02'"), "the next window holds only the one call the corpus keeps for it, at midnight");
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
        String rerate = r.jobs.get("rr_rerate").params().get("sql"), hygiene = r.jobs.get("rr_tariff_hygiene").params().get("sql");
        Map<String, String> p = new HashMap<>(r.jobs.get("rr_rerate").params());
        p.putAll(WINDOW1);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            registerViews(st, r.space);
            String baseCalls = signature(readRows(st, "(" + substitute(rerate, p) + ")", "ITEM_KEY"));
            String baseTariff = signature(readRows(st, "(" + substitute(hygiene, Map.of()) + ")", "FINDING_KEY"));
            assertEquals(signature(r.calls), baseCalls, "the harness reproduces the Job's own rows");
            assertEquals(signature(r.tariff), baseTariff, "the harness reproduces the hygiene Job's own rows");

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
                    new String[]{"null rate excluded", "WHERE RATE_PER_UNIT IS NOT NULL", "WHERE TRUE"},
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
                    new String[]{"no tariff is data quality", "CASE WHEN CANDIDATES = 0 THEN 'data_quality' WHEN UNDER", "CASE WHEN CANDIDATES = 0 THEN 'leakage' WHEN UNDER"},
                    new String[]{"range is data quality", "WHEN RESOLUTION = 'disagree' THEN 'data_quality' END AS FINDING", "WHEN RESOLUTION = 'disagree' THEN 'leakage' END AS FINDING"},
                    new String[]{"window date", "CAST($window_start AS DATE) AS window_date", "CAST($window_end AS DATE) AS window_date"}));
            Map<String, String> pm = p;
            for (String[] m : mutants) {
                assertTrue(count(rerate, m[1]) >= 1, m[0] + ": the clause text is not in the statement: " + m[1]);
                String mutated = rerate.replace(m[1], m[2]);
                assertNotEquals(rerate, mutated, m[0]);
                assertNotEquals(baseCalls, mutantSignature(st, substitute(mutated, pm), "ITEM_KEY"), "rr_rerate mutant survived: " + m[0]);
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
                    new String[]{"gap previous id", "arg_max(TARIFF_VERSION_ID, EFFECTIVE_TO) OVER w", "arg_min(TARIFF_VERSION_ID, EFFECTIVE_TO) OVER w"},
                    new String[]{"gap period", "PREV_END AS PERIOD_FROM, EFFECTIVE_FROM AS PERIOD_TO", "EFFECTIVE_FROM AS PERIOD_FROM, PREV_END AS PERIOD_TO"},
                    new String[]{"gap end", "max(EFFECTIVE_TO) OVER w AS PREV_END", "min(EFFECTIVE_TO) OVER w AS PREV_END"},
                    new String[]{"finding key", "concat_ws('|', REASON, PLAN, SERVICE, TARIFF_VERSION_ID, OTHER_TARIFF_VERSION_ID)", "concat_ws('|', REASON, PLAN, SERVICE, TARIFF_VERSION_ID)"});
            for (String[] m : hyMutants) {
                assertTrue(count(hygiene, m[1]) >= 1, m[0] + ": the clause text is not in the statement: " + m[1]);
                String mutated = hygiene.replace(m[1], m[2]);
                assertNotEquals(hygiene, mutated, m[0]);
                assertNotEquals(baseTariff, mutantSignature(st, substitute(mutated, Map.of()), "FINDING_KEY"), "rr_tariff_hygiene mutant survived: " + m[0]);
            }
            System.out.println("RERATE-R1 mutation pass: " + mutants.size() + " rr_rerate + " + hyMutants.size() + " rr_tariff_hygiene mutants, all killed");
        }
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
    private static Map<String, Map<String, Object>> oracleCalls(RerateCorpus corpus, BigDecimal tolerance) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        LocalDateTime from = DAY1.atStartOfDay(), to = DAY1.plusDays(1).atStartOfDay();
        for (RerateCorpus.Usage u : corpus.usage()) {
            if (u.ts().isBefore(from) || !u.ts().isBefore(to)) continue;
            List<RerateCorpus.Tariff> cands = new ArrayList<>();
            for (RerateCorpus.Tariff t : corpus.tariffs())
                if (t.rate() != null && t.from() != null && t.plan().equals(u.plan()) && t.service().equals(u.service()) && !t.from().isAfter(u.ts())
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
            if (!cands.isEmpty()) {
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
                BigDecimal a = low.subtract(u.charge()), b = u.charge().subtract(high);
                under = a.compareTo(tolerance) > 0 ? a : BigDecimal.ZERO;
                over = b.compareTo(tolerance) > 0 ? b : BigDecimal.ZERO;
            }
            if (cands.isEmpty()) { reason = "no_tariff"; finding = "data_quality"; }
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
            row.put("window_date", DAY1.toString());
            out.put(u.id(), row);
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
                    else if (end == null || p.to().isAfter(end)) { end = p.to(); endRow = p; }
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
                       Map<String, Map<String, Object>> tariff, long hygieneMs, long rerateMs) {}

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
            JobRun rr = runJob(js, "rr_rerate", args);
            assertEquals("SUCCESS", rr.status(), rr.message());
            return new Run(space, jobs, readCalls(space), readTariff(space), hy.durationMs(), rr.durationMs());
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
