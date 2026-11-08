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
 * The {@code la-card} Space Template (Basic tier) RUN end to end from a verbatim copy: the six Pipelines land the shipped
 * extracts with nothing quarantined, then the {@code card_links} {@code sql.template} Job folds them. Over the REAL link Dataset
 * it pins the generator's ground truth ({@code tools/gen-la-card-data.ground-truth.json}): every story's entities form one
 * connected group, no benign look-alike has a link to any of them, and each saved view's link kinds surface its story's hub.
 * The facts behind each story in the files themselves (1.00 bursts, drop addresses, refunds, fraud after the pump) are pinned by
 * {@code tools/gen-la-card-data.test.mjs}.
 */
class CardLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-card");
    private static final List<String> FEEDS = List.of("cardholder", "card", "merchant", "card_auth", "chargeback", "online_account");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;

    @BeforeAll
    static void runThePipelinesAndTheJob() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-card");
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
        // the link Job first, then the Advanced tier's counts Job in a service of its own, so no signal-fired run races a read
        runJob(space, "card_links");
        runJob(space, "card_compromise_counts");
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-card-data.ground-truth.json").toFile());
        links = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT src, dst, kind FROM " + parquet("card_links")))
            links.add(new String[]{(String) r.get("src"), (String) r.get("dst"), (String) r.get("kind")});
    }

    private static void runJob(Path space, String name) throws Exception {
        JobConfig job = JobConfig.load(space.resolve("config/jobs/" + name + "_job.toon").toString());
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        // the built-in Job Types that declare these grants must validate at registration; this test sends nothing
        for (Map.Entry<String, String> e : Map.of("mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "datasets", "com.gamma.query.DatasetAccess",
                "schema", "com.gamma.pipeline.SchemaAccess", "link-index", "com.gamma.linkindex.LinkIndexAccess",
                "alerts", "com.gamma.alert.AlertAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(job), new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform)) {
            js.eventLog(com.gamma.event.EventLog.current());
            js.start();
            assertTrue(js.triggerRun(job.name(), null, Map.of()).isPresent(), job.name());
            JobRun r = awaitRun(js, job.name());
            assertNotNull(r, job.name() + " never ran");
            assertEquals("SUCCESS", r.status(), job.name() + " failed: " + r.message());
        }
    }

    @Test
    void theAdvancedTierShipsInTheBasicTemplateAndItsRuleFiresOnTheRealCounts() throws Exception {
        // Edition rule (operator 2026-11-08): a minEdition: basic template carries its roster and Alert Rules, inert / unarmed on Basic
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/jobs/card_compromise_counts_job.toon",
                "config/jobs/card_compromise_alerts_job.toon"))
            assertTrue(Files.exists(TEMPLATE.resolve(advanced)), advanced);
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/card_links_job.toon").toString()).cron(),
                "a commit landing while the Job runs is skipped (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches it up");
        com.gamma.alert.AlertRule rule = rule();
        assertEquals("card_compromise_counts_dataset", rule.dataset());
        assertEquals(List.of(), rule.by(), "card_compromise_counts is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        String c = parquet("card_compromise_counts");
        assertEquals(1, count("SELECT count(*) FROM " + c), "one row of counts");
        // the real figures on the shipped data: one compromised terminal (M-317); 60 cards approved there since its first
        // defrauded-card visit and not defrauded (10 in the 3-week window, 50 after it); the busiest other merchant saw 4
        Map<String, Object> row = rows("SELECT * FROM " + c).get(0);
        assertEquals(1L, ((Number) row.get("compromised_terminals")).longValue());
        assertEquals(60L, ((Number) row.get("cards_seen_since_compromise")).longValue());
        assertEquals(4L, ((Number) row.get("busiest_other_merchant")).longValue());
        assertTrue(((Number) row.get("busiest_other_merchant")).intValue() < minDefraudedCards(), "a busy shop stays below the threshold");
        assertEquals(1, count("SELECT count(*) FROM (SELECT " + rule.measure() + " AS v FROM " + c + ") WHERE v >= " + rule.threshold()), "the rule fires");
    }

    @Test
    void withoutTheCompromisedFuelStationTheRuleWouldNotFire() throws Exception {
        // the counts Job's own SQL over the same output with M-317 taken out: no compromised terminal, no card to reissue
        String terminal = truth.get("stories").get("common_point_of_purchase").get("hub").asText();
        Map<String, Object> without = countsOver("dst <> '" + terminal + "' AND src <> '" + terminal + "'", "merchant_id <> '" + terminal + "'");
        assertEquals(0L, ((Number) without.get("compromised_terminals")).longValue(), "no other merchant (the busy supermarket included) is one");
        com.gamma.alert.AlertRule rule = rule();
        assertTrue(((Number) without.get("cards_seen_since_compromise")).doubleValue() < rule.threshold(), "without " + terminal + " it would not fire: " + without);
        // and the cards the rule counts on the real data include the ten the generator planted in the window, none of the defrauded
        Set<String> seen = new TreeSet<>();
        for (Map<String, Object> r : rows("SELECT DISTINCT card_id FROM " + parquet("card_auth/database", "/**/*.parquet")
                + " WHERE merchant_id = '" + terminal + "' AND result = 'approved' AND auth_at >= (SELECT min(ts) FROM " + parquet("card_links")
                + " WHERE kind = 'visited_before_fraud' AND dst = '" + terminal + "')"))
            seen.add((String) r.get("card_id"));
        Set<String> inWindow = ids(truth.get("common_point_of_purchase").get("in_window_not_defrauded"));
        assertTrue(seen.containsAll(inWindow), "the ten cards paid there in the window and not defrauded");
        Set<String> defrauded = ids(truth.get("common_point_of_purchase").get("cards"));
        seen.retainAll(defrauded);
        assertEquals(defrauded.size(), seen.size(), "the defrauded cards also paid there since the compromise; the Job leaves them out");
    }

    @Test
    void theBusySupermarketIsNeverACompromisedTerminal() throws Exception {
        String supermarket = truth.get("stories").get("common_point_of_purchase").get("lookalikes").get(0).asText();
        long customers = count("SELECT count(DISTINCT card_id) FROM " + parquet("card_auth/database", "/**/*.parquet")
                + " WHERE merchant_id = '" + supermarket + "' AND result = 'approved'");
        assertTrue(customers >= 10L * minDefraudedCards(), supermarket + " is busy (" + customers + " cards): a count of traffic would flag it");
        assertEquals(0, count("SELECT count(*) FROM " + parquet("card_links") + " WHERE kind = 'visited_before_fraud' AND dst = '" + supermarket + "'"),
                "none of its customers was defrauded");
        // the counts Job's own SQL over the real output with only the supermarket's traffic: it is not a compromised terminal
        Map<String, Object> only = countsOver("dst = '" + supermarket + "' OR src = '" + supermarket + "'", "merchant_id = '" + supermarket + "'");
        assertEquals(0L, ((Number) only.get("compromised_terminals")).longValue(), supermarket + " " + only);
        assertEquals(0L, ((Number) only.get("cards_seen_since_compromise")).longValue(), supermarket + " " + only);
    }

    private static com.gamma.alert.AlertRule rule() throws Exception {
        return com.gamma.alert.AlertRule.fromMap(ToonHelper.load(
                TEMPLATE.resolve("config/registry/alert-rules/card_new_cards_at_compromised_terminal.toon").toString()));
    }

    private static JobConfig countsJob() throws Exception {
        return JobConfig.load(TEMPLATE.resolve("config/jobs/card_compromise_counts_job.toon").toString());
    }

    private static int minDefraudedCards() throws Exception {
        return Integer.parseInt(countsJob().require("min_defrauded_cards"));
    }

    /** The counts Job's own SQL (its params substituted as the Job does) over the real Job output and feeds, each narrowed. */
    private static Map<String, Object> countsOver(String linkFilter, String authFilter) throws Exception {
        JobConfig job = countsJob();
        String sql = SqlParamScanner.substitute(job.require("sql"), Map.of("min_defrauded_cards", job.require("min_defrauded_cards")));
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW card_links AS SELECT * FROM " + parquet("card_links") + " WHERE " + linkFilter);
            st.execute("CREATE VIEW card_auth AS SELECT * FROM " + parquet("card_auth/database", "/**/*.parquet") + " WHERE " + authFilter);
            st.execute("CREATE VIEW chargeback AS SELECT * FROM " + parquet("chargeback/database", "/**/*.parquet"));
            try (ResultSet rs = st.executeQuery(sql)) {
                assertTrue(rs.next(), "one row of counts");
                Map<String, Object> r = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) r.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                return r;
            }
        }
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
        assertEquals(0, count("SELECT count(*) FROM " + parquet("card_auth/database", "/**/*.parquet") + " WHERE auth_at >= TIMESTAMP '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndEightKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + parquet("card_links") + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + parquet("card_links") + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("account_card", "charged_back", "from_ip", "refunded", "ships_to", "used_at", "used_device", "visited_before_fraud"),
                new ArrayList<>(perKind.keySet()));
        assertEquals(4000L, perKind.get("account_card"), "one card on file per online account");
        assertEquals(12L, perKind.get("refunded"), "the twelve planted refunds");
        long total = perKind.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 20_000 && total <= 60_000, "the plan's 20-60 k link rows: " + total);
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + parquet("card_links") + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + parquet("card_links") + " WHERE ts IS NULL OR event_date IS NULL OR src IS NULL OR dst IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("card_links") + " WHERE kind = 'ships_to' AND dst IN ('', 'UNKNOWN', 'N/A')"),
                "a blank or placeholder address is never a link");
    }

    @Test
    void everyStorysEntitiesFormOneConnectedGroup() {
        truth.get("stories").fields().forEachRemaining(e -> {
            Set<String> members = ids(e.getValue().get("entities"));
            Map<String, String> parent = new HashMap<>();
            for (String m : members) parent.put(m, m);
            for (String[] l : links)
                if (members.contains(l[0]) && members.contains(l[1])) parent.put(root(parent, l[0]), root(parent, l[1]));
            Set<String> groups = new TreeSet<>();
            for (String m : members) groups.add(root(parent, m));
            assertEquals(1, groups.size(), e.getKey() + ": " + members.size() + " entities fall into " + groups.size() + " groups");
            String hub = e.getValue().get("hub").asText();
            assertTrue(links.stream().anyMatch(l -> l[0].equals(hub) || l[1].equals(hub)), e.getKey() + ": the hub " + hub + " is linked");
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

    @Test
    void eachSavedViewsKindsMakeItsHubTheBusiestNode() throws Exception {
        Map<String, String> viewHub = Map.of("card_testing", "card_testing", "account_takeover_ring", "account_takeover",
                "colluding_merchant_refunds", "colluding_merchant", "common_point_of_purchase", "common_point_of_purchase");
        for (Map.Entry<String, String> v : viewHub.entrySet()) {
            String toon = Files.readString(TEMPLATE.resolve("config/registry/link-analysis-views/" + v.getKey() + ".toon"));
            Set<String> kinds = new TreeSet<>();
            for (String line : toon.split("\n"))
                if (line.trim().startsWith("condition,kind,")) kinds.add(line.trim().replaceAll(".*,\"(.*)\"$", "$1"));
            assertFalse(kinds.isEmpty(), v.getKey());
            Map<String, Integer> degree = new HashMap<>();
            for (String[] l : links)
                if (kinds.contains(l[2])) {
                    degree.merge(l[0], 1, Integer::sum);
                    degree.merge(l[1], 1, Integer::sum);
                }
            String hub = truth.get("stories").get(v.getValue()).get("hub").asText();
            assertNotNull(degree.get(hub), v.getKey() + ": the hub " + hub + " is in the view");
            long busier = degree.values().stream().filter(d -> d > degree.get(hub)).count();
            assertTrue(busier < 20, v.getKey() + ": the hub " + hub + " (" + degree.get(hub) + " links) is among the 20 busiest of "
                    + degree.size() + " nodes, " + busier + " are busier");
        }
        assertEquals(0, links.stream().filter(l -> "visited_before_fraud".equals(l[2]) && "M-052".equals(l[1])).count(),
                "the supermarket look-alike is not in the common-point view");
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
