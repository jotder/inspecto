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
 * The {@code la-trade} Space Template (Basic tier) RUN end to end from a verbatim copy: the seven Pipelines land the shipped
 * files with nothing quarantined, the {@code trade_links} {@code sql.template} Job folds them, and the four derived Jobs cut one
 * Dataset per saved view. Over the REAL Datasets it pins the generator's ground truth ({@code tools/gen-la-trade-data.ground-truth.json}):
 * every story's entities form one connected group in the link Dataset AND in its saved view's Dataset, no benign look-alike has a
 * link to any of them, each view fits the canvas, and the engine's own SQL raises each red flag on exactly the planted records -
 * above all {@code vessel_elsewhere}, the bill of lading checked against its vessel's position on the B/L date, which the five
 * transhipment look-alikes pass. The facts in the files themselves are pinned by {@code tools/gen-la-trade-data.test.mjs}.
 */
class TradeLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-trade");
    private static final List<String> FEEDS = List.of("company", "director", "trade", "bill_of_lading", "vessel_position",
            "price_reference", "payment");
    private static final List<String> VIEWS = List.of("over_invoicing", "phantom_shipment", "circular_trade", "shell_importers");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static final Map<String, List<String[]>> links = new LinkedHashMap<>();

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-trade");
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
        List<String> jobs = new ArrayList<>(List.of("trade_links"));
        VIEWS.forEach(v -> jobs.add("trade_" + v + "_links"));
        run(space, jobs);
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-trade-data.ground-truth.json").toFile());
        for (String sink : jobs) {
            List<String[]> ls = new ArrayList<>();
            for (Map<String, Object> r : rows("SELECT src, dst, kind, flag FROM " + parquet(sink)))
                ls.add(new String[]{(String) r.get("src"), (String) r.get("dst"), (String) r.get("kind"), (String) r.get("flag")});
            links.put(sink, ls);
        }
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (plan step 8)");
    }

    @Test
    void theLinkJobRunsOnEveryCommitAndOnACron() throws Exception {
        // a pipeline.commit run can be SKIPPED while the previous one is still in flight (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches up
        JobConfig job = JobConfig.load(TEMPLATE.resolve("config/jobs/trade_links_job.toon").toString());
        assertEquals("pipeline.commit", job.onSignal());
        assertEquals("*/5 * * * *", job.cron());
    }

    @Test
    void everyLandedRowIsInItsDatasetAndTheStagedMonthIsNotLanded() throws Exception {
        for (String feed : FEEDS) {
            long csv = 0;
            try (Stream<Path> w = Files.list(TEMPLATE.resolve("data/inbox").resolve(feed))) {
                for (Path f : w.toList()) csv += Files.readAllLines(f).size() - 1;
            }
            assertEquals(csv, count("SELECT count(*) FROM " + feedParquet(feed)), feed);
        }
        assertEquals(0, count("SELECT count(*) FROM " + feedParquet("trade") + " WHERE ship_date >= DATE '2026-10-01'"));
        assertEquals(truth.get("landed_rows").get("trade").asLong(), count("SELECT count(*) FROM " + feedParquet("trade")));
        assertEquals(0, count("SELECT count(*) FROM " + feedParquet("price_reference") + " WHERE length(hs_code) <> 6"),
                "HS codes keep their leading zero");
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        String l = parquet("trade_links");
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via", "flag"),
                new ArrayList<>(rows("SELECT * FROM " + l + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + l + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("directed_by", "on_vessel", "paid", "registered_at", "shipped_in", "traded_with"), new ArrayList<>(perKind.keySet()));
        long total = perKind.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 20_000 && total <= 60_000, "20-60 k link rows (plan): " + total);
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + l + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + l + " WHERE ts IS NULL OR event_date IS NULL OR flag IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + l + " WHERE kind = 'paid' AND (src NOT LIKE 'CO-%' OR dst NOT LIKE 'CO-%')"),
                "every account belongs to a company");
    }

    @Test
    void vesselElsewhereIsRaisedOnThePhantomBillsOfLadingOnlyAndNotOnTheTranshipments() throws Exception {
        JsonNode s = truth.get("stories").get("phantom_shipment");
        String exp = s.get("facts").get("exporter").asText(), pv = s.get("facts").get("vessel").asText();
        assertEquals(List.of(exp + ">" + pv), pairs("on_vessel", "vessel_elsewhere"));
        List<String> buyers = new ArrayList<>();
        s.get("facts").get("importers").forEach(i -> buyers.add(exp + ">" + i.asText()));
        assertEquals(buyers.stream().sorted().toList(), pairs("traded_with", "vessel_elsewhere"));
        String via = (String) rows("SELECT via FROM " + parquet("trade_links") + " WHERE kind = 'on_vessel' AND src = '" + exp + "'").get(0).get("via");
        s.get("facts").get("bills_of_lading").forEach(b -> assertTrue(via.contains(b.asText()), via));
        // the transhipments: flagged as such, both legs pass the position check
        Set<String> ts = ids(s.get("lookalikes"));
        for (String[] l : links.get("trade_links"))
            if (ts.contains(l[0]) && l[2].equals("on_vessel")) {
                assertEquals("transhipment", l[3], String.join(" ", l));
            }
        assertTrue(links.get("trade_links").stream().filter(l -> ts.contains(l[0]) && l[2].equals("on_vessel")).count() >= 10,
                "5 transhipments, two vessels each");
    }

    @Test
    void theOtherRedFlagsAreRaisedOnThePlantedRecordsOnly() throws Exception {
        JsonNode st = truth.get("stories");
        JsonNode oi = st.get("over_invoicing").get("facts");
        assertEquals(new TreeSet<>(Set.of(oi.get("exporter").asText(), oi.get("specialty_supplier").asText())),
                new TreeSet<>(pairs("traded_with", "price_over_reference").stream().map(p -> p.split(">")[0]).toList()));
        assertEquals(1 + oi.get("specialty_buyers").asInt(), pairs("traded_with", "price_over_reference").size());
        assertEquals(ids(st.get("circular_trade").get("facts").get("containers")), dsts("shipped_in", "relayed"));
        assertEquals(ids(st.get("circular_trade").get("facts").get("leasing_boxes")), dsts("shipped_in", "many_shippers"));
        JsonNode sh = st.get("shell_importers").get("facts");
        assertEquals(new TreeSet<>(Set.of(sh.get("agent_address").asText().toUpperCase(), sh.get("business_centre").asText().toUpperCase())),
                dsts("registered_at", "shared_address"));
    }

    @Test
    void theShellImportersPayTheirExporterFarMoreThanItShippedThem() throws Exception {
        JsonNode f = truth.get("stories").get("shell_importers").get("facts");
        String l = parquet("trade_links"), exp = f.get("exporter").asText();
        double paid = 0, shipped = 0;
        for (JsonNode i : f.get("importers")) {
            paid += ((Number) rows("SELECT amount FROM " + l + " WHERE kind = 'paid' AND src = '" + i.asText() + "' AND dst = '" + exp + "'")
                    .get(0).get("amount")).doubleValue();
            shipped += ((Number) rows("SELECT amount FROM " + l + " WHERE kind = 'traded_with' AND src = '" + exp + "' AND dst = '" + i.asText() + "'")
                    .get(0).get("amount")).doubleValue();
        }
        assertEquals(f.get("paid_to_exporter").asDouble(), paid, 0.05);
        assertTrue(paid > 10 * shipped, paid + " paid against " + shipped + " shipped");
    }

    @Test
    void everySavedViewFitsTheCanvasAndShowsItsLookAlikes() {
        // a saved view draws its whole Dataset; past projection_node_cap (1 000 nodes) or traversal.rows_default (4 000 rows) it is cut short
        for (String v : VIEWS) {
            List<String[]> ls = links.get("trade_" + v + "_links");
            Set<String> nodes = new TreeSet<>();
            ls.forEach(l -> { nodes.add(l[0]); nodes.add(l[1]); });
            assertTrue(ls.size() < 4_000, v + " fits one read: " + ls.size());
            assertTrue(nodes.size() < 1_000, v + " fits the canvas: " + nodes.size());
            for (String a : ids(truth.get("stories").get(v).get("lookalikes")))
                assertTrue(nodes.contains(a), v + ": the look-alike " + a + " is there to compare");
        }
    }

    @Test
    void everyStorysEntitiesFormOneConnectedGroupInTheLinkDatasetAndInItsView() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            for (String sink : List.of("trade_links", "trade_" + e.getKey() + "_links")) {
                Map<String, String> parent = new HashMap<>();
                for (String m : members) parent.put(m, m);
                for (String[] l : links.get(sink))
                    if (members.contains(l[0]) && members.contains(l[1])) parent.put(root(parent, l[0]), root(parent, l[1]));
                Set<String> groups = new TreeSet<>();
                for (String m : members) groups.add(root(parent, m));
                assertEquals(1, groups.size(), e.getKey() + " in " + sink + ": " + members.size() + " entities fall into " + groups.size() + " groups");
            }
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
                assertTrue(links.get("trade_links").stream().anyMatch(l -> l[0].equals(a) || l[1].equals(a)), e.getKey() + ": the look-alike " + a + " is in the data");
            }
            for (String[] l : links.get("trade_links"))
                assertFalse(lookalikes.contains(l[0]) && members.contains(l[1]) || lookalikes.contains(l[1]) && members.contains(l[0]),
                        e.getKey() + ": the look-alike link " + String.join(" ", l) + " joins the story");
        });
    }

    private static List<String> pairs(String kind, String flag) {
        return links.get("trade_links").stream().filter(l -> l[2].equals(kind) && List.of(l[3].split(",")).contains(flag))
                .map(l -> l[0] + ">" + l[1]).sorted().toList();
    }

    private static Set<String> dsts(String kind, String flag) {
        Set<String> out = new TreeSet<>();
        links.get("trade_links").stream().filter(l -> l[2].equals(kind) && l[3].equals(flag)).forEach(l -> out.add(l[1]));
        return out;
    }

    private static void run(Path space, List<String> names) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : names) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        // the built-in Job Types that declare these grants must validate at registration; this test sends nothing
        for (Map.Entry<String, String> e : Map.of("alerts", "com.gamma.alert.AlertAccess", "mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "datasets", "com.gamma.query.DatasetAccess",
                "schema", "com.gamma.pipeline.SchemaAccess", "link-index", "com.gamma.linkindex.LinkIndexAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform)) {
            js.eventLog(com.gamma.event.EventLog.current());
            js.start();
            for (String j : names) {
                // the link Job's job.dataset.produced signal runs the derived Jobs as at runtime (those runs are not in
                // lastRunOf); wait for their Datasets, then run each once more by hand to read its status
                if (!j.equals(names.get(0))) assertTrue(awaitDataset(j), j + " was never fired by the trade_links signal");
                JobRun r = null;
                for (int attempt = 0; attempt < 20 && (r == null || "SKIPPED".equals(r.status())); attempt++) {
                    assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                    r = awaitRun(js, j);
                    if (r != null && "SKIPPED".equals(r.status())) Thread.sleep(250);
                }
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
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
            if (r != null && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(50);
        }
        return null;
    }

    private static boolean awaitDataset(String sink) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (hasFiles(data.resolve(sink))) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static String parquet(String sink) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + "/*.parquet')";
    }

    private static String feedParquet(String feed) {
        return "read_parquet('" + data.resolve(feed + "/database").toString().replace('\\', '/') + "/**/*.parquet')";
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
