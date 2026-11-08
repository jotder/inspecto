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
 * The {@code la-insider} Space Template (Basic tier) RUN end to end from a verbatim copy: the six Pipelines land the shipped
 * extracts with nothing quarantined, the {@code insider_links} {@code sql.template} Job folds them, and the derived
 * {@code insider_screen_links} Job builds the three insider-risk screens the saved views read. Over the REAL link Datasets it
 * pins the generator's ground truth ({@code tools/gen-la-insider-data.ground-truth.json}): every story's entities form one
 * connected group, no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its
 * story whole, shows the look-alike beside it but apart from it, and fits the canvas (under 1 000 nodes, under 4 000 rows). The
 * facts behind each story in the files themselves are pinned by {@code tools/gen-la-insider-data.test.mjs}.
 */
class InsiderLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-insider");
    private static final List<String> FEEDS = List.of("employee", "access_grant", "access_daily", "email_external", "expense", "vendor_change");
    private static final List<String> JOBS = List.of("insider_links", "insider_screen_links");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String bl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-insider");
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
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-insider-data.ground-truth.json").toFile());
        bl = parquet("insider_links");
        links = triples("SELECT src, dst, kind FROM " + bl);
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (wave D)");
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/insider_links_job.toon").toString()).cron(),
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
        assertEquals(truth.get("counts").get("access_daily_landed").asLong(), count("SELECT count(*) FROM " + parquet("access_daily/database", "/**/*.parquet")));
        assertEquals(truth.get("counts").get("expense_landed").asLong(), count("SELECT count(*) FROM " + parquet("expense/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("access_daily/database", "/**/*.parquet") + " WHERE read_date >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("access_grant/database", "/**/*.parquet") + " WHERE CAST(granted_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM (SELECT employee_id, system, read_date FROM " + parquet("access_daily/database", "/**/*.parquet")
                + " GROUP BY ALL HAVING count(*) > 1)"), "daily counts per employee and system, never record-level");
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + bl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + bl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("accessed", "approved_for", "changed_vendor", "granted_by", "mailed_domain", "reports_to"), new ArrayList<>(perKind.keySet()));
        long employees = truth.get("counts").get("employee").asLong();
        assertEquals(employees - 1, perKind.get("reports_to"), "everyone but the chief executive has a manager");
        String grants = parquet("access_grant/database", "/**/*.parquet");
        assertEquals(count("SELECT count(*) FROM (SELECT DISTINCT employee_id, system FROM " + grants + ")"), perKind.get("accessed"), "one accessed link per system held");
        String exp = parquet("expense/database", "/**/*.parquet");
        assertEquals(count("SELECT count(*) FROM (SELECT DISTINCT approver_id, requester_id FROM " + exp + ")"), perKind.get("approved_for"));
        long total = perKind.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 20_000 && total <= 60_000, "the plan's 20-60 k link rows: " + total);
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + bl + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE ts IS NULL OR event_date IS NULL OR src IS NULL OR dst IS NULL OR src = dst"));
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
    void everySavedViewHoldsItsStoryAndItsLookAlikeApartAndFitsTheCanvas() throws Exception {
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

    @Test
    void theLeaverReadsTwentyTimesTheirNormalAfterNoticeAndMailsAGigabyteWhileTheEngineersBulkReadIsTicketed() throws Exception {
        JsonNode l = truth.get("leaver_exfiltration");
        String leaver = l.get("leaver").asText();
        String engineer = l.get("lookalike_engineer").asText();
        String ratio = "TRY_CAST(regexp_extract(via, 'peak_ratio:([0-9.]+)', 1) AS DOUBLE)";
        assertEquals(new TreeSet<>(List.of(leaver + " SYS-CRM", engineer + " SYS-CUSTARCH")),
                new TreeSet<>(rows("SELECT src || ' ' || dst AS p FROM " + bl + " WHERE kind = 'accessed' AND " + ratio + " >= 10")
                        .stream().map(r -> (String) r.get("p")).toList()), "only two reads ever spike tenfold");
        assertTrue(count("SELECT count(*) FROM " + bl + " WHERE kind = 'accessed' AND " + ratio + " >= 4") == 2, "the background never comes close");
        String lv = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'accessed' AND src = '" + leaver + "' AND dst = 'SYS-CRM'").get(0).get("via");
        assertEquals(l.get("peak_ratio").asDouble(), Double.parseDouble(lv.replaceAll(".*\\|peak_ratio:([0-9.]+)\\|.*", "$1")), 0.05, lv);
        assertTrue(l.get("peak_ratio").asDouble() >= 19.5, "20x normal");
        assertTrue(lv.contains("|daily_median:" + l.get("daily_median").asDouble() + "|") && lv.endsWith("|notice_given:" + l.get("notice_given_at").asText() + "|left:" + l.get("left_at").asText()), lv);
        String end = lv.replaceAll(".*\\|peak_window_end:([0-9-]+)\\|.*", "$1");
        assertTrue(end.compareTo(l.get("notice_given_at").asText()) > 0, "the spike follows the notice: " + lv);
        List<Map<String, Object>> mail = rows("SELECT dst, amount, via FROM " + bl + " WHERE kind = 'mailed_domain' AND src = '" + leaver + "' AND via LIKE 'domain_type:personal|%'");
        assertEquals(List.of(l.get("domain").asText()), mail.stream().map(r -> (String) r.get("dst")).toList());
        assertTrue(((String) mail.get(0).get("via")).contains("|after_notice_mb:" + l.get("after_notice_mb").asDouble() + "|"), "1.2 GB after notice: " + mail.get(0).get("via"));
        String ev = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'accessed' AND src = '" + engineer + "' AND dst = 'SYS-CUSTARCH'").get(0).get("via");
        assertTrue(ev.contains("|role:Data Migration Engineer|") && ev.endsWith("|grants:2|last_grant:2026-04-02|last_ticket:" + l.get("lookalike_ticket").asText() + "|notice_given:|left:"),
                "a ticketed bulk read, nobody leaving: " + ev);
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'mailed_domain' AND src = '" + engineer + "' AND (amount >= 100 OR via LIKE 'domain_type:personal|%')"));
    }

    @Test
    void emp0007GivesFourReportsFinanceAccessWithNoTicketAndOneChangesAVendorsBankWhileTheMoverIsTicketed() throws Exception {
        JsonNode c = truth.get("access_creep");
        String noTicket = "TRY_CAST(regexp_extract(via, 'finance_no_ticket:([0-9]+)', 1) AS INTEGER)";
        List<Map<String, Object>> creep = rows("SELECT src, dst, via FROM " + bl + " WHERE kind = 'granted_by' AND " + noTicket + " > 0");
        assertEquals(ids(c.get("reports")), new TreeSet<>(creep.stream().map(r -> (String) r.get("src")).toList()), "the four reports, nobody else");
        for (Map<String, Object> r : creep) {
            String via = (String) r.get("via");
            assertEquals("EMP-0007", r.get("dst"));
            assertTrue(via.startsWith("dept:Operations|") && via.contains("PAYROLL;") && via.contains(";TREASURY") && via.contains("|finance_after_onboarding:2|finance_no_ticket:2|")
                    && via.endsWith("|last_finance_ticket:none"), "payroll and treasury, no ticket, not a finance role: " + via);
        }
        List<Map<String, Object>> vend = rows("SELECT src, via FROM " + bl + " WHERE kind = 'changed_vendor' AND dst = '" + c.get("vendor").asText() + "'");
        assertEquals(List.of(c.get("vendor_editor").asText()), vend.stream().map(r -> (String) r.get("src")).toList());
        assertTrue(((String) vend.get(0).get("via")).equals("fields:bank_account|changes:1|bank_changes:1|changer_dept:Operations|changer_role:Operations Analyst|vendor_editors:1"),
                "" + vend.get(0).get("via"));
        String mv = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'granted_by' AND src = '" + c.get("lookalike_employee").asText() + "' AND dst = '"
                + c.get("lookalike_manager").asText() + "'").get(0).get("via");
        assertTrue(mv.startsWith("dept:Finance|role:Payroll Officer|role_since:" + c.get("lookalike_role_since").asText() + "|")
                && mv.endsWith("|finance_no_ticket:0|last_finance_grant:2026-03-03|last_finance_ticket:" + c.get("lookalike_ticket").asText()), "moved, then ticketed: " + mv);
    }

    @Test
    void twoPeersApproveEachOtherFortyTimesJustUnderTheLimitWhileTheTeamLeadApprovesNormally() throws Exception {
        JsonNode m = truth.get("mutual_approvals");
        String back = "TRY_CAST(regexp_extract(via, 'approved_back:([0-9]+)', 1) AS INTEGER)";
        List<Map<String, Object>> pair = rows("SELECT src, dst, via FROM " + bl + " WHERE kind = 'approved_for' AND " + back + " > 0 ORDER BY src");
        assertEquals(new TreeSet<>(List.of(m.get("a").asText() + ">" + m.get("b").asText(), m.get("b").asText() + ">" + m.get("a").asText())),
                new TreeSet<>(pair.stream().map(r -> r.get("src") + ">" + r.get("dst")).toList()), "the only pair approving each other");
        int weekend = 0;
        for (Map<String, Object> r : pair) {
            String via = (String) r.get("via");
            int n = Integer.parseInt(via.replaceAll("^approvals:([0-9]+)\\|.*", "$1"));
            assertEquals(r.get("src").equals(m.get("a").asText()) ? m.get("a_approved_b").asInt() : m.get("b_approved_a").asInt(), n, via);
            assertTrue(via.contains("|near_limit:" + n + "|over_limit:0|") && via.contains("|approver_role:Account Manager|requester_role:Account Manager|"), "every one just under 500: " + via);
            weekend += Integer.parseInt(via.replaceAll(".*\\|weekend:([0-9]+)\\|.*", "$1"));
        }
        assertEquals(m.get("weekend").asInt(), weekend, "mostly at weekends");
        List<Map<String, Object>> team = rows("SELECT dst, via FROM " + bl + " WHERE kind = 'approved_for' AND src = '" + m.get("lookalike_team_lead").asText() + "'");
        int heavy = 0;
        for (Map<String, Object> r : team) {
            String via = (String) r.get("via");
            assertTrue(via.contains("|approved_back:0|approver_role:Sales Team Lead|requester_role:Field Sales Representative|requester_manager:" + m.get("lookalike_team_lead").asText()), via);
            if (Integer.parseInt(via.replaceAll("^approvals:([0-9]+)\\|.*", "$1")) >= 20) heavy++;
        }
        assertEquals(m.get("lookalike_heavy_reports").size(), heavy, "a busy field team, approved often and never approving back");
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
