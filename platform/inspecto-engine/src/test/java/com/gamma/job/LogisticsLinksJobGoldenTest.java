package com.gamma.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-logistics} Space Template (Basic tier) RUN end to end from a verbatim copy: the eight Pipelines land the shipped
 * extracts with nothing quarantined, the {@code logistics_links} {@code sql.template} Job folds them, and the derived
 * {@code logistics_screen_links} Job builds the three cargo-security screens the saved views read. Over the REAL link Datasets it
 * pins the generator's ground truth ({@code tools/gen-la-logistics-data.ground-truth.json}): every story's entities form one connected group,
 * no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole, shows the
 * look-alike beside it and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts behind each story in the files
 * themselves are pinned by {@code tools/gen-la-logistics-data.test.mjs}.
 */
class LogisticsLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-logistics");
    private static final List<String> FEEDS = List.of("carrier", "driver", "place", "shipment", "trip", "stop", "warehouse_scan", "loss_report");
    private static final List<String> JOBS = List.of("logistics_links", "logistics_screen_links");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String bl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-logistics");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS) {
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/" + feed + "/" + feed + "_pipeline.toon").toString());
            CollectorProcessor.run(pc);
            assertFalse(hasFiles(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
            assertFalse(hasFiles(Path.of(pc.dirs().errors())), feed + ": nothing in the corpus is an error");
        }
        data = space.resolve("data");
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        // the built-in Job Types that declare these grants must validate at registration; this test sends nothing
        for (Map.Entry<String, String> e : Map.of("mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "datasets", "com.gamma.query.DatasetAccess",
                "schema", "com.gamma.pipeline.SchemaAccess", "link-index", "com.gamma.linkindex.LinkIndexAccess",
                "alerts", "com.gamma.alert.AlertAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform)) {
            js.eventLog(com.gamma.event.EventLog.current());
            js.start();
            for (String j : JOBS) {
                // the derived Job may already be running off the main Job's produced signal; a trigger then is a no-op
                if (j.equals(JOBS.get(0))) assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                else js.triggerRun(j, null, Map.of());
                JobRun r = awaitRun(js, j);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-logistics-data.ground-truth.json").toFile());
        bl = parquet("logistics_links");
        links = triples("SELECT src, dst, kind FROM " + bl);
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (wave D)");
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/logistics_links_job.toon").toString()).cron(),
                "a commit landing while the Job runs is skipped (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches it up");
        assertTrue(Files.isRegularFile(TEMPLATE.resolve("config/examples/sla-escalation-examples.md")));
    }

    @Test
    void everyLandedRowIsInItsDatasetAndTheStagedMonthIsNotLanded() throws Exception {
        for (String feed : FEEDS) {
            long csv = 0;
            try (Stream<Path> w = Files.list(TEMPLATE.resolve("data/inbox").resolve(feed))) {
                for (Path f : w.toList()) csv += Files.readAllLines(f).size() - 1;
            }
            assertEquals(csv, count("SELECT count(*) FROM " + parquet(feed + "/database", "/**/*.parquet")), feed);
        }
        assertEquals(truth.get("counts").get("trip_landed").asLong(), count("SELECT count(*) FROM " + parquet("trip/database", "/**/*.parquet")));
        assertEquals(truth.get("counts").get("warehouse_scan_landed").asLong(), count("SELECT count(*) FROM " + parquet("warehouse_scan/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("trip/database", "/**/*.parquet") + " WHERE CAST(depart_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("loss_report/database", "/**/*.parquet") + " WHERE CAST(reported_at AS DATE) >= DATE '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSevenKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + bl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + bl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("carried_by", "co_drove", "drove", "lost", "same_phone", "scanned_by", "stopped_at"), new ArrayList<>(perKind.keySet()));
        long trips = truth.get("counts").get("trip_landed").asLong();
        assertEquals(trips, perKind.get("drove"), "one driver per landed trip");
        assertEquals(trips, perKind.get("carried_by"), "one carrier per landed trip");
        assertEquals(trips, perKind.get("scanned_by"), "one outbound scan per landed trip");
        assertEquals(truth.get("counts").get("co_driven_landed").asLong(), perKind.get("co_drove"));
        assertEquals(truth.get("counts").get("long_stop_pairs_landed").asLong(), perKind.get("stopped_at"), "stops of 20 minutes or more only");
        assertEquals(truth.get("counts").get("loss_report_landed").asLong(), perKind.get("lost"));
        long total = perKind.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 20_000 && total <= 60_000, "the plan's 20-60 k link rows: " + total);
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + bl + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE ts IS NULL OR event_date IS NULL OR src IS NULL OR dst IS NULL"));
    }

    @Test
    void everyStorysEntitiesFormOneConnectedGroup() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            assertEquals(1, groups(members, links), e.getKey() + ": " + members + " are not one connected group");
            assertTrue(members.contains(e.getValue().get("hub").asText()), e.getKey());
        });
    }

    @Test
    void noLookAlikeJoinsItsStory() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            Set<String> lookalikes = ids(e.getValue().get("lookalikes"));
            assertFalse(lookalikes.isEmpty(), e.getKey());
            for (String a : lookalikes) {
                assertFalse(members.contains(a), e.getKey() + ": " + a + " is both");
                assertTrue(links.stream().anyMatch(l -> l[0].equals(a) || l[1].equals(a)), e.getKey() + ": the look-alike " + a + " is in the data");
            }
            for (String[] l : links)
                assertFalse(lookalikes.contains(l[0]) && members.contains(l[1]) || lookalikes.contains(l[1]) && members.contains(l[0]),
                        e.getKey() + ": the look-alike link " + String.join(" ", l) + " joins the story");
        });
    }

    /** Each saved view read the way the explorer reads it: its Dataset, its own filter. */
    @Test
    void everySavedViewHoldsItsStoryAndItsLookAlikeAndFitsTheCanvas() throws Exception {
        List<String> views = new ArrayList<>();
        try (Stream<Path> w = Files.list(TEMPLATE.resolve("config/registry/link-analysis-views"))) {
            for (Path p : w.toList()) views.add(p.getFileName().toString().replace(".toon", ""));
        }
        Map<String, String> storyOf = new HashMap<>();
        truth.get("stories").fields().forEachRemaining(e -> storyOf.put(e.getValue().get("view").asText(), e.getKey()));
        assertEquals(new TreeSet<>(storyOf.keySet()), new TreeSet<>(views), "one saved view per story");
        for (String view : views) {
            List<String[]> vl = triples("SELECT src, dst, kind FROM " + viewRelation(view));
            Set<String> nodes = new TreeSet<>();
            for (String[] l : vl) {
                nodes.add(l[0]);
                nodes.add(l[1]);
            }
            System.out.println(view + ": " + vl.size() + " rows, " + nodes.size() + " nodes");
            assertTrue(vl.size() < 4_000, view + " fits one read (traversal.rows_default): " + vl.size());
            assertTrue(nodes.size() < 1_000, view + " fits the canvas (projection_node_cap): " + nodes.size() + " nodes");
            JsonNode story = truth.get("stories").get(storyOf.get(view));
            Set<String> members = ids(story.get("entities"));
            Set<String> missing = new TreeSet<>(members);
            missing.removeAll(nodes);
            assertTrue(missing.isEmpty(), view + " is missing story entities " + missing);
            assertEquals(1, groups(members, vl), view + ": the story is one connected piece inside the view");
            for (String a : ids(story.get("lookalikes"))) {
                assertTrue(nodes.contains(a), view + ": the look-alike " + a + " is on the canvas beside the story");
                Set<String> beside = componentOf(a, vl);
                beside.retainAll(members);
                assertTrue(beside.isEmpty(), view + ": the look-alike " + a + " stays apart from the story, yet reaches " + beside);
            }
        }
    }

    /** Every node reachable from {@code start} over the given links (either direction). */
    private static Set<String> componentOf(String start, List<String[]> ls) {
        Map<String, List<String>> adj = new HashMap<>();
        for (String[] l : ls) {
            adj.computeIfAbsent(l[0], k -> new ArrayList<>()).add(l[1]);
            adj.computeIfAbsent(l[1], k -> new ArrayList<>()).add(l[0]);
        }
        Set<String> seen = new TreeSet<>(List.of(start));
        List<String> todo = new ArrayList<>(List.of(start));
        while (!todo.isEmpty())
            for (String n : adj.getOrDefault(todo.remove(todo.size() - 1), List.of()))
                if (seen.add(n)) todo.add(n);
        return seen;
    }

    @Test
    void theTeamLosesTwelveLoadsAndTheNightScannerTenWhileTheBusyDriverLosesTwo() throws Exception {
        JsonNode j = truth.get("inside_job");
        String losses = "TRY_CAST(regexp_extract(via, 'driver_losses:([0-9]+)', 1) AS INTEGER)";
        assertEquals(new TreeSet<>(List.of(j.get("driver").asText(), j.get("co_driver").asText(), j.get("lookalike_driver").asText())),
                new TreeSet<>(rows("SELECT DISTINCT src FROM " + bl + " WHERE kind IN ('drove', 'co_drove') AND " + losses + " >= 2")
                        .stream().map(r -> (String) r.get("src")).toList()), "only the team and the busy driver are on two losses or more");
        assertEquals(ids(j.get("trips")), dsts("SELECT dst FROM " + bl + " WHERE kind = 'drove' AND src = '" + j.get("driver").asText() + "' AND via NOT LIKE '%|lost:N|%'"));
        assertEquals(ids(j.get("trips")), dsts("SELECT dst FROM " + bl + " WHERE kind = 'co_drove' AND src = '" + j.get("co_driver").asText() + "' AND via NOT LIKE '%|lost:N|%'"),
                "the same twelve with the same co-driver");
        assertEquals(12, count("SELECT count(*) FROM " + bl + " WHERE kind = 'lost' AND src IN " + in(j.get("trips")) + " AND via LIKE 'loss_type:shortage|%'"),
                "short-delivered: each arrived with goods missing");
        List<Map<String, Object>> sc = rows("SELECT src, via FROM " + bl + " WHERE kind = 'scanned_by' AND dst = '" + j.get("scanner").asText() + "' AND via NOT LIKE '%|lost:N|%'");
        assertEquals(ids(j.get("night_trips")), new TreeSet<>(sc.stream().map(r -> (String) r.get("src")).toList()), "the night scanner is on ten of them");
        for (Map<String, Object> r : sc) {
            String via = (String) r.get("via");
            assertTrue(via.startsWith("scan:outbound|warehouse:" + j.get("scanner_warehouse").asText() + "|shift:night|") && via.endsWith("|scanner_losses:10"), via);
        }
        String dv = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'drove' AND src = '" + j.get("lookalike_driver").asText() + "' LIMIT 1").get(0).get("via");
        assertTrue(dv.endsWith("|driver_losses:2") && Integer.parseInt(dv.replaceAll(".*\\|driver_trips:([0-9]+)\\|.*", "$1")) >= 300, "a busy driver: " + dv);
        assertEquals(ids(j.get("lookalike_trips")), dsts("SELECT dst FROM " + bl + " WHERE kind = 'drove' AND src = '" + j.get("lookalike_driver").asText() + "' AND via NOT LIKE '%|lost:N|%'"));
        assertTrue(count("SELECT count(*) FROM " + bl + " WHERE kind = 'drove' AND src = '" + j.get("lookalike_driver").asText()
                + "' AND (via LIKE 'route:Southampton-Birmingham|%' OR via LIKE 'route:Birmingham-Southampton|%')") >= 280, "on the busiest route");
    }

    @Test
    void everyHighValueTruckAtTheLayByWasRobbedWhileTheServicesSeeAFewAmongMany() throws Exception {
        JsonNode h = truth.get("stop_hotspot");
        String layby = h.get("layby").asText();
        String services = h.get("lookalike_services").asText();
        String lost = "TRY_CAST(regexp_extract(via, 'place_lost_trips:([0-9]+)', 1) AS INTEGER)";
        assertEquals(new TreeSet<>(List.of(layby, services)), dsts("SELECT DISTINCT dst FROM " + bl + " WHERE kind = 'stopped_at' AND " + lost + " >= 3"),
                "only two places saw three or more losses after a long stop");
        List<Map<String, Object>> hv = rows("SELECT src, via FROM " + bl + " WHERE kind = 'stopped_at' AND dst = '" + layby + "' AND via LIKE '%|high_value:Y|%'");
        assertEquals(ids(h.get("trips")), new TreeSet<>(hv.stream().map(r -> (String) r.get("src")).toList()), "every high-value truck that stood at the lay-by");
        for (Map<String, Object> r : hv) {
            String via = (String) r.get("via");
            assertTrue(via.contains("|place_type:layby|last_stop:Y|") && via.contains("|arrived:N|lost:theft|") && via.endsWith("|place_lost_trips:9"),
                    "robbed while standing there: " + via);
        }
        assertEquals(9, count("SELECT count(*) FROM " + bl + " WHERE kind = 'stopped_at' AND dst = '" + layby + "' AND via NOT LIKE '%|lost:N|%'"));
        String sv = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'stopped_at' AND dst = '" + services + "' LIMIT 1").get(0).get("via");
        assertTrue(sv.contains("|place_type:services|") && sv.endsWith("|place_high_value_long_stops:" + h.get("lookalike_high_value_long_stops").asInt() + "|place_lost_trips:3")
                && Integer.parseInt(sv.replaceAll(".*\\|place_long_stops:([0-9]+)\\|.*", "$1")) >= 200, "busy official services: " + sv);
        assertEquals(ids(h.get("lookalike_lost_trips")), dsts("SELECT src AS dst FROM " + bl + " WHERE kind = 'stopped_at' AND dst = '" + services + "' AND via NOT LIKE '%|lost:N|%'"));
        assertEquals(0, count("SELECT count(*) FROM " + bl + " a JOIN " + bl + " b USING (src) WHERE a.kind = 'stopped_at' AND b.kind = 'stopped_at' AND a.dst = '"
                + layby + "' AND b.dst = '" + services + "'"), "no trip stood long at both");
    }

    @Test
    void theNewCarrierSharingADismissedDriversPhoneLosesEveryLoadAndTheNewOwnerOperatorDelivers() throws Exception {
        JsonNode f = truth.get("fictitious_carrier");
        String age = "TRY_CAST(regexp_extract(via, 'carrier_age_days:([0-9]+)', 1) AS INTEGER)";
        List<Map<String, Object>> loads = rows("SELECT src, via FROM " + bl + " WHERE kind = 'carried_by' AND dst = '" + f.get("carrier").asText() + "'");
        assertEquals(ids(f.get("trips")), new TreeSet<>(loads.stream().map(r -> (String) r.get("src")).toList()), "five loads, all it ever carried");
        for (Map<String, Object> r : loads) {
            String via = (String) r.get("via");
            int days = Integer.parseInt(via.replaceAll(".*\\|carrier_age_days:([0-9]+)\\|.*", "$1"));
            assertTrue(days >= 18 && days <= 32 && via.contains("|high_value:Y|arrived:N|lost:non_delivery|") && via.endsWith("|insured:N|gps_stops:0"),
                    "high value, three weeks old, never arrived, no GPS: " + via);
        }
        List<Map<String, Object>> phone = rows("SELECT dst, via FROM " + bl + " WHERE kind = 'same_phone' AND src = '" + f.get("carrier").asText() + "'");
        assertEquals(1, phone.size());
        assertEquals(f.get("thief").asText(), phone.get(0).get("dst"));
        String pv = (String) phone.get(0).get("via");
        assertTrue(pv.contains("|dst_left_on:2025-11-30|dst_driver_losses:1") && !pv.replaceAll(".*src_phone:([^|]*)\\|.*", "$1").equals(pv.replaceAll(".*dst_phone:([^|]*)\\|.*", "$1")),
                "the same number written another way, a driver who left after a loss: " + pv);
        assertEquals(1, count("SELECT count(*) FROM " + bl + " WHERE kind = 'drove' AND src = '" + f.get("thief").asText() + "' AND dst = '" + f.get("thief_trip").asText() + "' AND via LIKE '%|lost:theft|%'"));
        List<Map<String, Object>> n = rows("SELECT src, via FROM " + bl + " WHERE kind = 'carried_by' AND dst = '" + f.get("lookalike_carrier").asText() + "' AND via LIKE '%|high_value:Y|%' AND " + age + " < 60");
        assertEquals(ids(f.get("lookalike_high_value_trips")), new TreeSet<>(n.stream().map(r -> (String) r.get("src")).toList()));
        for (Map<String, Object> r : n)
            assertTrue(((String) r.get("via")).contains("|arrived:Y|lost:N|") && ((String) r.get("via")).contains("|insured:Y|"), "delivered: " + r.get("via"));
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'carried_by' AND dst = '" + f.get("lookalike_carrier").asText() + "' AND via NOT LIKE '%|lost:N|%'"));
        assertEquals(List.of(f.get("lookalike_driver").asText()), rows("SELECT dst FROM " + bl + " WHERE kind = 'same_phone' AND src = '" + f.get("lookalike_carrier").asText() + "'")
                .stream().map(r -> (String) r.get("dst")).toList(), "an owner-operator: its phone is its own driver's");
    }

    private static Set<String> dsts(String sql) throws Exception {
        return new TreeSet<>(rows(sql).stream().map(r -> (String) r.get("dst")).toList());
    }

    /** The relation a saved view reads: its Dataset's parquet, narrowed by its own flat filter group. */
    private static String viewRelation(String view) throws Exception {
        Map<String, Object> v = ToonHelper.load(TEMPLATE.resolve("config/registry/link-analysis-views/" + view + ".toon").toString());
        Map<?, ?> query = (Map<?, ?>) v.get("query");
        String datasetId = (String) ((Map<?, ?>) query.get("projection")).get("datasetId");
        Map<String, Object> ds = ToonHelper.load(TEMPLATE.resolve("config/registry/datasets/" + datasetId + ".toon").toString());
        String rel = parquet((String) ds.get("physicalRef"));
        Map<?, ?> filter = (Map<?, ?>) query.get("filter");
        if (filter == null) return rel;
        List<String> preds = new ArrayList<>();
        for (Object o : (List<?>) filter.get("items")) {
            Map<?, ?> item = (Map<?, ?>) o;
            assertEquals("=", item.get("operator"), view + ": this test renders '=' only");
            preds.add(item.get("field") + " = '" + item.get("value") + "'");
        }
        return "(SELECT * FROM " + rel + " WHERE " + String.join(" " + filter.get("op") + " ", preds) + ")";
    }

    private static int groups(Set<String> members, List<String[]> ls) {
        Map<String, String> parent = new HashMap<>();
        for (String m : members) parent.put(m, m);
        for (String[] l : ls)
            if (members.contains(l[0]) && members.contains(l[1])) parent.put(root(parent, l[0]), root(parent, l[1]));
        Set<String> roots = new TreeSet<>();
        for (String m : members) roots.add(root(parent, m));
        return roots.size();
    }

    private static String in(JsonNode arr) {
        List<String> q = new ArrayList<>();
        arr.forEach(n -> q.add("'" + n.asText() + "'"));
        return "(" + String.join(", ", q) + ")";
    }

    private static List<String[]> triples(String sql) throws Exception {
        List<String[]> out = new ArrayList<>();
        for (Map<String, Object> r : rows(sql))
            out.add(new String[]{(String) r.get("src"), (String) r.get("dst"), (String) r.get("kind")});
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void unused(PlatformServiceRegistry platform, String id, String iface) {
        Class type;
        try {
            type = Class.forName(iface);
        } catch (ClassNotFoundException e) {
            return;
        }
        platform.register(id, type, java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) return m.invoke(id, a);
                    throw new UnsupportedOperationException(id + " is not wired in this test");
                }));
    }

    private static Set<String> ids(JsonNode arr) {
        Set<String> out = new TreeSet<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static String root(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) x = parent.get(x);
        return x;
    }

    private static long count(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static JobRun awaitRun(JobService js, String job) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobRun r = js.lastRunOf(job).orElse(null);
            if (r != null && !"SKIPPED".equals(r.status()) && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(50);
        }
        return null;
    }

    private static String parquet(String sink) {
        return parquet(sink, "/*.parquet");
    }

    private static String parquet(String sink, String glob) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + glob + "')";
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
