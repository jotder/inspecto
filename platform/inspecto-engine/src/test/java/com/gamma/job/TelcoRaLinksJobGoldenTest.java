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
 * The {@code la-telco-ra} Space Template (Basic tier) RUN end to end from a verbatim copy: the seven Pipelines land the shipped
 * extracts with nothing quarantined, the {@code telco_ra_links} {@code sql.template} Job folds them, and the derived
 * {@code telco_ra_screen_links} Job builds the three revenue-assurance screens the saved views read. Over the REAL link Datasets it
 * pins the generator's ground truth ({@code tools/gen-la-telco-ra-data.ground-truth.json}): every story's entities form one connected group,
 * no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole, shows the
 * look-alike beside it and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts behind each story in the files
 * themselves are pinned by {@code tools/gen-la-telco-ra-data.test.mjs}. The Advanced tier's {@code telco_ra_unbilled_counts} Job
 * runs over the same landed feeds: the Alert Rule {@code telco_ra_usage_without_billing} fires on the unbilled-usage story's 300 SIMs,
 * would not without them, and never counts the test SIMs on the known list.
 */
class TelcoRaLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-telco-ra");
    private static final List<String> FEEDS = List.of("dealer", "subscriber", "network_usage", "billing_record", "topup", "enterprise_trunk", "trunk_call");
    private static final List<String> JOBS = List.of("telco_ra_links", "telco_ra_screen_links");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String bl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-telco-ra");
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
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(jobs, new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform())) {
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
        // then the Advanced tier's counts Job in a service of its own, so no signal-fired run races a read
        JobConfig counts = JobConfig.load(space.resolve("config/jobs/telco_ra_unbilled_counts_job.toon").toString());
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(counts), new ConsignmentEventBus(), s, null,
                     space.resolve("audit").toString(), null, null, data.toString(), null, platform())) {
            js.eventLog(com.gamma.event.EventLog.current());
            js.start();
            assertTrue(js.triggerRun(counts.name(), null, Map.of()).isPresent(), counts.name());
            JobRun r = awaitRun(js, counts.name());
            assertNotNull(r, counts.name() + " never ran");
            assertEquals("SUCCESS", r.status(), counts.name() + " failed: " + r.message());
        }
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-telco-ra-data.ground-truth.json").toFile());
        bl = parquet("telco_ra_links");
        links = triples("SELECT src, dst, kind FROM " + bl);
    }

    private static PlatformServiceRegistry platform() {
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
        // the built-in Job Types that declare these grants must validate at registration; this test sends nothing
        for (Map.Entry<String, String> e : Map.of("mail", "com.gamma.notify.MailAccess",
                "notifications", "com.gamma.notify.NotificationAccess", "incidents", "com.gamma.objects.IncidentAccess",
                "objects", "com.gamma.objects.ObjectAccess", "datasets", "com.gamma.query.DatasetAccess",
                "schema", "com.gamma.pipeline.SchemaAccess", "link-index", "com.gamma.linkindex.LinkIndexAccess",
                "alerts", "com.gamma.alert.AlertAccess").entrySet())
            unused(platform, e.getKey(), e.getValue());
        return platform;
    }

    @Test
    void theAdvancedTierShipsInTheBasicTemplateAndItsRuleFiresOnTheRealCounts() throws Exception {
        // Edition rule (operator 2026-11-08): a minEdition: basic template carries its roster and Alert Rules, inert / unarmed on Basic
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/jobs/telco_ra_unbilled_counts_job.toon",
                "config/jobs/telco_ra_unbilled_alerts_job.toon"))
            assertTrue(Files.exists(TEMPLATE.resolve(advanced)), advanced);
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/telco_ra_links_job.toon").toString()).cron(),
                "a commit landing while the Job runs is skipped (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches it up");
        assertTrue(Files.isRegularFile(TEMPLATE.resolve("config/examples/sla-escalation-examples.md")));
        com.gamma.alert.AlertRule rule = rule();
        assertEquals("telco_ra_unbilled_counts_dataset", rule.dataset());
        assertEquals(List.of(), rule.by(), "telco_ra_unbilled_counts is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        String c = parquet("telco_ra_unbilled_counts");
        assertEquals(1, count("SELECT count(*) FROM " + c), "one row of counts");
        // the real figures on the shipped data: the 300 SIMs of the unbilled-usage story; the 40 test SIMs on the known list are
        // seen and not counted; 62 ordinary subscribers (50 with one dropped month, 12 whose first two months were never billed) neither
        JsonNode u = truth.get("unbilled_usage");
        Map<String, Object> row = rows("SELECT * FROM " + c).get(0);
        assertEquals(300L, ((Number) row.get("unbilled_subscribers")).longValue());
        assertEquals((long) u.get("subscribers").size(), ((Number) row.get("unbilled_subscribers")).longValue());
        assertEquals((long) u.get("test_sims").size(), ((Number) row.get("known_list_subscribers")).longValue());
        assertEquals((long) u.get("late_billing").size() + u.get("dropped_months").size(), ((Number) row.get("other_unbilled_subscribers")).longValue());
        assertEquals(1, count("SELECT count(*) FROM (SELECT " + rule.measure() + " AS v FROM " + c + ") WHERE v >= " + rule.threshold()), "the rule fires");
    }

    @Test
    void withoutTheUnbilledDealersSimsTheRuleWouldNotFire() throws Exception {
        // the counts Job's own SQL over the same feeds with the story's 300 SIMs taken out: nothing left to count. billing_record
        // stays whole in these narrowed runs: a month is judged once its billing file has landed, and these SIMs have no billing of their own
        JsonNode u = truth.get("unbilled_usage");
        String notStory = "msisdn NOT IN " + in(u.get("subscribers"));
        Map<String, Object> without = countsOver(notStory, notStory, "true");
        com.gamma.alert.AlertRule rule = rule();
        assertEquals(0L, ((Number) without.get("unbilled_subscribers")).longValue(), "no other subscriber is counted: " + without);
        assertTrue(((Number) without.get("unbilled_subscribers")).doubleValue() < rule.threshold(), "without the story it would not fire: " + without);
        // and the story's SIMs alone are all counted
        String story = "msisdn IN " + in(u.get("subscribers"));
        assertEquals(300L, ((Number) countsOver(story, story, "true").get("unbilled_subscribers")).longValue());
    }

    @Test
    void theTestSimsOnTheKnownListAndTheOrdinaryLeakageNeverCount() throws Exception {
        JsonNode u = truth.get("unbilled_usage");
        // the test SIMs are used and never billed, as the story's are: only the known list keeps them out
        String test = "msisdn IN " + in(u.get("test_sims"));
        Map<String, Object> only = countsOver(test, test, "true");
        assertEquals(0L, ((Number) only.get("unbilled_subscribers")).longValue(), "the known list: " + only);
        assertEquals((long) u.get("test_sims").size(), ((Number) only.get("known_list_subscribers")).longValue(), "seen, not counted: " + only);
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'activated_by' AND src IN " + in(u.get("test_sims"))
                + " AND TRY_CAST(regexp_extract(via, 'unbilled_months:([0-9]+)', 1) AS INTEGER) < 2"), "every test SIM is unbilled in two months or more");
        // a single dropped month, or a provisioning lag whose later months reached billing, is not usage that keeps escaping billing
        List<String> noise = new ArrayList<>();
        u.get("late_billing").forEach(n -> noise.add(n.asText()));
        u.get("dropped_months").forEach(n -> noise.add(n.asText()));
        String ordinary = "msisdn IN ('" + String.join("', '", noise) + "')";
        Map<String, Object> o = countsOver(ordinary, ordinary, "true");
        assertEquals(0L, ((Number) o.get("unbilled_subscribers")).longValue(), "ordinary leakage: " + o);
        assertEquals((long) noise.size(), ((Number) o.get("other_unbilled_subscribers")).longValue(), o.toString());
    }

    @Test
    void aMonthWhoseBillingFileHasNotLandedIsNotReadAsUnbilled() throws Exception {
        // first boot: September's usage file lands before September's billing file. Read as unbilled, every subscriber used in
        // September would have it unbilled, and the ordinary ones with one earlier unbilled month would be counted (355);
        // judged only once that month's billing lands, the count stays the story's
        Map<String, Object> early = countsOver("true", "true", "CAST(period AS DATE) < DATE '2026-09-01'");
        assertEquals(300L, ((Number) early.get("unbilled_subscribers")).longValue(), early.toString());
    }

    private static com.gamma.alert.AlertRule rule() throws Exception {
        return com.gamma.alert.AlertRule.fromMap(ToonHelper.load(
                TEMPLATE.resolve("config/registry/alert-rules/telco_ra_usage_without_billing.toon").toString()));
    }

    /** The counts Job's own SQL (its params substituted as the Job does) over the real landed feeds, each narrowed by a predicate. */
    private static Map<String, Object> countsOver(String subscriberFilter, String usageFilter, String billingFilter) throws Exception {
        JobConfig job = JobConfig.load(TEMPLATE.resolve("config/jobs/telco_ra_unbilled_counts_job.toon").toString());
        String sql = SqlParamScanner.substitute(job.require("sql"), Map.of("min_unbilled_months", job.require("min_unbilled_months")));
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW subscriber AS SELECT * FROM " + parquet("subscriber/database", "/**/*.parquet") + " WHERE " + subscriberFilter);
            st.execute("CREATE VIEW network_usage AS SELECT * FROM " + parquet("network_usage/database", "/**/*.parquet") + " WHERE " + usageFilter);
            st.execute("CREATE VIEW billing_record AS SELECT * FROM " + parquet("billing_record/database", "/**/*.parquet") + " WHERE " + billingFilter);
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
        assertEquals(truth.get("counts").get("network_usage_landed").asLong(), count("SELECT count(*) FROM " + parquet("network_usage/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("network_usage/database", "/**/*.parquet") + " WHERE CAST(period AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("subscriber/database", "/**/*.parquet") + " WHERE CAST(activated_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("trunk_call/database", "/**/*.parquet") + " WHERE CAST(call_date AS DATE) >= DATE '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndFiveKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + bl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + bl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("activated_by", "activated_on", "called_country", "topped_up", "trunk_of"), new ArrayList<>(perKind.keySet()));
        assertEquals(truth.get("counts").get("subscriber_landed").asLong(), perKind.get("activated_by"), "one dealer per landed subscriber");
        assertTrue(perKind.get("activated_on") < perKind.get("activated_by"), "some older activations recorded no device");
        assertEquals(truth.get("counts").get("enterprise_trunk").asLong(), perKind.get("trunk_of"), "one customer per trunk");
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
    void theUnbilledSimsAreOneDealersOneDayAndTheTestSimsAreOnTheKnownList() throws Exception {
        JsonNode u = truth.get("unbilled_usage");
        String unbilled = "TRY_CAST(regexp_extract(via, 'unbilled_months:([0-9]+)', 1) AS INTEGER)";
        List<Map<String, Object>> over = rows("SELECT src, dst, via FROM " + bl + " WHERE kind = 'activated_by' AND " + unbilled + " >= 2");
        Set<String> expected = ids(u.get("subscribers"));
        expected.addAll(ids(u.get("test_sims")));
        expected.addAll(ids(u.get("late_billing")));
        assertEquals(expected, new TreeSet<>(over.stream().map(r -> (String) r.get("src")).toList()), "only these are unbilled in two months or more");
        for (Map<String, Object> r : over) {
            String via = (String) r.get("via");
            if (ids(u.get("subscribers")).contains((String) r.get("src"))) {
                assertEquals(u.get("dealer").asText(), r.get("dst"));
                assertTrue(via.contains("|device:" + u.get("device").asText() + "|") && via.contains("|topups:0|")
                        && via.contains("|usage_months:7|unbilled_months:7|") && via.contains("|billed_minutes:0|"), "used, never billed or topped up: " + via);
            } else if (ids(u.get("test_sims")).contains((String) r.get("src"))) {
                assertEquals(u.get("test_dealer").asText(), r.get("dst"));
                assertTrue(via.startsWith("plan:Internal test|"), "a test SIM is on the known list: " + via);
            } else {
                assertTrue(via.contains("|unbilled_months:2|") && !via.contains("|billed_minutes:0|"), "billed late, then billed: " + via);
            }
        }
        assertEquals(300, count("SELECT count(*) FROM " + bl + " WHERE kind = 'activated_by' AND dst = '" + u.get("dealer").asText()
                + "' AND CAST(ts AS DATE) = DATE '" + u.get("day").asText() + "'"), "300 activations on one day");
        assertEquals(300, count("SELECT count(*) FROM " + bl + " WHERE kind = 'activated_on' AND src IN " + in(u.get("subscribers"))
                + " AND dst = '" + u.get("device").asText() + "'"), "on one device");
    }

    @Test
    void theCommissionSimsTopUpOnceAndGoDormantAndTheStudentsKeepUsingTheirs() throws Exception {
        JsonNode d = truth.get("dealer_commission_fraud");
        List<Map<String, Object>> fraud = rows("SELECT src, dst, amount, via FROM " + bl + " WHERE kind = 'activated_by' AND src IN " + in(d.get("subscribers")));
        assertEquals(400, fraud.size());
        for (Map<String, Object> r : fraud) {
            String via = (String) r.get("via");
            assertEquals(d.get("dealer").asText(), r.get("dst"));
            assertEquals(5.0, ((Number) r.get("amount")).doubleValue(), "one 5.00 top-up: " + via);
            assertTrue(via.contains("|topups:1|") && via.contains("|usage_months:1|"), "one top-up, then dormant: " + via);
        }
        assertEquals(400, count("SELECT count(*) FROM " + bl + " WHERE kind = 'topped_up' AND src IN " + in(d.get("subscribers")) + " AND dst = '" + d.get("pos").asText() + "'"),
                "every one at the dealer's own shop");
        assertEquals(ids(d.get("devices")), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl + " WHERE kind = 'activated_on' AND src IN " + in(d.get("subscribers")))
                .stream().map(r -> (String) r.get("dst")).toList()), "on the dealer's three devices");
        for (Map<String, Object> r : rows("SELECT dst, via FROM " + bl + " WHERE kind = 'activated_by' AND src IN " + in(d.get("lookalike_subscribers")))) {
            String via = (String) r.get("via");
            assertEquals(d.get("lookalike_dealer").asText(), r.get("dst"));
            assertTrue(via.contains("|usage_months:12|") && Integer.parseInt(via.replaceAll(".*\\|topups:([0-9]+)\\|.*", "$1")) >= 6,
                    "a student keeps topping up and using the SIM: " + via);
        }
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'topped_up' AND src IN " + in(d.get("lookalike_subscribers")) + " AND dst = '" + d.get("pos").asText() + "'"));
    }

    @Test
    void theHackedTrunksCallPremiumRangesOffHoursAndTheLookAlikeCallsItsOffice() throws Exception {
        JsonNode p = truth.get("pbx_hacking");
        String offHours = "TRY_CAST(regexp_extract(via, 'off_hours_minutes:([0-9]+)', 1) AS INTEGER)";
        List<Map<String, Object>> prn = rows("SELECT src, dst, via FROM " + bl + " WHERE kind = 'called_country' AND dst LIKE '%-PRN' AND " + offHours + " >= 300");
        assertEquals(ids(p.get("trunks")), new TreeSet<>(prn.stream().map(r -> (String) r.get("src")).toList()), "only the four call premium ranges off hours");
        assertEquals(ids(p.get("nodes")), new TreeSet<>(prn.stream().map(r -> (String) r.get("dst")).toList()), "in three countries");
        for (Map<String, Object> r : prn) {
            String via = (String) r.get("via");
            assertEquals(via.replaceAll(".*\\|minutes:([0-9]+)\\|.*", "$1"), via.replaceAll(".*\\|off_hours_minutes:([0-9]+)\\|.*", "$1"), "every minute off hours: " + via);
        }
        assertEquals(ids(p.get("customers")), new TreeSet<>(rows("SELECT dst FROM " + bl + " WHERE kind = 'trunk_of' AND src IN " + in(p.get("trunks")))
                .stream().map(r -> (String) r.get("dst")).toList()), "four different firms");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'called_country' AND dst IN " + in(p.get("nodes")) + " AND src NOT IN " + in(p.get("trunks"))),
                "nobody else calls those premium ranges");
        String via = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'called_country' AND src = '" + p.get("lookalike_trunk").asText() + "' AND dst = 'CTRY-SG-GEO'").get(0).get("via");
        assertTrue(via.contains("number_type:geographic|") && via.endsWith("|weekend_minutes:0")
                && Long.parseLong(via.replaceAll(".*\\|night_minutes:([0-9]+)\\|.*", "$1")) >= 300, "working nights to a geographic number, never weekends: " + via);
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'called_country' AND src = '" + p.get("lookalike_trunk").asText() + "' AND dst LIKE '%-PRN'"));
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
