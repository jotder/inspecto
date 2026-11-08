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
 * The {@code la-mfs} Space Template (Basic tier) RUN end to end from a verbatim copy: the seven Pipelines land the shipped extracts
 * with nothing quarantined, the {@code mfs_links} {@code sql.template} Job folds them, and the derived {@code mfs_screen_links} Job
 * builds the three fraud screens the saved views read. Over the REAL link Datasets it pins the generator's ground truth
 * ({@code tools/gen-la-mfs-data.ground-truth.json}): every story's entities form one connected group,
 * no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole, shows the
 * look-alike beside it and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts behind each story in the files
 * themselves are pinned by {@code tools/gen-la-mfs-data.test.mjs}. The Advanced tier's {@code mfs_swap_drain_counts} Job runs over
 * the real {@code mfs_links} too: the Alert Rule {@code mfs_wallet_drained_after_sim_swap} fires on the takeover wallets, would not
 * without them, and never counts the store's SIM upgrade drive.
 */
class MfsLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-mfs");
    private static final List<String> FEEDS = List.of("agent", "merchant", "wallet", "wallet_txn", "sim_event", "device_login", "agent_commission");
    // the link Job, its derived screens and the Advanced tier's counts Job (both derived Jobs fire on mfs_links' produced signal)
    private static final List<String> JOBS = List.of("mfs_links", "mfs_screen_links", "mfs_swap_drain_counts");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String bl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-mfs");
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
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-mfs-data.ground-truth.json").toFile());
        bl = parquet("mfs_links");
        links = triples("SELECT src, dst, kind FROM " + bl);
    }

    @Test
    void theAdvancedTierShipsInTheBasicTemplateAndItsRuleFiresOnTheRealCounts() throws Exception {
        // Edition rule (operator 2026-11-08): a minEdition: basic template carries its roster and Alert Rules, inert / unarmed on Basic
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/jobs/mfs_swap_drain_counts_job.toon",
                "config/jobs/mfs_swap_drain_alerts_job.toon"))
            assertTrue(Files.exists(TEMPLATE.resolve(advanced)), advanced);
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/mfs_links_job.toon").toString()).cron(),
                "a commit landing while the Job runs is skipped (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches it up");
        assertTrue(Files.isRegularFile(TEMPLATE.resolve("config/examples/sla-escalation-examples.md")));
        com.gamma.alert.AlertRule rule = rule();
        assertEquals("mfs_swap_drain_counts_dataset", rule.dataset());
        assertEquals(List.of(), rule.by(), "mfs_swap_drain_counts is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        String c = parquet("mfs_swap_drain_counts");
        assertEquals(1, count("SELECT count(*) FROM " + c), "one row of counts");
        // the real figures on the shipped data: the 30 takeover wallets, all swapped by one login (S-41); no other wallet paid out
        // more than 98.14 within 2 hours of a swap
        Map<String, Object> row = rows("SELECT * FROM " + c).get(0);
        assertEquals(30L, ((Number) row.get("drained_after_swap")).longValue());
        assertEquals(1L, ((Number) row.get("logins_behind_drains")).longValue());
        assertEquals(98.14, ((Number) row.get("largest_other_out_within_2h")).doubleValue(), 0.005);
        assertTrue(((Number) row.get("largest_other_out_within_2h")).doubleValue() < minOutWithin2h(), "a genuine customer stays below the threshold");
        assertEquals(1, count("SELECT count(*) FROM (SELECT " + rule.measure() + " AS v FROM " + c + ") WHERE v >= " + rule.threshold()), "the rule fires");
        // the wallets it counts are exactly the generator's 30 victims, and the login behind them is the story's
        JsonNode s = truth.get("sim_swap_takeover");
        String drained = "kind = 'swapped_by' AND TRY_CAST(regexp_extract(via, 'out_within_2h:([0-9.]+)', 1) AS DOUBLE) >= " + minOutWithin2h();
        assertEquals(ids(s.get("victims")), new TreeSet<>(rows("SELECT DISTINCT src FROM " + bl + " WHERE " + drained).stream().map(r -> (String) r.get("src")).toList()));
        assertEquals(Set.of(s.get("login").asText()), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl + " WHERE " + drained).stream().map(r -> (String) r.get("dst")).toList()));
    }

    @Test
    void withoutTheTakeoverWalletsTheRuleWouldNotFire() throws Exception {
        // the counts Job's own SQL over the same output with the 30 victims' swap links taken out: nothing drained, no Alert
        Map<String, Object> without = countsOver("NOT (kind = 'swapped_by' AND src IN " + in(truth.get("sim_swap_takeover").get("victims")) + ")");
        assertEquals(0L, ((Number) without.get("logins_behind_drains")).longValue(), without.toString());
        assertTrue(((Number) without.get("drained_after_swap")).doubleValue() < rule().threshold(), "without the takeover it would not fire: " + without);
    }

    @Test
    void theStoresSimUpgradeDriveIsNeverCounted() throws Exception {
        JsonNode s = truth.get("sim_swap_takeover");
        String store = s.get("lookalike_login").asText();
        // the store swaps far more SIMs than the call-centre login: a count of swaps would flag it
        assertTrue(count("SELECT max(TRY_CAST(regexp_extract(via, 'login_max_swaps_10d:([0-9]+)', 1) AS INTEGER)) FROM " + bl
                + " WHERE kind = 'swapped_by' AND dst = '" + store + "'") >= 15, store + " bursts too");
        assertEquals(ids(s.get("lookalike_customers")).size(), count("SELECT count(DISTINCT src) FROM " + bl + " WHERE kind = 'swapped_by' AND dst = '" + store + "'"));
        // the counts Job's own SQL over only the store's swaps: its customers keep their balances, so nobody is drained
        Map<String, Object> only = countsOver("kind = 'swapped_by' AND dst = '" + store + "'");
        assertEquals(0L, ((Number) only.get("drained_after_swap")).longValue(), store + " " + only);
        assertEquals(0L, ((Number) only.get("logins_behind_drains")).longValue(), store + " " + only);
    }

    private static com.gamma.alert.AlertRule rule() throws Exception {
        return com.gamma.alert.AlertRule.fromMap(ToonHelper.load(
                TEMPLATE.resolve("config/registry/alert-rules/mfs_wallet_drained_after_sim_swap.toon").toString()));
    }

    private static JobConfig countsJob() throws Exception {
        return JobConfig.load(TEMPLATE.resolve("config/jobs/mfs_swap_drain_counts_job.toon").toString());
    }

    private static double minOutWithin2h() throws Exception {
        return Double.parseDouble(countsJob().require("min_out_within_2h"));
    }

    /** The counts Job's own SQL (its params substituted as the Job does) over the real mfs_links output, narrowed. */
    private static Map<String, Object> countsOver(String linkFilter) throws Exception {
        JobConfig job = countsJob();
        String sql = SqlParamScanner.substitute(job.require("sql"), Map.of("min_out_within_2h", job.require("min_out_within_2h")));
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW mfs_links AS SELECT * FROM " + bl + " WHERE " + linkFilter);
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
        assertEquals(truth.get("counts").get("wallet_txn_landed").asLong(), count("SELECT count(*) FROM " + parquet("wallet_txn/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("wallet_txn/database", "/**/*.parquet") + " WHERE CAST(txn_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("wallet/database", "/**/*.parquet") + " WHERE CAST(opened_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("sim_event/database", "/**/*.parquet") + " WHERE CAST(event_at AS DATE) >= DATE '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSevenKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + bl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + bl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("cashed_in_at", "cashed_out_at", "logged_in_on", "paid", "registered_by", "settles_to", "swapped_by"), new ArrayList<>(perKind.keySet()));
        long agentRegistered = count("SELECT count(*) FROM " + parquet("wallet/database", "/**/*.parquet") + " WHERE nullif(trim(registered_by_agent), '') IS NOT NULL");
        assertEquals(agentRegistered, perKind.get("registered_by"), "one agent per wallet an agent registered");
        assertTrue(agentRegistered < truth.get("counts").get("wallet_landed").asLong(), "some customers registered in the app");
        assertEquals(truth.get("counts").get("merchant").asLong(), perKind.get("settles_to"), "one settlement account per merchant");
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
    void theTakeoverWalletsAreDrainedWithinTwoHoursFromNewHandsetsAndTheStoreCustomersKeepTheirBalances() throws Exception {
        JsonNode s = truth.get("sim_swap_takeover");
        String burst = "TRY_CAST(regexp_extract(via, 'login_max_swaps_10d:([0-9]+)', 1) AS INTEGER)";
        assertEquals(Set.of(s.get("login").asText(), s.get("lookalike_login").asText()), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl
                + " WHERE kind = 'swapped_by' AND " + burst + " >= 15").stream().map(r -> (String) r.get("dst")).toList()), "only two logins burst");
        List<Map<String, Object>> taken = rows("SELECT src, amount, via FROM " + bl + " WHERE kind = 'swapped_by' AND dst = '" + s.get("login").asText()
                + "' AND src IN " + in(s.get("victims")));
        assertEquals(30, taken.size());
        for (Map<String, Object> r : taken) {
            String via = (String) r.get("via");
            assertTrue(via.contains("|channel:call_centre|") && Integer.parseInt(via.replaceAll(".*\\|new_device_minutes:([0-9]+)\\|.*", "$1")) <= 60,
                    "a handset it never used, within the hour: " + via);
            assertEquals(s.get("drained").get((String) r.get("src")).asDouble(), ((Number) r.get("amount")).doubleValue(), 0.05, "drained within 2 hours: " + via);
        }
        assertEquals(30, count("SELECT count(*) FROM " + bl + " WHERE kind = 'logged_in_on' AND src IN " + in(s.get("victims")) + " AND dst IN " + in(s.get("attacker_devices"))));
        assertEquals(ids(s.get("mules")), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl + " WHERE kind = 'paid' AND src IN " + in(s.get("victims"))
                + " AND dst LIKE 'WL-%' AND ts >= TIMESTAMP '2026-06-08 00:00:00' AND ts <= TIMESTAMP '2026-06-18 23:59:59'").stream().map(r -> (String) r.get("dst")).toList()),
                "the victims' new wallet payees in those days are the five mules");
        assertEquals(ids(s.get("cash_out_agents")), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl + " WHERE kind = 'cashed_out_at' AND src IN " + in(s.get("mules")))
                .stream().map(r -> (String) r.get("dst")).toList()), "the mules cash out at two agents");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'paid' AND dst IN " + in(s.get("mules")) + " AND src NOT IN " + in(s.get("victims"))),
                "nobody else pays the mules");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'swapped_by' AND dst = '" + s.get("lookalike_login").asText()
                + "' AND TRY_CAST(regexp_extract(via, 'out_within_2h:([0-9.]+)', 1) AS DOUBLE) > 100"), "the store's customers keep their balances");
    }

    @Test
    void theSplitDepositWalletsAreTheAgentsOwnAndDoLittleElseAndTheTradersAreEverydayCustomers() throws Exception {
        JsonNode d = truth.get("split_deposits");
        List<Map<String, Object>> reg = rows("SELECT src, dst, via FROM " + bl + " WHERE kind = 'registered_by' AND src IN " + in(d.get("wallets")));
        assertEquals(120, reg.size());
        for (Map<String, Object> r : reg) {
            String via = (String) r.get("via");
            assertEquals(d.get("agent").asText(), r.get("dst"));
            assertTrue(via.startsWith("kyc:1|") && via.replaceAll(".*\\|cash_ins:([0-9]+)\\|.*", "$1").equals(via.replaceAll(".*\\|cash_ins_at_registrar:([0-9]+)\\|.*", "$1"))
                    && via.endsWith("|merchant_pays:0"), "every cash-in at its registrar, no shopping: " + via);
        }
        for (Map<String, Object> r : rows("SELECT dst, via FROM " + bl + " WHERE kind = 'cashed_in_at' AND src IN " + in(d.get("wallets")))) {
            assertEquals(d.get("agent").asText(), r.get("dst"));
            String via = (String) r.get("via");
            assertTrue(Double.parseDouble(via.replaceAll(".*\\|per_day:([0-9.]+)\\|.*", "$1")) >= 3, "several small cash-ins a visit: " + via);
        }
        for (Map<String, Object> r : rows("SELECT via FROM " + bl + " WHERE kind = 'cashed_in_at' AND dst = '" + d.get("lookalike_agent").asText() + "'"))
            assertTrue(Double.parseDouble(((String) r.get("via")).replaceAll(".*\\|per_day:([0-9.]+)\\|.*", "$1")) < 2, "one deposit a visit: " + r.get("via"));
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind IN ('cashed_in_at', 'registered_by') AND dst = '" + d.get("lookalike_agent").asText()
                + "' AND src IN " + in(d.get("wallets"))));
        assertTrue(count("SELECT count(DISTINCT src) FROM " + bl + " WHERE kind = 'paid' AND src IN " + in(d.get("lookalike_traders"))) > 150,
                "the traders also pay merchants and each other");
    }

    @Test
    void theNewMerchantIsFedOnlyByFreshlyFundedWalletsAndSettlesDailyAndTheGroceryIsNot() throws Exception {
        JsonNode m = truth.get("merchant_cash_out");
        List<Map<String, Object>> pays = rows("SELECT src, via FROM " + bl + " WHERE kind = 'paid' AND dst = '" + m.get("merchant").asText() + "'");
        assertEquals(ids(m.get("payers")), new TreeSet<>(pays.stream().map(r -> (String) r.get("src")).toList()));
        for (Map<String, Object> r : pays) {
            String via = (String) r.get("via");
            assertEquals(via.replaceAll(".*\\|payments:([0-9]+)\\|.*", "$1"), via.replaceAll(".*\\|within_60m_of_cash_in:([0-9]+)$", "$1"),
                    "every payment within 60 minutes of a cash-in: " + via);
        }
        assertEquals(ids(m.get("agents")), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl + " WHERE kind = 'cashed_in_at' AND src IN " + in(m.get("payers")))
                .stream().map(r -> (String) r.get("dst")).toList()), "funded at the three agents");
        String st = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'settles_to' AND src = '" + m.get("merchant").asText() + "' AND dst = '"
                + m.get("account").asText() + "'").get(0).get("via");
        assertTrue(st.contains("|merchant_opened:" + m.get("opened").asText() + "|")
                && st.replaceAll(".*\\|settlements:([0-9]+)\\|.*", "$1").equals(st.replaceAll(".*\\|settlement_days:([0-9]+)\\|.*", "$1"))
                && Integer.parseInt(st.replaceAll(".*\\|settlement_days:([0-9]+)\\|.*", "$1")) >= 20, "opened three weeks ago, settles every day: " + st);
        List<Map<String, Object>> g = rows("SELECT src, via FROM " + bl + " WHERE kind = 'paid' AND dst = '" + m.get("lookalike_merchant").asText() + "'");
        assertEquals(ids(m.get("lookalike_payers")), new TreeSet<>(g.stream().map(r -> (String) r.get("src")).toList()));
        assertEquals(ids(m.get("lookalike_quick_payers")), new TreeSet<>(g.stream().filter(r -> !((String) r.get("via")).endsWith("|within_60m_of_cash_in:0"))
                .map(r -> (String) r.get("src")).toList()), "a quarter of the shoppers sometimes pay right after a cash-in");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'cashed_in_at' AND src IN " + in(m.get("lookalike_payers")) + " AND dst IN " + in(m.get("agents"))));
        String gs = (String) rows("SELECT via FROM " + bl + " WHERE kind = 'settles_to' AND src = '" + m.get("lookalike_merchant").asText() + "'").get(0).get("via");
        assertTrue(Integer.parseInt(gs.replaceAll(".*\\|settlements:([0-9]+)\\|.*", "$1")) <= 53, "the grocery settles weekly: " + gs);
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
