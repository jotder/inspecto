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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * The {@code la-procurement} Space Template (Basic tier) RUN end to end from a verbatim copy: the seven Pipelines land the
 * shipped extracts with nothing quarantined, the {@code procurement_links} {@code sql.template} Job folds them, and the two
 * derived Jobs build the Datasets behind the approvals and debarment views. Over the REAL link Datasets it pins the generator's
 * ground truth ({@code tools/gen-la-procurement-data.ground-truth.json}): every story's entities form one connected group, no
 * benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole, shows the
 * look-alike beside it and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts behind each story in the files
 * themselves are pinned by {@code tools/gen-la-procurement-data.test.mjs}.
 */
class ProcurementLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-procurement");
    private static final List<String> FEEDS = List.of("company", "director", "person", "official", "tender", "bid", "contract_change");
    private static final List<String> JOBS = List.of("procurement_links", "procurement_approval_links", "procurement_debarment_links");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-procurement");
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
                // the derived Jobs may already be running off the main Job's produced signal; a trigger then is a no-op
                if (j.equals(JOBS.get(0))) assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                else js.triggerRun(j, null, Map.of());
                JobRun r = awaitRun(js, j);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-procurement-data.ground-truth.json").toFile());
        links = triples("SELECT src, dst, kind FROM " + parquet("procurement_links"));
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (wave D)");
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/procurement_links_job.toon").toString()).cron(),
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
        assertEquals(truth.get("counts").get("tender_landed").asLong(), count("SELECT count(*) FROM " + parquet("tender/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("tender/database", "/**/*.parquet") + " WHERE CAST(published_at AS DATE) >= DATE '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSevenKinds() throws Exception {
        String pl = parquet("procurement_links");
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via", "market"),
                new ArrayList<>(rows("SELECT * FROM " + pl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + pl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("approved_change", "bid_on", "directed_by", "lives_at", "same_author", "same_ip", "won"), new ArrayList<>(perKind.keySet()));
        assertEquals(truth.get("counts").get("bid_landed").asLong(), perKind.get("bid_on"), "one bid_on per landed bid");
        assertEquals(truth.get("counts").get("person").asLong(), perKind.get("lives_at"), "one home per person");
        long total = perKind.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 10_000 && total <= 60_000, "about 11 000 link rows (the plan's 20-60 k scaled with its row counts): " + total);
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + pl + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + pl + " WHERE ts IS NULL OR event_date IS NULL OR src IS NULL OR dst IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + pl + " WHERE kind = 'same_author' AND lower(dst) IN ('administrator', '')"),
                "a blank or placeholder author is never a link");
        Set<String> sharedIps = new TreeSet<>();
        for (Map<String, Object> r : rows("SELECT DISTINCT dst FROM " + pl + " WHERE kind = 'same_ip'")) sharedIps.add((String) r.get("dst"));
        assertEquals(new TreeSet<>(List.of(truth.get("cover_bids").get("winner_ip").asText(), truth.get("cover_bids").get("kiosk_ip").asText())), sharedIps,
                "only the winner's office and the kiosk are shared addresses");
    }

    @Test
    void everyStorysEntitiesFormOneConnectedGroup() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            assertEquals(1, groups(members, links), e.getKey() + ": " + members + " are not one connected group");
            String hub = e.getValue().get("hub").asText();
            assertTrue(members.contains(hub), e.getKey());
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
            for (String a : ids(story.get("lookalikes")))
                assertTrue(nodes.contains(a), view + ": the look-alike " + a + " is on the canvas beside the story");
        }
    }

    @Test
    void theRotationFirmsWinInTurnAndLoseByFiveToTwelvePercent() throws Exception {
        JsonNode r = truth.get("bid_rotation");
        String pl = parquet("procurement_links");
        Map<String, String> winnerOf = new HashMap<>();
        for (Map<String, Object> w : rows("SELECT src, dst FROM " + pl + " WHERE kind = 'won' AND dst IN " + in(r.get("tenders_in_order"))))
            winnerOf.put((String) w.get("dst"), (String) w.get("src"));
        assertEquals(24, winnerOf.size());
        for (int i = 0; i < 24; i++)   // in order of publication
            assertEquals(r.get("firms").get(i % 4).asText(), winnerOf.get(r.get("tenders_in_order").get(i).asText()), "tender " + (i + 1) + " goes to the next firm in turn");
        List<Map<String, Object>> losers = rows("SELECT src, dst, via FROM " + pl + " WHERE kind = 'bid_on' AND dst IN " + in(r.get("tenders_in_order")) + " AND via LIKE '%vs_award%'");
        assertEquals(72, losers.size(), "three losing bids on each of the 24");
        for (Map<String, Object> l : losers) {
            double pct = Double.parseDouble(((String) l.get("via")).replaceAll(".*vs_award:\\+?([-0-9.]+)%.*", "$1"));
            assertTrue(pct >= 4.9 && pct <= 12.1, l + " is 5-12 % above the winner");
        }
        List<Double> margins = new ArrayList<>();
        for (Map<String, Object> l : rows("SELECT via FROM " + pl + " WHERE kind = 'bid_on' AND src IN " + in(r.get("lookalike_firms")) + " AND via LIKE '%vs_award%'"))
            margins.add(Double.parseDouble(((String) l.get("via")).replaceAll(".*vs_award:\\+?([-0-9.]+)%.*", "$1")));
        assertTrue(margins.stream().anyMatch(m -> m < 2) && margins.stream().anyMatch(m -> m > 15),
                "the big three lose by anything from under 2 % to over 15 %: " + margins);
    }

    @Test
    void theOfficialApprovesNineChangesForTheRelativesFirmAndThePhoenixWinsWithinThreeMonths() throws Exception {
        String pl = parquet("procurement_links");
        JsonNode c = truth.get("conflict_of_interest");
        Map<String, Object> ac = rows("SELECT via FROM " + pl + " WHERE kind = 'approved_change' AND src = '" + c.get("official").asText()
                + "' AND dst = '" + c.get("firm").asText() + "'").get(0);
        assertTrue(((String) ac.get("via")).startsWith("changes:9|"), ac.toString());
        assertEquals(0, count("SELECT count(*) FROM " + pl + " WHERE kind = 'approved_change' AND via NOT LIKE 'changes:1|%' AND via NOT LIKE 'changes:2|%'"
                + " AND via NOT LIKE 'changes:3|%' AND NOT (src = '" + c.get("official").asText() + "' AND dst = '" + c.get("firm").asText() + "')"),
                "no other official approves more than 3 changes for one firm");
        assertTrue(count("SELECT count(*) FROM " + pl + " WHERE kind = 'approved_change' AND src = '" + c.get("lookalike_official").asText() + "'") >= 17,
                "the busy contracts manager approves for many suppliers");
        JsonNode p = truth.get("phoenix_firm");
        Map<String, Object> won = rows("SELECT CAST(ts AS DATE) AS d FROM " + pl + " WHERE kind = 'won' AND src = '" + p.get("new").asText()
                + "' AND dst = '" + p.get("won").asText() + "'").get(0);
        long days = ChronoUnit.DAYS.between(LocalDate.parse(p.get("debarred_at").asText()), LocalDate.parse(won.get("d").toString()));
        assertTrue(days > 0 && days <= 90, "the new firm wins " + days + " days after the debarment");
        assertEquals(2, count("SELECT count(*) FROM " + pl + " WHERE kind = 'directed_by' AND src = '" + p.get("old").asText()
                + "' AND via LIKE '%company_debarred:" + p.get("debarred_at").asText() + "%'"), "the debarment is on the old firm's directorships");
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
