package com.gamma.job;

import com.gamma.alert.AlertRule;
import com.gamma.alert.AlertService;
import com.gamma.catalog.ConfigSource;
import com.gamma.catalog.SemanticModel;
import com.gamma.enrich.EnrichmentConfig;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.StatusStore;
import com.gamma.objects.FakeObjectAccess;
import com.gamma.pipeline.ComponentStore;
import com.gamma.query.DatasetMeasureProbe;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code la-showcase} Space Template's TELECOM corpus RUN end to end from a verbatim copy: the eight telecom
 * Pipelines ingest the shipped extracts ({@code data/inbox/<feed>}, written by {@code tools/gen-la-telecom-data.py}),
 * then the two {@code sql.template} Jobs build {@code telecom_links} and {@code telecom_msisdn_indicators}. Pins the
 * demo promises of {@code docs/superpower/la-telecom-demo-data.md}: every link kind present, each planted ring present
 * as documented, the degree ladder 1..4 from the suspect, the supernode, the pair cap, and the planted Wangiri / IRSF /
 * SIM-box numbers standing out on the indicator columns.
 */
class TelecomLinksGoldenTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "la-showcase").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("voice_cdr", "sms_cdr", "hlr_eir", "crm_kyc", "recharge", "provisioning",
            "fraud_blocklist", "fraud_alarms");
    private static final List<String> JOBS = List.of("telecom_links", "telecom_msisdn_indicators", "telecom_typology_counts");

    static final String SUSPECT = "99979100001", WANGIRI_B = "99979100002", PREMIUM_01 = "99891000001", HUB = "99970000100";
    static final List<String> IRSF = List.of("99979200001", "99979200002", "99979200003", "99979200004");
    static final List<String> SUBFRAUD = List.of("99979300001", "99979300002", "99979300003", "99979300004", "99979300005");
    static final List<String> SIMBOX = List.of("99979400001", "99979400002", "99979400003", "99979400004", "99979400005", "99979400006");
    static final List<String> MULES = List.of("99979500001", "99979500002", "99979500003", "99979500004", "99979500005");

    @TempDir static Path tmp;
    private static Path data, space;
    private static List<Map<String, Object>> links;
    private static Map<String, Map<String, Object>> indicators;

    @BeforeAll
    static void runThePipelinesAndJobs() throws Exception {
        space = tmp.resolve("spaces").resolve("la-showcase");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS) {
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/telecom/" + feed + "_pipeline.toon").toString());
            CollectorProcessor.run(pc);
            assertFalse(hasFiles(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
        }
        data = space.resolve("data");
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            for (String j : JOBS) {
                assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                JobRun r = null;
                long deadline = System.nanoTime() + 60_000_000_000L;
                while ((r = js.lastRunOf(j).orElse(null)) == null && System.nanoTime() < deadline) Thread.sleep(50);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
        links = rows("SELECT * FROM " + parquet("telecom_links"));
        indicators = new HashMap<>();
        for (Map<String, Object> r : rows("SELECT * FROM " + parquet("telecom_msisdn_indicators")))
            indicators.put((String) r.get("msisdn"), r);
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndEveryLinkKind() throws Exception {
        assertEquals(List.of("a_msisdn", "b_msisdn", "link_kind", "first_seen", "last_seen", "events", "total_duration_s", "via",
                "avg_duration_s"), new ArrayList<>(links.get(0).keySet()));
        Map<String, Integer> perKind = new TreeMap<>();
        for (Map<String, Object> l : links) perKind.merge((String) l.get("link_kind"), 1, Integer::sum);
        assertEquals(Set.of("voice", "sms", "forwarding", "shared_device", "shared_identity", "sim_history", "shared_payment"),
                perKind.keySet(), "exactly the seven link kinds");
        perKind.forEach((k, n) -> assertTrue(n > 0, k));
        Set<String> pairs = new HashSet<>();
        for (Map<String, Object> l : links)
            assertTrue(pairs.add(l.get("a_msisdn") + "|" + l.get("b_msisdn") + "|" + l.get("link_kind")), "one row per ordered pair and kind: " + l);
    }

    @Test
    void theSharedKindsAreEmittedInBothDirections() {
        Set<String> kinds = Set.of("shared_device", "shared_identity", "shared_payment", "sim_history");
        for (Map<String, Object> l : links) {
            if (!kinds.contains((String) l.get("link_kind"))) continue;
            Map<String, Object> back = link((String) l.get("b_msisdn"), (String) l.get("a_msisdn"), (String) l.get("link_kind"));
            assertEquals(l.get("via"), back.get("via"), "the mirror row carries the same key: " + l);
            assertEquals(l.get("events"), back.get("events"));
        }
    }

    @Test
    void simHistoryComesOnlyFromRealTransfers() {
        for (Map<String, Object> l : links)
            if (l.get("link_kind").equals("sim_history"))
                assertFalse(((String) l.get("a_msisdn")).equals(l.get("b_msisdn")), String.valueOf(l));
        // a customer holding two lines (no transfer) is shared_identity only, never sim_history
        long twoLineOnly = links.stream().filter(l -> l.get("link_kind").equals("shared_identity")).filter(l ->
                find((String) l.get("a_msisdn"), (String) l.get("b_msisdn"), "sim_history") == null).count();
        assertTrue(twoLineOnly > 0);
        assertNull(find(SIMBOX.get(0), SIMBOX.get(0), "sim_history"));
        assertEquals("subscriber:CUST-M01", link(SUBFRAUD.get(3), MULES.get(0), "sim_history").get("via"));
    }

    @Test
    void eachPlantedRingIsPresentAsDocumented() {
        assertEquals("cdr|hlr", link(SUSPECT, PREMIUM_01, "forwarding").get("via"), "Wangiri callbacks forwarded to premium");
        assertTrue(((Number) link(SUSPECT, PREMIUM_01, "forwarding").get("events")).longValue() >= 40);
        assertTrue(String.valueOf(link(SUSPECT, WANGIRI_B, "shared_device").get("via")).startsWith("imei:"), "the twin SIM");
        for (String m : IRSF.subList(0, 2)) assertNotNull(link(m, PREMIUM_01, "voice"), "IRSF pumps the premium range");
        for (String m : SUBFRAUD)
            assertTrue(String.valueOf(link(IRSF.get(0), m, "shared_identity").get("via")).contains("id_doc:"), "one identity: " + m);
        assertTrue(component(SIMBOX.get(0), "shared_device").containsAll(SIMBOX), "the six SIM-box SIMs share one gateway");
        assertTrue(String.valueOf(link(SUBFRAUD.get(2), SIMBOX.get(0), "shared_payment").get("via")).startsWith("card:"), "the d3 -> d4 card");
        assertEquals("cdr|hlr", link(MULES.get(0), MULES.get(1), "forwarding").get("via"), "forwarding chain A -> B");
        assertEquals("cdr|hlr", link(MULES.get(1), MULES.get(2), "forwarding").get("via"), "forwarding chain B -> C");
        for (String m : MULES.subList(1, 5))
            assertTrue(String.valueOf(link(MULES.get(0), m, "shared_payment").get("via")).contains("agent:AGT-666"), "rogue agent: " + m);
        assertTrue(String.valueOf(link(MULES.get(0), MULES.get(1), "shared_payment").get("via")).contains("voucher:VMULE00000A1"));
        assertEquals("subscriber:CUST-M01", link(SIMBOX.get(5), MULES.get(0), "sim_history").get("via"), "the mule ring's SIM history");
    }

    @Test
    void theHubIsASupernodeAndThePairCapDropsCommonKeys() {
        Set<String> callers = new HashSet<>();
        for (Map<String, Object> l : links)
            if (l.get("link_kind").equals("voice") && l.get("b_msisdn").equals(HUB)) callers.add((String) l.get("a_msisdn"));
        assertTrue(callers.size() > 500, "the hub has " + callers.size() + " distinct callers");
        for (Map<String, Object> l : links) {
            String via = String.valueOf(l.get("via"));
            assertFalse(via.matches(".*agent:AGT-0\\d\\d.*"), "a retail agent serving ~60 lines makes no pair: " + l);
        }
        long devicePairs = links.stream().filter(l -> l.get("link_kind").equals("shared_device")).count();
        assertTrue(devicePairs <= 40, "only the planted handsets are shared (both directions): " + devicePairs);
    }

    /** The demo expands degree by degree with the HUB flagged high-connectivity and NOT expanded (hubThreshold 500). */
    @Test
    void theSuspectReachesTheRingsDegreeByDegreeWithTheHubNotExpanded() {
        Map<String, Integer> d = distancesFrom(SUSPECT, HUB);
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put(WANGIRI_B, 1);
        expected.put(PREMIUM_01, 1);
        expected.put(HUB, 2);
        expected.put(IRSF.get(0), 2);
        for (String m : SUBFRAUD) expected.put(m, 3);
        expected.put(SIMBOX.get(0), 4);
        expected.put(SIMBOX.get(5), 4);
        for (String m : MULES) expected.put(m, 4);
        Map<String, Integer> actual = new LinkedHashMap<>();
        expected.keySet().forEach(m -> actual.put(m, d.get(m)));
        assertEquals(expected, actual, "shortest distances from the suspect " + SUSPECT + ", hub not expanded");
        // the intended path, hop by hop
        assertNotNull(link(SUSPECT, PREMIUM_01, "forwarding"));
        assertNotNull(link(IRSF.get(0), PREMIUM_01, "voice"));
        assertNotNull(link(IRSF.get(0), SUBFRAUD.get(2), "shared_identity"));
        assertTrue(String.valueOf(link(SUBFRAUD.get(2), SIMBOX.get(0), "shared_payment").get("via")).startsWith("card:"));
        assertTrue(String.valueOf(link(SUBFRAUD.get(4), MULES.get(1), "shared_payment").get("via")).contains("agent:AGT-666"));
    }

    @Test
    void theIndicatorsMakeEachTypologyStandOut() {
        Map<String, Object> any = indicators.get(SUSPECT);
        assertEquals(List.of("msisdn", "indicator_score", "on_blocklist", "alarms_recent", "recent_activation_high_intl",
                "shares_imei_with_flagged", "premium_destination_share", "short_call_ratio", "one_way_ratio", "out_distinct_b",
                "inbound_distinct_b", "avg_dur_s", "out_in_ratio", "premium_share", "imei_per_msisdn", "msisdn_per_imei",
                "activated_at"), new ArrayList<>(any.keySet()));
        assertEquals(2423, indicators.size(), "one row per crm_kyc MSISDN");

        // Wangiri: wide fan-out of near-zero calls, nothing comes back
        assertEquals(Set.of(SUSPECT, WANGIRI_B), select(r -> num(r, "out_distinct_b") >= 50 && num(r, "avg_dur_s") < 5));
        assertEquals(1.0, num(indicators.get(SUSPECT), "short_call_ratio"));
        assertEquals(0.0, num(indicators.get(SUSPECT), "inbound_distinct_b"));
        // IRSF: (almost) all traffic to the premium range, long calls
        assertEquals(new TreeSet<>(IRSF), select(r -> num(r, "premium_destination_share") >= 0.8 && num(r, "avg_dur_s") >= 600));
        // SIM box: many IMEIs per SIM, many SIMs per IMEI, outbound only
        assertEquals(new TreeSet<>(SIMBOX), select(r -> num(r, "imei_per_msisdn") >= 3 && num(r, "msisdn_per_imei") >= 3
                && num(r, "out_in_ratio") >= 50));
        // subscription fraud (and the IRSF SIMs bought on the same identity): recent activation, high international
        assertEquals(new TreeSet<>(union(IRSF, SUBFRAUD)),
                select(r -> Boolean.TRUE.equals(r.get("recent_activation_high_intl"))));

        for (String m : union(List.of(SUSPECT, WANGIRI_B), union(IRSF, union(SUBFRAUD, SIMBOX))))
            assertTrue(num(indicators.get(m), "indicator_score") >= 25, m + " scores " + indicators.get(m).get("indicator_score"));
        Set<String> loudBackground = select(r -> ((String) r.get("msisdn")).startsWith("99971") && num(r, "indicator_score") >= 15);
        assertEquals(Set.of("99971002000"), loudBackground, "only the stale block-list entry is loud in the background");
    }

    /**
     * The three indicator Alert Rules (Wangiri, IRSF, SIM box) the pattern stages cannot express load from the template and
     * fire on the seed through the production {@link AlertService} and {@link DatasetMeasureProbe}. They read the ONE-row
     * {@code telecom_typology_counts} Dataset, so each Alert carries a count, never an MSISDN (aggregate-only alerts).
     */
    @Test
    void theTypologyAlertRulesFireOnTheSeedWithCountsOnly() throws Exception {
        List<Map<String, Object>> counts = rows("SELECT * FROM " + parquet("telecom_typology_counts"));
        assertEquals(1, counts.size(), "one row of counts");
        assertEquals(List.of("wangiri_lines", "irsf_lines", "simbox_lines", "scored_lines"), new ArrayList<>(counts.get(0).keySet()),
                "counts only - no MSISDN column");
        assertEquals(2L, ((Number) counts.get(0).get("wangiri_lines")).longValue());
        assertEquals(4L, ((Number) counts.get(0).get("irsf_lines")).longValue());
        assertEquals(6L, ((Number) counts.get(0).get("simbox_lines")).longValue());
        assertEquals((long) indicators.size(), ((Number) counts.get(0).get("scored_lines")).longValue());

        Path cfg = space.resolve("config");
        JobConfig counter = JobConfig.load(cfg.resolve("jobs/telecom_typology_counts_job.toon").toString());
        assertEquals("job.dataset.produced", counter.onSignal(), "runs after the indicators, never on its own timer");
        assertEquals("$signal.dataset == telecom_msisdn_indicators", counter.when());
        assertTrue(counter.cron() == null || counter.cron().isBlank(), "no cron: it must never read a missing or older snapshot");
        List<AlertRule> rules = new ComponentStore(cfg.resolve("registry")).list("alert-rule").stream()
                .map(c -> AlertRule.fromMap(c.content())).toList();
        assertEquals(Set.of("telecom_wangiri_lines", "telecom_irsf_lines", "telecom_simbox_lines"),
                new TreeSet<>(rules.stream().map(AlertRule::name).toList()));
        for (AlertRule r : rules) {
            assertTrue(r.isMeasureRule(), r.name());
            assertFalse(r.isGrouped(), r.name() + " is aggregate-only: no by, so no MSISDN in the Alert");
        }
        FakeObjectAccess objects = new FakeObjectAccess();
        AlertService svc = new AlertService(rules, noPipelines(), emptyStore(), objects);
        DatasetMeasureProbe probe = new DatasetMeasureProbe(() -> cfg, () -> data);
        svc.measureProbe(probe::value);
        Map<String, String> fired = new TreeMap<>();
        for (com.gamma.alert.Alert a : svc.evaluateRules()) {
            fired.put(a.rule(), String.valueOf(a.value()));
            assertFalse((a.message() + a.evidence()).contains("99979"), "no planted MSISDN in the Alert: " + a);
        }
        for (FakeObjectAccess.Opened o : objects.opened) {   // the CRITICAL rules also open an Incident: still counts only
            assertFalse(o.attributes().keySet().stream().anyMatch(k -> k.startsWith("key")), "no entity key: " + o.attributes());
            assertFalse(String.valueOf(o.attributes()).contains("99979"), "no planted MSISDN in the Incident: " + o.attributes());
        }
        assertEquals(Map.of("telecom_irsf_lines", "4.0", "telecom_simbox_lines", "6.0", "telecom_wangiri_lines", "2.0"), fired);
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

    @Test
    void theScoreIsTheDocumentedWeightedSum() {
        for (Map<String, Object> r : indicators.values()) {
            boolean busy = outEvents(r) >= 10;   // the Job's min_calls
            double expected = 25 * flag(r, "on_blocklist") + 15 * Math.min(num(r, "alarms_recent"), 3) / 3
                    + 15 * flag(r, "recent_activation_high_intl") + 15 * flag(r, "shares_imei_with_flagged")
                    + (busy ? 10 * (num(r, "premium_destination_share") + num(r, "short_call_ratio") + num(r, "one_way_ratio")) : 0);
            assertEquals(Math.round(expected * 10) / 10.0, num(r, "indicator_score"), 0.051, String.valueOf(r));
            assertTrue(num(r, "indicator_score") >= 0 && num(r, "indicator_score") <= 100);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private static final Map<String, Long> OUT_EVENTS = new HashMap<>();

    /** Outbound voice events per MSISDN, from the links (the gate the score's ratio terms need). */
    private static long outEvents(Map<String, Object> r) {
        if (OUT_EVENTS.isEmpty())
            for (Map<String, Object> l : links)
                if (l.get("link_kind").equals("voice"))
                    OUT_EVENTS.merge((String) l.get("a_msisdn"), ((Number) l.get("events")).longValue(), Long::sum);
        return OUT_EVENTS.getOrDefault((String) r.get("msisdn"), 0L);
    }

    private static List<String> union(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static double flag(Map<String, Object> r, String c) { return Boolean.TRUE.equals(r.get(c)) ? 1 : 0; }

    private static double num(Map<String, Object> r, String c) { return ((Number) r.get(c)).doubleValue(); }

    private static Set<String> select(java.util.function.Predicate<Map<String, Object>> p) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> r : indicators.values()) if (p.test(r)) out.add((String) r.get("msisdn"));
        return out;
    }

    private static Map<String, Object> find(String a, String b, String kind) {
        for (Map<String, Object> l : links)
            if (l.get("link_kind").equals(kind) && l.get("a_msisdn").equals(a) && l.get("b_msisdn").equals(b)) return l;
        return null;
    }

    private static Map<String, Object> link(String a, String b, String kind) {
        Map<String, Object> l = find(a, b, kind);
        assertNotNull(l, "no " + kind + " link " + a + " -> " + b);
        return l;
    }

    private static Map<String, Set<String>> adjacency(String kindOrNull) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (Map<String, Object> l : links) {
            if (kindOrNull != null && !l.get("link_kind").equals(kindOrNull)) continue;
            String a = (String) l.get("a_msisdn"), b = (String) l.get("b_msisdn");
            adj.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adj.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }
        return adj;
    }

    private static Map<String, Integer> bfs(Map<String, Set<String>> adj, String from, String... notExpanded) {
        Map<String, Integer> d = new HashMap<>(Map.of(from, 0));
        ArrayDeque<String> q = new ArrayDeque<>(List.of(from));
        Set<String> stop = Set.of(notExpanded);
        while (!q.isEmpty()) {
            String x = q.poll();
            if (stop.contains(x)) continue;   // reached and shown, never expanded
            for (String y : adj.getOrDefault(x, Set.of()))
                if (d.putIfAbsent(y, d.get(x) + 1) == null) q.add(y);
        }
        return d;
    }

    /** Shortest undirected hop counts over every link kind - how Link Analysis expands degree by degree. */
    private static Map<String, Integer> distancesFrom(String m, String... notExpanded) { return bfs(adjacency(null), m, notExpanded); }

    private static Set<String> component(String m, String kind) { return bfs(adjacency(kind), m).keySet(); }

    private static String parquet(String sink) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + "/*.parquet')";
    }

    private static List<Map<String, Object>> rows(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) r.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                out.add(r);
            }
        }
        assertFalse(out.isEmpty(), "no rows: " + sql);
        return out;
    }

    private static boolean hasFiles(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return false;
        try (Stream<Path> w = Files.walk(dir)) {
            return w.anyMatch(p -> Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."));
        }
    }
}
