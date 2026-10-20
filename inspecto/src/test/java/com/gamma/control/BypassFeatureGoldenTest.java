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
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
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
 * {@code TELCO-BYPASS-SIMBOX-1} slice B1 golden test. Boots the shipped {@code spaces/_templates/telco-bypass} Space
 * Template through {@code POST /spaces} (every seed gate), ingests the synthetic {@link BypassCorpus} through the
 * template's own Pipelines on the production Consignment path, runs the {@code tb_features} {@code sql.template} Job
 * as authored, and holds its SIM-day rows to
 * <ul>
 *   <li>an INDEPENDENT oracle (plain Java over the corpus rows) for every signal S1 to S12 of every scored SIM;</li>
 *   <li>the exact {@code box_like} set: the planted SIMs and each clause's just-inside SIM flagged, every look-alike
 *       and each clause's exactly-at SIM silent, the pinned ambiguous pair flagged;</li>
 *   <li>degradation: a feed with blank or absent optional columns, or without the three Reference feeds, still runs
 *       and names what it omitted in {@code missing}; and
 *   <li>time-zone independence: identical rows under two host zones, and the explicit zone parameter moves the hour
 *       signals.</li>
 * </ul>
 */
class BypassFeatureGoldenTest {

    private static final Map<String, String> FEED_COLUMNS = new LinkedHashMap<>();
    static {
        FEED_COLUMNS.put("voice_cdr", "{'record_id':'VARCHAR','start_ts':'TIMESTAMP','direction':'VARCHAR','service':'VARCHAR',"
                + "'a_number':'VARCHAR','b_number':'VARCHAR','duration_s':'INTEGER','roaming':'VARCHAR','cell_id':'VARCHAR',"
                + "'answered':'VARCHAR','release_cause':'VARCHAR','imei':'VARCHAR','imsi':'VARCHAR'}");
        FEED_COLUMNS.put("cell_site", "{'cell_id':'VARCHAR','site_id':'VARCHAR','lat':'DOUBLE','lon':'DOUBLE'}");
        FEED_COLUMNS.put("number_plan", "{'prefix':'VARCHAR','operator':'VARCHAR','own':'VARCHAR'}");
        FEED_COLUMNS.put("subscriber_profile", "{'msisdn':'VARCHAR','plan':'VARCHAR','dealer_id':'VARCHAR',"
                + "'activated_on':'DATE','corporate_flag':'VARCHAR'}");
    }

    private static final Set<String> REFERENCES = Set.of("cell_site", "number_plan", "subscriber_profile");
    private static final LocalDate DAY1 = LocalDate.of(2026, 7, 1);
    private static final Map<String, String> WINDOW1 = window(DAY1);

    private static boolean regenerate() { return "true".equals(System.getenv("TELCO_BYPASS_REGENERATE")); }

    private static Map<String, String> window(LocalDate day) {
        return Map.of("window_start", day + " 00:00:00", "window_end", day.plusDays(1) + " 00:00:00", "day", day.toString());
    }

    private static Path template() {
        Path p = Path.of("..", "spaces", "_templates", "telco-bypass").toAbsolutePath().normalize();
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
    void theTemplateShipsTheSimDayJobAndItsFilesReachTheSpace(@TempDir Path root) throws Exception {
        Path space = createSpace(root);
        Path config = space.resolve("config");
        List<JobConfig> jobs = jobs(config);
        assertEquals(Set.of("tb_features", "tb_factors", "tb_findings", "tb_cluster_members", "tb_clusters"), new TreeSet<>(jobs.stream().map(JobConfig::name).toList()),
                "B1's SIM-day stage, B2's two and B3's two sql.template stages (the risk.score Job is not one); B2's and B3's goldens pin them");
        JobConfig j = featureJob(config);
        assertEquals("tb_features", j.name());
        assertEquals("0 3 * * *", j.cron());
        assertEquals("$yesterday", j.params().get("window_start"));
        assertEquals("$today", j.params().get("window_end"));
        assertEquals("$yesterday", j.params().get("day"));
        assertEquals("window_date", j.params().get("incremental.column"));
        assertEquals("31", j.params().get("incremental.retain"));
        assertEquals("tb_features", j.params().get("sink_dataset"));
        assertEquals(Set.of("voice_cdr", "cell_site", "number_plan", "subscriber_profile"),
                new TreeSet<>(List.of(j.params().get("sources").split(","))));
        try (Stream<Path> s = Files.list(config.resolve("registry").resolve("datasets"))) {
            assertEquals(Set.of("voice_cdr.toon", "cell_site.toon", "number_plan.toon", "subscriber_profile.toon", "tb_features.toon",
                            "tb_factors.toon", "tb_findings.toon", "tb_cluster_members.toon", "tb_clusters.toon"),
                    new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()));
        }
        assertTrue(Files.isRegularFile(config.resolve("runbooks").resolve("telco-bypass-runbooks.md")), "the runbook skeleton ships");
        for (String feed : FEED_COLUMNS.keySet())
            assertTrue(Files.isRegularFile(space.resolve("data").resolve(feed).resolve("database").resolve("schema-seed.parquet")), feed + " seed");
        assertTrue(Files.isRegularFile(space.resolve("data").resolve("tb_features").resolve("window_date=1970-01-01").resolve("seed.parquet")));
        // every threshold is a Job parameter, none is hidden in the SQL
        for (String k : List.of("min_events", "short_s", "seq_step", "seq_prefix", "night_end_hour", "feed_zone", "local_zone",
                "cand_max_inbound", "cand_min_b_div", "cand_min_short", "cand_min_voice_only", "cand_min_top_cell"))
            assertNotNull(j.params().get(k), "parameter " + k);
    }

