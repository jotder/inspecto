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
 * The {@code la-benefits} Space Template (Basic tier) RUN end to end from a verbatim copy: the seven Pipelines land the shipped
 * extracts with nothing quarantined, the {@code benefits_links} {@code sql.template} Job folds them, and the derived
 * {@code benefits_screen_links} Job builds the four integrity screens the saved views read. Over the REAL link Datasets it pins the
 * generator's ground truth ({@code tools/gen-la-benefits-data.ground-truth.json}): every story's entities form one connected group,
 * no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole, shows the
 * look-alike beside it and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts behind each story in the files
 * themselves are pinned by {@code tools/gen-la-benefits-data.test.mjs}.
 */
class BenefitsLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-benefits");
    private static final List<String> FEEDS = List.of("address", "person", "household", "claim", "death_register", "payroll", "tax_return");
    private static final List<String> JOBS = List.of("benefits_links", "benefits_screen_links");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String bl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-benefits");
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
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-benefits-data.ground-truth.json").toFile());
        bl = parquet("benefits_links");
        links = triples("SELECT src, dst, kind FROM " + bl);
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (wave D)");
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/benefits_links_job.toon").toString()).cron(),
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
        assertEquals(truth.get("counts").get("payroll_landed").asLong(), count("SELECT count(*) FROM " + parquet("payroll/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("payroll/database", "/**/*.parquet") + " WHERE CAST(period AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("tax_return/database", "/**/*.parquet") + " WHERE CAST(filed_at AS DATE) >= DATE '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + bl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + bl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("claims", "employed_by", "lives_at", "paid_into", "prepared_by", "refund_to"), new ArrayList<>(perKind.keySet()));
        assertEquals(truth.get("counts").get("person").asLong(), perKind.get("lives_at"), "one home per person");
        assertEquals(truth.get("counts").get("claim_landed").asLong(), perKind.get("claims"), "one claims link per landed claim");
        assertEquals(perKind.get("claims"), perKind.get("paid_into"), "every claim is paid into an account");
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
    void theAddressFarmIsFourteenHouseholdsOfOneAndTheCareHomesAreNot() throws Exception {
        JsonNode f = truth.get("address_farm");
        List<Map<String, Object>> farm = rows("SELECT src, via FROM " + bl + " WHERE kind = 'lives_at' AND dst = '" + f.get("address").asText() + "'");
        assertEquals(ids(f.get("claimants")), new TreeSet<>(farm.stream().map(r -> (String) r.get("src")).toList()));
        for (Map<String, Object> r : farm)
            assertEquals("residents:14|claimants:14|households_declared:14|declared_members:14", r.get("via"), "14 households of one at one address");
        String paid = "SELECT pi.dst FROM " + bl + " pi JOIN " + bl + " cl ON cl.kind = 'claims' AND cl.dst = pi.src WHERE pi.kind = 'paid_into' AND cl.src IN " + in(f.get("claimants"));
        assertEquals(ids(f.get("accounts")), new TreeSet<>(rows(paid).stream().map(r -> (String) r.get("dst")).toList()), "paid into 3 accounts");
        assertEquals(14, count("SELECT count(*) FROM " + bl + " WHERE kind = 'paid_into' AND dst IN " + in(f.get("accounts"))), "nobody else is paid into them");
        List<String> homes = new ArrayList<>();
        f.get("care_homes").fieldNames().forEachRemaining(homes::add);
        for (String home : homes) {
            assertTrue(count("SELECT count(*) FROM " + bl + " WHERE kind = 'lives_at' AND dst = '" + home + "' AND via LIKE '%|households_declared:1|%'") >= 20,
                    home + ": one declaration for 20 or more residents");
            assertEquals(0, count("SELECT count(*) FROM (SELECT pi.dst FROM " + bl + " pi JOIN " + bl + " cl ON cl.kind = 'claims' AND cl.dst = pi.src"
                    + " JOIN " + bl + " la ON la.kind = 'lives_at' AND la.src = cl.src AND la.dst = '" + home + "' WHERE pi.kind = 'paid_into'"
                    + " GROUP BY pi.dst HAVING count(DISTINCT cl.src) > 1)"), home + ": every resident is paid into their own account");
        }
    }

    @Test
    void theDeceasedArePaidIntoAccountsThatTakeOneLivingPersonsClaimsAndTheLateNoticeIsRecovered() throws Exception {
        JsonNode d = truth.get("payments_to_the_deceased");
        List<Map<String, Object>> ongoing = rows("SELECT src, dst FROM " + bl + " WHERE kind = 'paid_into' AND via LIKE '%paid_after_death_days:ongoing%'");
        assertEquals(9, ongoing.size(), "only the nine are still paid after their deaths");
        assertEquals(ids(d.get("accounts")), new TreeSet<>(ongoing.stream().map(r -> (String) r.get("dst")).toList()));
        Set<String> intoAccounts = new TreeSet<>();
        for (Map<String, Object> r : rows("SELECT cl.src FROM " + bl + " pi JOIN " + bl + " cl ON cl.kind = 'claims' AND cl.dst = pi.src WHERE pi.kind = 'paid_into' AND pi.dst IN " + in(d.get("accounts"))))
            intoAccounts.add((String) r.get("src"));
        Set<String> expected = ids(d.get("deceased"));
        expected.add(d.get("controller").asText());
        assertEquals(expected, intoAccounts, "the accounts take the nine pensions and one living person's claims, nothing else");
        assertEquals(3, count("SELECT count(*) FROM " + bl + " WHERE kind = 'claims' AND src = '" + d.get("controller").asText() + "'"));
        String via = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'paid_into' AND src = '" + d.get("lookalike_claim").asText() + "'").get(0).get("via");
        assertTrue(via.contains("|paid_after_death_days:42|"), "stopped 2 days after a notice 40 days late: " + via);
        assertTrue(Long.parseLong(via.replaceAll(".*\\|recovered:([0-9]+).*", "$1")) > 0, "the overpayment is recovered: " + via);
    }

    @Test
    void theTwentyJobseekersWorkFullTimeAndNobodyElseIsOverTheLimitForMoreThanAMonth() throws Exception {
        JsonNode w = truth.get("undeclared_work");
        String overLimit = "TRY_CAST(regexp_extract(via, 'over_limit_months:([0-9]+)', 1) AS INTEGER)";
        List<Map<String, Object>> over = rows("SELECT src, dst, " + overLimit + " AS m FROM " + bl + " WHERE kind = 'employed_by' AND " + overLimit + " >= 2");
        assertEquals(ids(w.get("claimants")), new TreeSet<>(over.stream().map(r -> (String) r.get("src")).toList()), "only the twenty work over the limit for long");
        assertEquals(ids(w.get("employers")), new TreeSet<>(over.stream().map(r -> (String) r.get("dst")).toList()), "for the two employers");
        for (String p : ids(w.get("claimants")))
            assertTrue(over.stream().filter(r -> r.get("src").equals(p)).mapToInt(r -> ((Number) r.get("m")).intValue()).max().orElse(0) >= 6, p + ": six months or more");
        for (String p : ids(w.get("on_both")))
            assertEquals(2, over.stream().filter(r -> r.get("src").equals(p)).count(), p + " is on both payrolls");
        String via = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'employed_by' AND src = '" + w.get("lookalike").asText() + "'").get(0).get("via");
        assertTrue(via.contains("|over_limit_months:0|") && !via.contains("|jobseeker_months:0|"), "the look-alike earns under the limit while claiming: " + via);
    }

    @Test
    void thePreparersEightyRefundsGoToSixAccountsAndTheLargePreparersToTheirClients() throws Exception {
        JsonNode t = truth.get("tax_preparer_ring");
        assertEquals(ids(t.get("taxpayers")), new TreeSet<>(rows("SELECT src FROM " + bl + " WHERE kind = 'prepared_by' AND dst = '" + t.get("preparer").asText() + "'")
                .stream().map(r -> (String) r.get("src")).toList()), "the preparer files the eighty returns");
        List<Map<String, Object>> refunds = rows("SELECT dst, amount FROM " + bl + " WHERE kind = 'refund_to' AND src IN " + in(t.get("taxpayers")));
        assertEquals(80, refunds.size());
        assertEquals(ids(t.get("accounts")), new TreeSet<>(refunds.stream().map(r -> (String) r.get("dst")).toList()), "into six accounts");
        for (Map<String, Object> r : refunds) assertTrue(((Number) r.get("amount")).doubleValue() >= 1800, "an inflated refund: " + r);
        assertTrue(count("SELECT CAST(median(amount) AS BIGINT) FROM " + bl + " WHERE kind = 'refund_to'") < 600, "the usual refund is a few hundred");
        String big = t.get("lookalike_preparer").asText();
        long landed = count("SELECT count(*) FROM " + bl + " WHERE kind = 'prepared_by' AND dst = '" + big + "'");
        assertTrue(landed >= 200 && landed <= t.get("lookalike_clients").asLong(), "most of the 250 returns are filed by September: " + landed);
        assertEquals(0, count("SELECT count(*) FROM (SELECT rt.dst FROM " + bl + " rt WHERE rt.kind = 'refund_to' AND rt.src IN (SELECT src FROM " + bl
                + " WHERE kind = 'prepared_by' AND dst = '" + big + "') GROUP BY rt.dst HAVING count(*) > 2)"), "each client is refunded into their own (or a joint) account");
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