    /**
     * Every Dataset the Job reads or writes ships a ZERO-row seed with exactly the Schema the real thing has, so the
     * Job runs on a fresh Space (an absent Reference reads as empty) and the Dataset has a Schema before its first run.
     * The feed seeds are pinned to the Pipelines' real output; the {@code tb_features} seed to the Job's own SQL.
     */
    @Test
    void everyDatasetShipsAnEmptySeedWithTheSchemaOfTheRealThing(@TempDir Path root) throws Exception {
        Path tpl = template();
        DuckDbUtil.loadDriver();
        JobConfig job = featureJob(tpl.resolve("config"));
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
            String sql = substitute(job.params().get("sql"), job.params());
            Path seed = tpl.resolve("data").resolve("tb_features").resolve("window_date=1970-01-01").resolve("seed.parquet");
            if (regenerate()) {
                Files.createDirectories(seed.getParent());
                st.execute("COPY (SELECT * EXCLUDE (window_date) FROM (" + sql + ") LIMIT 0) TO '"
                        + seed.toString().replace('\\', '/') + "' (FORMAT PARQUET)");
            }
            String seedRel = "read_parquet('" + seed.toString().replace('\\', '/') + "')";
            assertEquals(describe(st, "(SELECT * EXCLUDE (window_date), window_date FROM (" + sql + ") LIMIT 0)"), describe(st, seedRel),
                    "the tb_features seed drifted from the Job's SQL - regenerate with env TELCO_BYPASS_REGENERATE=true");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + seedRel)) {
                rs.next();
                assertEquals(0, rs.getLong(1));
            }
        }
        // the feed seeds equal what the Pipelines really write
        Path space = createSpace(root);
        BypassCorpus corpus = new BypassCorpus().generate();
        ingest(space, corpus.files(true));
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String feed : FEED_COLUMNS.keySet()) {
                Path db = space.resolve("data").resolve(feed).resolve("database");
                List<String> files = new ArrayList<>();
                try (Stream<Path> w = Files.walk(db)) {
                    for (Path p : w.filter(f -> f.toString().endsWith(".parquet") && !f.getFileName().toString().equals("schema-seed.parquet")).toList())
                        files.add("'" + p.toString().replace('\\', '/') + "'");
                }
                assertFalse(files.isEmpty(), feed + ": the Pipeline wrote no file");
                assertEquals(describe(st, "read_parquet([" + String.join(",", files) + "])"),
                        describe(st, "read_parquet('" + db.resolve("schema-seed.parquet").toString().replace('\\', '/') + "')"),
                        feed + ": the seed's Schema is not the Pipeline's output Schema");
            }
        }
    }

    // ------------------------------------------------------------------------------------------- the golden run

    /** Every signal S1 to S12 of every scored SIM equals an independent plain-Java computation over the corpus rows. */
    @Test
    void everySignalEqualsTheIndependentOracleForEveryScoredSim(@TempDir Path root) throws Exception {
        Run r = run(root, new BypassCorpus().generate().files(true), Map.of());
        BypassCorpus corpus = new BypassCorpus().generate();
        Map<String, Map<String, Object>> expected = oracle(corpus, r.params);
        assertEquals(expected.keySet(), r.rows.keySet(), "the scored SIMs");
        assertFalse(expected.isEmpty());
        for (String m : expected.keySet()) assertRow(m, expected.get(m), r.rows.get(m));
        for (String m : corpus.unscored) assertFalse(r.rows.containsKey(m), m + " is below min_events or roaming: no row");
        Set<String> scored = new TreeSet<>(corpus.allPlanted());
        scored.removeAll(corpus.unscored);
        assertEquals(scored, r.rows.keySet(), "every planted or look-alike SIM above the floor, and nothing else");
        assertEquals(35, r.rows.values().iterator().next().size(), "35 columns incl. window_date");
        System.out.println("BYPASS-B1 tb_features: " + r.rows.size() + " SIM rows from " + corpus.calls().size()
                + " CDR rows, Job " + r.jobMs + " ms");
        assertTrue(r.jobMs < 30_000, "the SIM-day Job on the small corpus took " + r.jobMs + " ms");
    }

    /** The planted SIMs and each clause's just-inside SIM are flagged; every look-alike and each AT-threshold SIM is silent. */
    @Test
    void boxLikeIsExactlyThePlantedSetAndNoLookAlike(@TempDir Path root) throws Exception {
        Run r = run(root, new BypassCorpus().generate().files(true), Map.of());
        BypassCorpus corpus = new BypassCorpus().generate();
        Set<String> flagged = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> e : r.rows.entrySet())
            if (Boolean.TRUE.equals(e.getValue().get("box_like"))) flagged.add(e.getKey());
        Set<String> expected = new TreeSet<>(corpus.boxLike);
        expected.addAll(corpus.ambiguous);
        assertEquals(expected, flagged, "box_like: planted + just-inside + the pinned ambiguous pair, nothing else");
        assertEquals(60 + 20 + 6, corpus.boxLike.size());
        // disjoint by construction: a look-alike is never planted
        Set<String> benign = new TreeSet<>(corpus.allPlanted());
        benign.removeAll(corpus.boxLike);
        benign.removeAll(corpus.ambiguous);
        for (String m : benign) assertFalse(flagged.contains(m), m + " is a look-alike and must stay silent");
        for (String name : List.of("L1", "L4", "L5", "L6", "L7", "P4", "P5", "P6", "BUSY")) {
            assertFalse(corpus.archetypes.get(name).isEmpty(), name);
            for (String m : corpus.archetypes.get(name)) assertFalse(flagged.contains(m), name + " " + m);
        }
        // the AT-threshold SIM of each clause is scored and silent; the INSIDE one is scored and flagged
        for (String clause : List.of("inbound", "b_diversity", "short_calls", "voice_only", "static_cell")) {
            String at = corpus.archetypes.get(BypassCorpus.atClause(clause)).iterator().next();
            String in = corpus.archetypes.get(BypassCorpus.insideClause(clause)).iterator().next();
            assertEquals(Boolean.FALSE, r.rows.get(at).get("box_like"), clause + " AT the threshold stays silent");
            assertEquals(Boolean.TRUE, r.rows.get(in).get("box_like"), clause + " just inside is flagged");
        }
        String floorOut = corpus.archetypes.get("AT_min_events").iterator().next();
        String floorIn = corpus.archetypes.get("INSIDE_min_events").iterator().next();
        assertFalse(r.rows.containsKey(floorOut), "19 calls: below min_events, no row");
        assertEquals(Boolean.TRUE, r.rows.get(floorIn).get("box_like"), "20 calls: scored and flagged");
        // P2 (the slow-roll box) is under the floor; the roamer is excluded: neither is a candidate in this slice
        for (String m : corpus.archetypes.get("P2")) assertFalse(r.rows.containsKey(m));
        for (String m : corpus.archetypes.get("L8")) assertFalse(r.rows.containsKey(m));
    }

    /** The exact planted values (from the construction, not from the oracle). */
    @Test
    void theArchetypesCarryTheirPlantedSignalValues(@TempDir Path root) throws Exception {
        Run r = run(root, new BypassCorpus().generate().files(true), Map.of());
        BypassCorpus corpus = new BypassCorpus().generate();
        for (String m : corpus.archetypes.get("P1")) {
            Map<String, Object> row = r.rows.get(m);
            assertEquals(90L, row.get("calls_mo"), m);
            assertEquals(90L, row.get("distinct_b"));
            assertEquals(1.0, row.get("b_diversity"));
            assertEquals(1.0, row.get("short_call_ratio"));
            assertEquals(0L, row.get("calls_mt"));
            assertEquals(0.0, row.get("inbound_ratio"));
            assertEquals(1.0, row.get("voice_only_share"));
            assertEquals(0L, row.get("sms_mo"));
            assertEquals(0L, row.get("data_s"));
            assertEquals(1L, row.get("distinct_cells"));
            assertEquals(1.0, row.get("top_cell_share"));
            assertNull(row.get("max_implied_kmh"), "one cell: no transition");
            assertEquals(24L, row.get("active_hours"), "round the clock");
            assertEquals(0L, row.get("overlap_calls"));
            assertEquals(1.0, row.get("onnet_target_share"));
            assertEquals(6L, row.get("tenure_days"));
            assertEquals("D01", row.get("dealer_id"));
            assertEquals(1.0, row.get("new_batch_share"), "all 60 SIMs of the batch are box-like");
            assertEquals(78.0 / 90.0, (Double) row.get("asr"), 1e-12, "every 7th attempt unanswered");
            assertEquals("", row.get("missing"));
            assertEquals(Boolean.TRUE, row.get("has_imei"));
            assertEquals(Boolean.TRUE, row.get("has_imsi"));
            assertTrue((Double) row.get("seq_dial_share") > 0.85, "numbers walked in order: " + row.get("seq_dial_share"));
            assertTrue((Double) row.get("gap_cv") < 0.05, "machine-regular cadence: " + row.get("gap_cv"));
            assertEquals(3, ((String) row.get("evidence_ids")).split(",").length);
        }
        for (String m : corpus.archetypes.get("P3")) assertEquals(0.8, r.rows.get(m).get("new_batch_share"), "20 of the 25 SIMs activated together call");
        String p4 = corpus.archetypes.get("P4").iterator().next();
        assertEquals(1.0, r.rows.get(p4).get("seq_dial_share"), "every consecutive pair is a +1 step");
        assertEquals(0.0, r.rows.get(p4).get("onnet_target_share"), "another national operator");
        assertEquals(0.0, r.rows.get(p4).get("short_call_ratio"));
        assertEquals("S12", r.rows.get(p4).get("missing"), "no subscriber profile row");
        String p5 = corpus.archetypes.get("P5").iterator().next();
        assertEquals(2L, r.rows.get(p5).get("distinct_cells"));
        assertEquals(2L, r.rows.get(p5).get("overlap_calls"), "two long calls overlap the next one");
        assertTrue((Double) r.rows.get(p5).get("max_implied_kmh") > 1000, "~800 km in 30 minutes: " + r.rows.get(p5).get("max_implied_kmh"));
        for (String m : corpus.archetypes.get("L5")) {
            assertEquals(12L, r.rows.get(m).get("distinct_cells"), "couriers move across 12 cells");
            assertEquals(5.0 / 60.0, (Double) r.rows.get(m).get("top_cell_share"), 1e-12);
            assertTrue((Double) r.rows.get(m).get("max_implied_kmh") < 120, "plausible speed: " + r.rows.get(m).get("max_implied_kmh"));
        }
        for (String m : corpus.archetypes.get("L1")) assertEquals(0.5, r.rows.get(m).get("inbound_ratio"), m);
        for (String m : corpus.archetypes.get("L6")) assertEquals(3L, r.rows.get(m).get("tenure_days"), "activated three days before the day");
        for (String m : corpus.archetypes.get("L7")) assertEquals(1L, r.rows.get(m).get("distinct_cells"));
        // each clause's AT and INSIDE SIM carry exactly the boundary value
        assertBoundary(corpus, r, "inbound", "inbound_ratio", 0.05, 0.04);
        assertBoundary(corpus, r, "b_diversity", "b_diversity", 0.9, 0.91);
        assertBoundary(corpus, r, "short_calls", "short_call_ratio", 0.9, 0.91);
        assertBoundary(corpus, r, "voice_only", "voice_only_share", 0.99, 0.995);
        assertBoundary(corpus, r, "static_cell", "top_cell_share", 0.9, 0.91);
    }

    private static void assertBoundary(BypassCorpus corpus, Run r, String clause, String column, double at, double inside) {
        assertEquals(at, r.rows.get(corpus.archetypes.get(BypassCorpus.atClause(clause)).iterator().next()).get(column), clause + " AT");
        assertEquals(inside, r.rows.get(corpus.archetypes.get(BypassCorpus.insideClause(clause)).iterator().next()).get(column), clause + " INSIDE");
    }

    // ------------------------------------------------------------------------------------------- degradation

    /**
     * A feed whose optional columns are BLANK, or absent from the file, and a Space with NO Reference loaded: the Job
     * still succeeds, every signal that does not need the missing input is unchanged, and each omitted signal is named.
     */
    @Test
    void missingOptionalColumnsAndReferencesAreFlaggedNotFailed(@TempDir Path root) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generate();
        Run base = run(root.resolve("base"), corpus.files(true), Map.of());

        for (Map.Entry<String, Map<String, String>> variant : Map.of("blank", corpus.files(false), "absent", corpus.filesRequiredOnly()).entrySet()) {
            Run r = run(root.resolve(variant.getKey()), variant.getValue(), Map.of());
            assertEquals(base.rows.keySet(), r.rows.keySet(), variant.getKey() + ": the same SIMs are scored");
            for (String m : base.rows.keySet()) {
                Map<String, Object> got = r.rows.get(m), want = base.rows.get(m);
                assertNull(got.get("asr"), variant.getKey() + " " + m);
                assertNull(got.get("failed_share"));
                assertEquals(Boolean.FALSE, got.get("has_imei"));
                assertEquals(Boolean.FALSE, got.get("has_imsi"));
                assertEquals(want.get("missing").toString().isEmpty() ? "S3" : "S3," + want.get("missing"), got.get("missing"), m);
                for (String col : want.keySet())
                    if (!Set.of("asr", "failed_share", "has_imei", "has_imsi", "missing").contains(col)) assertSame(col, want.get(col), got.get(col), m);
            }
        }

        // only the release cause, no answered flag: the answer rate is read from the cause, every column is unchanged
        Run cause = run(root.resolve("cause"), corpus.filesCauseOnly(), Map.of());
        assertEquals(base.rows.keySet(), cause.rows.keySet());
        for (String m : base.rows.keySet()) assertRow(m, base.rows.get(m), cause.rows.get(m));

        // no Reference loaded at all: only the voice feed is ingested, the three Reference stores hold just their seeds
        Map<String, String> voiceOnly = new LinkedHashMap<>(corpus.files(true));
        voiceOnly.keySet().removeIf(k -> REFERENCES.contains(k.substring(0, k.indexOf('/'))));
        Run bare = run(root.resolve("bare"), voiceOnly, Map.of());
        assertEquals(base.rows.keySet(), bare.rows.keySet());
        for (String m : base.rows.keySet()) {
            Map<String, Object> got = bare.rows.get(m), want = base.rows.get(m);
            assertNull(got.get("max_implied_kmh"), m);
            assertNull(got.get("onnet_target_share"));
            assertNull(got.get("tenure_days"));
            assertNull(got.get("dealer_id"));
            assertNull(got.get("new_batch_share"));
            assertEquals("S6,S11,S12", got.get("missing"), m);
            for (String col : want.keySet())
                if (!Set.of("max_implied_kmh", "onnet_target_share", "tenure_days", "dealer_id", "new_batch_share", "missing").contains(col))
                    assertSame(col, want.get(col), got.get(col), m);
        }
    }

    // ------------------------------------------------------------------------------------------- time zones, windows

    /** The statement gives identical rows under two host session zones; the explicit zone parameter moves the hour signals. */
    @Test
    void theRowsAreIdenticalUnderTwoHostZonesAndTheZoneParameterMovesTheHourSignals(@TempDir Path root) throws Exception {
        BypassCorpus corpus = new BypassCorpus().generate();
        Run r = run(root, corpus.files(true), Map.of());
        JobConfig job = r.job;
        Map<String, String> p = new HashMap<>(job.params());
        p.putAll(WINDOW1);
        DuckDbUtil.loadDriver();
        String sql = substitute(job.params().get("sql"), p);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            for (String feed : FEED_COLUMNS.keySet()) {
                List<String> files = new ArrayList<>();
                try (Stream<Path> w = Files.walk(r.space.resolve("data").resolve(feed).resolve("database"))) {
                    for (Path f : w.filter(x -> x.toString().endsWith(".parquet")).toList()) files.add("'" + f.toString().replace('\\', '/') + "'");
                }
                st.execute("CREATE VIEW " + feed + " AS SELECT * FROM read_parquet([" + String.join(",", files) + "], union_by_name = true)");
            }
            st.execute("SET TimeZone = 'Asia/Kolkata'");
            st.execute("CREATE TABLE zone_a AS " + sql);
            st.execute("SET TimeZone = 'America/Los_Angeles'");
            st.execute("CREATE TABLE zone_b AS " + sql);
            // float aggregates (the entropy sum, the gap deviation) may differ in the last digit with thread scheduling
            // whatever the zone, so rows are compared to 1e-9, not bit for bit
            Map<String, Map<String, Object>> a = readRows(st, "zone_a"), b = readRows(st, "zone_b");
            assertEquals(r.rows.size(), a.size());
            assertEquals(a.keySet(), b.keySet());
            for (String m : a.keySet()) assertRow(m, a.get(m), b.get(m));
            for (String m : a.keySet()) assertRow(m, r.rows.get(m), a.get(m));

            // local_zone = Asia/Kolkata (UTC+5:30): the hour profile shifts, every other column is unchanged
            Map<String, String> shifted = new HashMap<>(p);
            shifted.put("local_zone", "Asia/Kolkata");
            st.execute("CREATE TABLE zone_c AS " + substitute(job.params().get("sql"), shifted));
            Map<String, Map<String, Object>> expected = oracle(corpus, shifted);
            Map<String, Map<String, Object>> got = readRows(st, "zone_c");
            assertEquals(expected.keySet(), got.keySet());
            for (String m : expected.keySet()) assertRow(m, expected.get(m), got.get(m));
            String p6 = corpus.archetypes.get("P6").iterator().next();   // calls 03:00 to 05:00 UTC, i.e. 08:30 to 10:30 in Kolkata
            assertTrue((Double) r.rows.get(p6).get("night_share") > 0.9, "night in UTC: " + r.rows.get(p6).get("night_share"));
            assertEquals(0.0, got.get(p6).get("night_share"), "the same calls are not night in Kolkata");
            assertEquals(r.rows.get(p6).get("b_diversity"), got.get(p6).get("b_diversity"));
        }
    }

    /** A same-window re-run duplicates no row; the next window keeps the earlier one. */
    @Test
    void aSameWindowRerunDuplicatesNothingAndTheNextWindowKeepsTheEarlierOne(@TempDir Path root) throws Exception {
        Run r = run(root, new BypassCorpus().generate().files(true), Map.of());
        Path data = r.space.resolve("data");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(r.job), new ConsignmentEventBus(), s, null,
                     root.resolve("audit2").toString(), null, null, data.toString())) {
            js.start();
            assertEquals("SUCCESS", runJob(js, "tb_features", WINDOW1).status());
            assertEquals(r.rows.size(), sinkRows(data, "2026-07-01"), "a same-window re-run duplicates no row");
            assertEquals("SUCCESS", runJob(js, "tb_features", window(DAY1.plusDays(1))).status(), "an empty next window");
            assertEquals(r.rows.size(), sinkRows(data, "2026-07-01"), "day 1 survives the next window's run");
            assertEquals(0, sinkRows(data, "2026-07-02"));
            assertTrue(Files.exists(data.resolve("tb_features").resolve("window_date=1970-01-01").resolve("seed.parquet")), "the seed is kept");
        }
    }

    // ------------------------------------------------------------------------------------------- the oracle

    /** The independent oracle: S1 to S12 from the corpus rows, computed with plain loops, per the plan's definitions. */
    private static Map<String, Map<String, Object>> oracle(BypassCorpus corpus, Map<String, String> params) {
        int minEvents = Integer.parseInt(params.get("min_events"));
        int shortS = Integer.parseInt(params.get("short_s"));
        long seqStep = Long.parseLong(params.get("seq_step"));
        int seqPrefix = Integer.parseInt(params.get("seq_prefix"));
        int nightEnd = Integer.parseInt(params.get("night_end_hour"));
        ZoneId feed = ZoneId.of(params.get("feed_zone")), local = ZoneId.of(params.get("local_zone"));
        double maxInbound = Double.parseDouble(params.get("cand_max_inbound")), minBDiv = Double.parseDouble(params.get("cand_min_b_div")),
                minShort = Double.parseDouble(params.get("cand_min_short")), minVoice = Double.parseDouble(params.get("cand_min_voice_only")),
                minTop = Double.parseDouble(params.get("cand_min_top_cell"));

        Map<String, List<BypassCorpus.Call>> mo = new TreeMap<>();
        Map<String, List<BypassCorpus.Call>> events = new TreeMap<>();
        for (BypassCorpus.Call c : corpus.calls()) {
            if (c.ts().isBefore(DAY1.atStartOfDay()) || !c.ts().isBefore(DAY1.plusDays(1).atStartOfDay())) continue;   // the window is [day, day + 1)
            if (c.direction().equals("MO")) events.computeIfAbsent(c.a(), k -> new ArrayList<>()).add(c);
            else events.computeIfAbsent(c.b(), k -> new ArrayList<>()).add(c);
            if (c.direction().equals("MO") && c.service().equals("VOICE") && !c.roaming().equals("Y"))
                mo.computeIfAbsent(c.a(), k -> new ArrayList<>()).add(c);
        }
        List<String> ownPrefixes = new ArrayList<>();
        for (String line : BypassCorpus.numberPlan().split("\n")) {
            String[] f = line.split(",");
            if (f.length == 3 && f[2].equals("Y")) ownPrefixes.add(f[0]);
        }
        Map<String, BypassCorpus.Profile> profile = new HashMap<>();
        Map<String, Integer> batchSize = new HashMap<>();
        for (BypassCorpus.Profile p : corpus.profiles()) {
            profile.put(p.msisdn(), p);
            batchSize.merge(p.dealer() + "|" + p.activatedOn(), 1, Integer::sum);
        }

        Map<String, Map<String, Object>> out = new TreeMap<>();
        Map<String, Integer> batchCandidates = new HashMap<>();
        for (Map.Entry<String, List<BypassCorpus.Call>> e : mo.entrySet()) {
            String m = e.getKey();
            List<BypassCorpus.Call> cs = new ArrayList<>(e.getValue());
            cs.sort(java.util.Comparator.comparing(BypassCorpus.Call::ts).thenComparing(BypassCorpus.Call::id));
            int n = cs.size();
            if (n < minEvents) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("msisdn", m);
            row.put("calls_mo", (long) n);
            Set<String> distinct = new HashSet<>();
            for (BypassCorpus.Call c : cs) distinct.add(c.b());
            row.put("distinct_b", (long) distinct.size());
            row.put("b_diversity", (double) distinct.size() / n);

            List<Integer> answered = new ArrayList<>();
            int shortN = 0, okN = 0, okTotal = 0;
            for (BypassCorpus.Call c : cs) {
                if (c.dur() > 0) { answered.add(c.dur()); if (c.dur() <= shortS) shortN++; }
                String a = c.answered(), cause = c.cause();
                if (!a.isEmpty()) { okTotal++; if (a.equals("Y")) okN++; }
                else if (!cause.isEmpty()) { okTotal++; if (cause.equals("16")) okN++; }
            }
            double mean = answered.isEmpty() ? Double.NaN : answered.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
            double var = 0;
            for (int d : answered) var += (d - mean) * (d - mean);
            double std = answered.isEmpty() ? Double.NaN : Math.sqrt(var / answered.size());
            row.put("short_call_ratio", answered.isEmpty() ? null : (double) shortN / answered.size());
            row.put("acd_s", answered.isEmpty() ? null : mean);
            row.put("acd_cv", answered.isEmpty() || mean == 0 ? null : std / mean);
            row.put("asr", okTotal == 0 ? null : (double) okN / okTotal);
            row.put("failed_share", okTotal == 0 ? null : 1.0 - (double) okN / okTotal);

            long mt = 0, voice = 0, sms = 0, dataS = 0;
            List<BypassCorpus.Call> ev = events.get(m);
            for (BypassCorpus.Call c : ev) {
                if (c.service().equals("VOICE")) voice++;
                if (c.service().equals("VOICE") && c.direction().equals("MT")) mt++;
                if (c.service().equals("SMS") && c.direction().equals("MO")) sms++;
                if (c.service().equals("DATA")) dataS += c.dur();
            }
            row.put("calls_mt", mt);
            row.put("inbound_ratio", (double) mt / (mt + n));
            row.put("voice_only_share", (double) voice / ev.size());
            row.put("sms_mo", sms);
            row.put("data_s", dataS);

            Map<String, Integer> perCell = new HashMap<>();
            int withSite = 0;
            for (BypassCorpus.Call c : cs) {
                perCell.merge(c.cell(), 1, Integer::sum);
                if (corpus.cells().containsKey(c.cell())) withSite++;
            }
            row.put("distinct_cells", (long) perCell.size());
            row.put("top_cell_share", (double) perCell.values().stream().mapToInt(Integer::intValue).max().orElse(0) / n);

            Double kmh = null;
            int seq = 0, pairs = 0, overlap = 0;
            List<Double> gaps = new ArrayList<>();
            java.time.LocalDateTime maxEnd = null;
            for (int i = 0; i < n; i++) {
                BypassCorpus.Call c = cs.get(i);
                if (i > 0) {
                    BypassCorpus.Call p = cs.get(i - 1);
                    pairs++;
                    long gap = Duration.between(p.ts(), c.ts()).getSeconds();
                    gaps.add((double) gap);
                    if (isSequential(p.b(), c.b(), seqPrefix, seqStep)) seq++;
                    BypassCorpus.Cell a = corpus.cells().get(p.cell()), b = corpus.cells().get(c.cell());
                    if (!p.cell().equals(c.cell()) && a != null && b != null) {
                        double v = haversine(a.lat(), a.lon(), b.lat(), b.lon()) / (Math.max(gap, 1) / 3600.0);
                        kmh = kmh == null ? v : Math.max(kmh, v);
                    }
                    if (maxEnd != null && c.ts().isBefore(maxEnd)) overlap++;
                }
                java.time.LocalDateTime end = c.ts().plusSeconds(c.dur());
                maxEnd = maxEnd == null || end.isAfter(maxEnd) ? end : maxEnd;
            }
            row.put("max_implied_kmh", withSite == 0 ? null : kmh);
            row.put("seq_dial_share", pairs == 0 ? null : (double) seq / pairs);
            row.put("overlap_calls", (long) overlap);

            Map<Integer, Integer> perHour = new TreeMap<>();
            Map<Integer, Set<String>> perHourB = new TreeMap<>();
            int night = 0;
            for (BypassCorpus.Call c : cs) {
                int h = c.ts().atZone(feed).withZoneSameInstant(local).getHour();
                perHour.merge(h, 1, Integer::sum);
                perHourB.computeIfAbsent(h, k -> new HashSet<>()).add(c.b());
                if (h < nightEnd) night++;
            }
            double entropy = 0;
            for (int cnt : perHour.values()) { double pr = (double) cnt / n; entropy -= pr * Math.log(pr); }
            row.put("active_hours", (long) perHour.size());
            row.put("night_share", (double) night / n);
            row.put("hour_entropy", entropy / Math.log(24));
            row.put("max_calls_1h", (long) perHour.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            row.put("max_distinct_b_1h", (long) perHourB.values().stream().mapToInt(Set::size).max().orElse(0));
            double gapMean = gaps.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double gv = 0;
            for (double g : gaps) gv += (g - gapMean) * (g - gapMean);
            row.put("gap_cv", gaps.isEmpty() || gapMean == 0 ? null : Math.sqrt(gv / gaps.size()) / gapMean);

            int on = 0;
            for (BypassCorpus.Call c : cs) for (String pre : ownPrefixes) if (c.b().startsWith(pre)) { on++; break; }
            row.put("onnet_target_share", (double) on / n);

            BypassCorpus.Profile pr = profile.get(m);
            row.put("tenure_days", pr == null ? null : (Object) java.time.temporal.ChronoUnit.DAYS.between(pr.activatedOn(), DAY1));
            row.put("dealer_id", pr == null ? null : pr.dealer());

            boolean boxLike = (double) mt / (mt + n) < maxInbound && (double) distinct.size() / n > minBDiv
                    && !answered.isEmpty() && (double) shortN / answered.size() > minShort
                    && (double) voice / ev.size() > minVoice
                    && (double) perCell.values().stream().mapToInt(Integer::intValue).max().orElse(0) / n > minTop;
            row.put("box_like", boxLike);
            if (pr != null && boxLike) batchCandidates.merge(pr.dealer() + "|" + pr.activatedOn(), 1, Integer::sum);

            List<String> missing = new ArrayList<>();
            if (okTotal == 0) missing.add("S3");
            if (withSite == 0) missing.add("S6");
            if (pr == null) missing.add("S12");
            row.put("missing", String.join(",", missing));
            boolean imei = false, imsi = false;
            for (BypassCorpus.Call c : cs) { if (!c.imei().isEmpty()) imei = true; if (!c.imsi().isEmpty()) imsi = true; }
            row.put("has_imei", imei);
            row.put("has_imsi", imsi);
            List<BypassCorpus.Call> byDur = new ArrayList<>(cs);
            byDur.removeIf(c -> c.dur() <= 0);
            byDur.sort(java.util.Comparator.comparingInt(BypassCorpus.Call::dur).thenComparing(BypassCorpus.Call::id));
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < Math.min(3, byDur.size()); i++) ids.add(byDur.get(i).id());
            row.put("evidence_ids", String.join(",", ids));
            row.put("window_date", java.sql.Date.valueOf(DAY1));
            out.put(m, row);
        }
        for (Map<String, Object> row : out.values()) {
            BypassCorpus.Profile pr = profile.get((String) row.get("msisdn"));
            row.put("new_batch_share", pr == null ? null
                    : (Object) ((double) batchCandidates.getOrDefault(pr.dealer() + "|" + pr.activatedOn(), 0) / batchSize.get(pr.dealer() + "|" + pr.activatedOn())));
        }
        return out;
    }

    private static boolean isSequential(String prev, String cur, int prefixLen, long step) {
        if (prev.length() < prefixLen || cur.length() < prefixLen || !prev.substring(0, prefixLen).equals(cur.substring(0, prefixLen))) return false;
        try {
            long d = Math.abs(Long.parseLong(cur) - Long.parseLong(prev));
            return d >= 1 && d <= step;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.pow(Math.sin(Math.toRadians(lat1 - lat2) / 2), 2)
                + Math.cos(Math.toRadians(lat2)) * Math.cos(Math.toRadians(lat1)) * Math.pow(Math.sin(Math.toRadians(lon1 - lon2) / 2), 2);
        return 2 * 6371 * Math.asin(Math.sqrt(a));
    }

    private static void assertRow(String msisdn, Map<String, Object> want, Map<String, Object> got) {
        assertEquals(want.keySet(), got.keySet(), msisdn + ": column set");
        for (String col : want.keySet()) assertSame(col, want.get(col), got.get(col), msisdn);
    }

    /** Equal values: numbers within 1e-9 (relative), everything else by equals; null only equals null. */
    private static void assertSame(String col, Object want, Object got, String msisdn) {
        String at = msisdn + "." + col + " want " + want + " got " + got;
        if (want == null || got == null) { assertNull(want, at); assertNull(got, at); return; }
        if (want instanceof Number w && got instanceof Number g) {
            double a = w.doubleValue(), b = g.doubleValue();
            assertTrue(Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a)), at);
        } else assertEquals(want.toString(), got.toString(), at);
    }

    // ------------------------------------------------------------------------------------------- the run

    private record Run(Path space, JobConfig job, Map<String, String> params, Map<String, Map<String, Object>> rows, long jobMs) {}

    /** Create the Space, ingest the given files through the template's Pipelines, run the Job for day 1, read its rows. */
    private static Run run(Path root, Map<String, String> files, Map<String, String> overrides) throws Exception {
        Files.createDirectories(root);
        Path space = createSpace(root);
        ingest(space, files);
        JobConfig job = featureJob(space.resolve("config"));
        Map<String, String> args = new HashMap<>(WINDOW1);
        args.putAll(overrides);
        Map<String, String> params = new HashMap<>(job.params());
        params.putAll(args);
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     root.resolve("audit").toString(), null, null, space.resolve("data").toString())) {
            js.start();
            JobRun jr = runJob(js, "tb_features", args);
            assertEquals("SUCCESS", jr.status(), jr.message());
            return new Run(space, job, params, readSink(space), jr.durationMs());
        }
    }

    private static Map<String, Map<String, Object>> readSink(Path space) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW sink AS SELECT * FROM read_parquet('" + space.resolve("data").resolve("tb_features").toString().replace('\\', '/')
                    + "/**/*.parquet', hive_partitioning = true) WHERE window_date = DATE '2026-07-01'");
            return readRows(st, "sink");
        }
    }

    private static Map<String, Map<String, Object>> readRows(Statement st, String relation) throws Exception {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        try (ResultSet rs = st.executeQuery("SELECT * FROM " + relation)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                assertNull(out.put((String) row.get("msisdn"), row), "one row per SIM per day: " + row.get("msisdn"));
            }
        }
        return out;
    }

    private static long sinkRows(Path data, String day) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM read_parquet('" + data.resolve("tb_features").toString().replace('\\', '/')
                     + "/**/*.parquet', hive_partitioning = true) WHERE window_date = DATE '" + day + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private static Path createSpace(Path root) throws Exception {
        copyTree(template(), root.resolve("_templates").resolve("telco-bypass"));
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        try {
            api.start();
            HttpResponse<String> created = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + api.port() + "/api/v1/spaces"))
                    .header("Content-Type", "application/json").header("Authorization", "Bearer admin")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"id\":\"bypass\",\"template\":\"telco-bypass\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode(), created.body());
        } finally {
            api.close();
            spaces.close();
        }
        return root.resolve("bypass");
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

    private static JobConfig featureJob(Path config) throws Exception {
        return jobs(config).stream().filter(j -> j.name().equals("tb_features")).findFirst().orElseThrow();
    }

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

    /** {@code $name} to quoted literal, longest name first (as the Job framework substitutes). */
    private static String substitute(String sql, Map<String, String> params) {
        List<String> names = new ArrayList<>(params.keySet());
        names.sort((a, b) -> b.length() - a.length());
        Map<String, String> window = new HashMap<>(WINDOW1);
        for (String n : names) {
            String v = params.get(n);
            if (v.startsWith("$")) v = window.getOrDefault(n, v);   // the rolling $yesterday / $today tokens: pin day 1
            sql = sql.replace("$" + n, "'" + v.replace("'", "''") + "'");
        }
        return sql;
    }

    private static String emptyCsv(String columns) throws Exception {
        Path f = Files.createTempFile("telco-bypass-empty", ".csv");
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
}
