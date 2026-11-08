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
 * The {@code la-crypto} Space Template (Basic tier) RUN end to end from a verbatim copy: the six Pipelines land the shipped extracts
 * with nothing quarantined, the {@code crypto_links} {@code sql.template} Job folds them, and the derived {@code crypto_screen_links}
 * Job builds the three screens the saved views read. Over the REAL link Datasets it pins the generator's ground truth
 * ({@code tools/gen-la-crypto-data.ground-truth.json}): every story's entities form one connected group,
 * no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole, shows the
 * look-alike beside it and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts behind each story in the files
 * themselves are pinned by {@code tools/gen-la-crypto-data.test.mjs}.
 * The Advanced tier's {@code crypto_hack_trace_counts} Job runs on the same signal: its Alert Rule fires on the deposits the stolen
 * funds reach within 5 transfers of the theft, would not without the hacked wallet or its label, never counts what the owner paid
 * before the theft, and never traces the payroll wallet whose month-end run has the peel chain's shape.
 */
class CryptoLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-crypto");
    private static final List<String> FEEDS = List.of("wallet", "transfer", "cluster_hint", "exchange_account", "kyc_person", "cashout");
    private static final String COUNTS = "crypto_hack_trace_counts";
    // the Advanced tier's counts Job is fired by the same crypto_links signal; its one row of counts is no link Dataset
    private static final List<String> JOBS = List.of("crypto_links", "crypto_screen_links", COUNTS);

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String bl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-crypto");
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
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-crypto-data.ground-truth.json").toFile());
        bl = parquet("crypto_links");
        links = triples("SELECT src, dst, kind FROM " + bl);
    }

    @Test
    void theAdvancedTierShipsInTheBasicTemplateAndItsRuleFiresOnTheRealCounts() throws Exception {
        // Edition rule (operator 2026-11-08): a minEdition: basic template carries its roster and Alert Rules, inert / unarmed on Basic
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/jobs/" + COUNTS + "_job.toon",
                "config/jobs/crypto_hack_trace_alerts_job.toon"))
            assertTrue(Files.exists(TEMPLATE.resolve(advanced)), advanced);
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/crypto_links_job.toon").toString()).cron(),
                "a commit landing while the Job runs is skipped (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches it up");
        assertTrue(Files.isRegularFile(TEMPLATE.resolve("config/examples/sla-escalation-examples.md")));
        com.gamma.alert.AlertRule rule = rule();
        assertEquals(COUNTS + "_dataset", rule.dataset());
        assertEquals(List.of(), rule.by(), COUNTS + " is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        String c = parquet(COUNTS);
        assertEquals(1, count("SELECT count(*) FROM " + c), "one row of counts");
        // the real figures on the shipped data: one hacked wallet; the stolen funds reach 2 exchange deposit addresses within 5
        // transfers of the theft (the first hop's peel at transfer 2, the third hop's at transfer 4); the owner's 4 payments to
        // merchants before the theft reach 3 more through the merchants' sweeps, which the rule does not count
        Map<String, Object> row = rows("SELECT * FROM " + c).get(0);
        assertEquals(1L, ((Number) row.get("hacked_wallets")).longValue());
        assertEquals(2L, ((Number) row.get("hack_traced_deposits")).longValue());
        assertEquals(3L, ((Number) row.get("deposits_from_earlier_payments")).longValue());
        assertEquals(1, count("SELECT count(*) FROM (SELECT " + rule.measure() + " AS v FROM " + c + ") WHERE v >= " + rule.threshold()), "the rule fires");
        // the same 2 read off the link Dataset by hand: the deposits the first four hops peel into (hacked -> hop 1 is transfer 1,
        // so hop 4's peel is transfer 5), every one of them a planted peel deposit
        JsonNode p = truth.get("peel_chain");
        List<String> firstFour = new ArrayList<>();
        for (int i = 0; i < 4; i++) firstFour.add("'" + p.get("hops").get(i).asText() + "'");
        assertEquals(2, count("SELECT count(DISTINCT dst) FROM " + bl + " WHERE kind = 'sent_to' AND src IN (" + String.join(", ", firstFour)
                + ") AND dst IN " + in(p.get("deposits"))));
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'sent_to' AND src IN (" + String.join(", ", firstFour)
                + ") AND dst NOT IN " + in(p.get("deposits")) + " AND dst NOT IN " + in(p.get("hops"))), "a hop pays only a deposit and the next hop");
        // "within 5 hops" is what keeps the rest of the chain out: at 7 transfers the trace reaches a third peel deposit
        assertEquals(3L, ((Number) countsOver("true", "label", "7").get("hack_traced_deposits")).longValue());
    }

    @Test
    void withoutTheHackedWalletOrItsLabelTheRuleWouldNotFire() throws Exception {
        String hacked = truth.get("peel_chain").get("hacked").asText();
        com.gamma.alert.AlertRule rule = rule();
        // the counts Job's own SQL over the same feeds with the hacked wallet's transfers taken out
        Map<String, Object> without = countsOver("from_address <> '" + hacked + "'", "label", "5");
        assertEquals(0L, ((Number) without.get("hack_traced_deposits")).longValue(), "without " + hacked + ": " + without);
        assertTrue(((Number) without.get("hack_traced_deposits")).doubleValue() < rule.threshold(), "it would not fire");
        assertEquals(0L, ((Number) without.get("hacked_wallets")).longValue(), without.toString());
        // and the label is what starts the trace: the same transfers with no wallet reported stolen from count nothing
        Map<String, Object> unlabelled = countsOver("true", "CASE WHEN label = 'hacked' THEN 'unknown' ELSE label END", "5");
        assertEquals(0L, ((Number) unlabelled.get("hack_traced_deposits")).longValue(), unlabelled.toString());
    }

    @Test
    void thePayrollChainHasThePeelShapeYetIsNeverTraced() throws Exception {
        JsonNode p = truth.get("peel_chain");
        List<String> payroll = new ArrayList<>(List.of("'" + p.get("lookalike_wallet").asText() + "'"));
        p.get("lookalike_staff").forEach(s -> payroll.add("'" + s.asText() + "'"));
        String set = "(" + String.join(", ", payroll) + ")";
        // every transfer of the payroll wallet and its 25 staff taken out: the counts do not move, so none of them was traced
        Map<String, Object> noPayroll = countsOver("from_address NOT IN " + set + " AND to_address NOT IN " + set, "label", "5");
        assertEquals(List.of(1L, 2L, 3L), List.of(((Number) noPayroll.get("hacked_wallets")).longValue(),
                ((Number) noPayroll.get("hack_traced_deposits")).longValue(), ((Number) noPayroll.get("deposits_from_earlier_payments")).longValue()),
                noPayroll.toString());
        // and the shape alone is not what the rule counts: even reported stolen from in place of the real wallet, the payroll's last
        // month-end run reaches staff wallets, not exchange deposit addresses, within 5 transfers
        String company = p.get("lookalike_wallet").asText();
        Map<String, Object> asIfHacked = countsOver("true", "CASE WHEN address = '" + company + "' THEN 'hacked' WHEN label = 'hacked' THEN 'unknown' ELSE label END", "5");
        assertEquals(1L, ((Number) asIfHacked.get("hacked_wallets")).longValue(), asIfHacked.toString());
        assertEquals(0L, ((Number) asIfHacked.get("hack_traced_deposits")).longValue(), asIfHacked.toString());
    }

    private static com.gamma.alert.AlertRule rule() throws Exception {
        return com.gamma.alert.AlertRule.fromMap(ToonHelper.load(
                TEMPLATE.resolve("config/registry/alert-rules/crypto_deposit_traced_to_hack.toon").toString()));
    }

    /** The counts Job's own SQL (max_hops substituted as the Job does) over the real link Dataset and feeds, transfers narrowed, labels rewritten. */
    private static Map<String, Object> countsOver(String transferFilter, String labelExpr, String maxHops) throws Exception {
        JobConfig job = JobConfig.load(TEMPLATE.resolve("config/jobs/" + COUNTS + "_job.toon").toString());
        assertEquals("5", job.require("max_hops"));
        String sql = SqlParamScanner.substitute(job.require("sql"), Map.of("max_hops", maxHops));
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW crypto_links AS SELECT * FROM " + bl);
            st.execute("CREATE VIEW transfer AS SELECT * FROM " + parquet("transfer/database", "/**/*.parquet") + " WHERE " + transferFilter);
            st.execute("CREATE VIEW wallet AS SELECT address, first_seen, " + labelExpr + " AS label FROM " + parquet("wallet/database", "/**/*.parquet"));
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
        JsonNode c = truth.get("counts");
        assertEquals(c.get("transfer_landed").asLong(), count("SELECT count(*) FROM " + parquet("transfer/database", "/**/*.parquet")));
        assertEquals(c.get("wallet_landed").asLong(), count("SELECT count(*) FROM " + parquet("wallet/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("transfer/database", "/**/*.parquet") + " WHERE CAST(tx_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("wallet/database", "/**/*.parquet") + " WHERE CAST(first_seen AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("cashout/database", "/**/*.parquet") + " WHERE CAST(cashout_at AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("wallet/database", "/**/*.parquet") + " WHERE NOT regexp_full_match(address, 'syn1[0-9a-z]{26}')"),
                "every address is in the invented syn1 format, which no real chain uses");
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + bl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + bl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("cashed_out_to", "deposit_of", "kyc_of", "mixer_exit", "same_cluster", "sent_to"), new ArrayList<>(perKind.keySet()));
        JsonNode c = truth.get("counts");
        assertEquals(c.get("exchange_account_landed").asLong(), perKind.get("deposit_of"), "one deposit address per account");
        assertEquals(c.get("exchange_account_landed").asLong(), perKind.get("kyc_of"), "one KYC person per account");
        assertEquals(c.get("cluster_hint_landed").asLong(), perKind.get("same_cluster"), "one cluster per hinted address");
        assertEquals(truth.get("mixer_hop").get("candidate_pairs_landed").asLong(), perKind.get("mixer_exit"),
                "one mixer_exit per input address and exit recipient within 6 hours and 90-100 %");
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
    void theHackIsPeeledOverEightyHopsIntoSixDepositsAndThePayrollChainPaysTheSameStaffEveryMonth() throws Exception {
        JsonNode p = truth.get("peel_chain");
        Set<String> hops = ids(p.get("hops"));
        Map<String, Object> theft = rows("SELECT amount, via FROM " + bl + " WHERE kind = 'sent_to' AND src = '" + p.get("hacked").asText()
                + "' AND dst = '" + p.get("hops").get(0).asText() + "'").get(0);
        assertEquals(p.get("stolen").asDouble(), ((Number) theft.get("amount")).doubleValue(), 1e-6);
        assertTrue(((String) theft.get("via")).contains("|src_label:hacked|") && ((String) theft.get("via")).endsWith("|src_hop:0|dst_hop:1"), (String) theft.get("via"));
        List<Map<String, Object>> out = rows("SELECT dst, via FROM " + bl + " WHERE kind = 'sent_to' AND src IN " + in(p.get("hops")));
        assertEquals(160, out.size(), "every hop pays two addresses: a peel and the change");
        for (Map<String, Object> r : out)
            assertTrue(((String) r.get("via")).startsWith("transfers:1|") && ((String) r.get("via")).contains("|src_hop:1|"), "one transfer each: " + r.get("via"));
        Set<String> peeled = new TreeSet<>(out.stream().map(r -> (String) r.get("dst")).filter(d -> !hops.contains(d)).toList());
        peeled.remove(p.get("keeper").asText());
        assertEquals(ids(p.get("deposits")), peeled, "the peels land at six exchange deposit addresses, the rest stays with the last change address");
        assertEquals(ids(p.get("accounts")), new TreeSet<>(rows("SELECT dst FROM " + bl + " WHERE kind = 'deposit_of' AND src IN " + in(p.get("deposits")))
                .stream().map(r -> (String) r.get("dst")).toList()));
        assertEquals(ids(p.get("persons")), new TreeSet<>(rows("SELECT dst FROM " + bl + " WHERE kind = 'kyc_of' AND src IN " + in(p.get("accounts")))
                .stream().map(r -> (String) r.get("dst")).toList()));
        String company = p.get("lookalike_wallet").asText();
        assertEquals(12, count("SELECT count(*) FROM " + bl + " WHERE kind = 'sent_to' AND src = '" + company + "' AND ends_with(via, '|dst_hop:1')"),
                "one payroll run a month starts at the company wallet");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'sent_to' AND (src = '" + company + "' OR dst = '" + company
                + "') AND contains(via, 'label:hacked|')"), "nothing hacked feeds the payroll");
        for (JsonNode s : p.get("lookalike_staff"))
            assertEquals(12, count("SELECT count(*) FROM " + bl + " WHERE kind = 'sent_to' AND dst = '" + s.asText() + "' AND contains(via, '|src_hop:1|')"),
                    "the same staff wallet is paid by a hop of every monthly run: " + s.asText());
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'deposit_of' AND src IN " + in(p.get("lookalike_staff"))),
                "the payroll pays personal wallets, not exchange deposit addresses");
    }

    @Test
    void onlyTheRansomLotsLeaveTheMixerWithinTwoHoursAtNinetyEightPercentAndTheNearMissesDoNot() throws Exception {
        JsonNode m = truth.get("mixer_hop");
        List<Map<String, Object>> hit = rows("SELECT src, dst, via FROM " + bl + " WHERE kind = 'mixer_exit' AND contains(via, '|matched:yes|')");
        assertEquals(3, hit.size());
        for (Map<String, Object> r : hit) {
            String via = (String) r.get("via");
            assertEquals(m.get("input_address").asText(), r.get("src"));
            assertTrue(Integer.parseInt(via.replaceAll(".*\\|minutes:([0-9]+)\\|.*", "$1")) <= 120
                    && Double.parseDouble(via.replaceAll(".*\\|ratio:([0-9.]+)\\|.*", "$1")) >= 0.98, "within 2 hours and 2 %: " + via);
        }
        assertEquals(ids(m.get("exits")), new TreeSet<>(hit.stream().map(r -> (String) r.get("dst")).toList()));
        assertEquals(Set.of(m.get("deposit").asText()), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + bl + " WHERE kind = 'sent_to' AND src IN " + in(m.get("exits")))
                .stream().map(r -> (String) r.get("dst")).toList()), "every exit is deposited at one exchange account");
        assertEquals(m.get("account").asText(), rows("SELECT dst FROM " + bl + " WHERE kind = 'deposit_of' AND src = '" + m.get("deposit").asText() + "'").get(0).get("dst"));
        String kyc = (String) rows("SELECT dst, via FROM " + bl + " WHERE kind = 'kyc_of' AND src = '" + m.get("account").asText() + "'").get(0).get("via");
        assertTrue(kyc.startsWith("exchange:" + m.get("exchange").asText() + "|"), kyc);
        for (JsonNode la : m.get("lookalikes")) {
            List<Map<String, Object>> near = rows("SELECT dst, via FROM " + bl + " WHERE kind = 'mixer_exit' AND src = '" + la.get("address").asText() + "'");
            for (Map<String, Object> r : near) assertTrue(((String) r.get("via")).contains("|matched:no|"), "a near miss, never a match: " + r.get("via"));
            assertTrue(near.stream().anyMatch(r -> la.get("other_exit").asText().equals(r.get("dst"))));
        }
    }

    @Test
    void oneClusterFundsFortyAccountsThatCashOutToThreeBanksAndTheOtcDeskCustomersCashOutToTheirOwn() throws Exception {
        JsonNode c = truth.get("converging_cash_out");
        String cl = c.get("cluster").asText();
        assertEquals(ids(c.get("addresses")), new TreeSet<>(rows("SELECT src FROM " + bl + " WHERE kind = 'same_cluster' AND dst = '" + cl + "'")
                .stream().map(r -> (String) r.get("src")).toList()));
        String funded = "SELECT DISTINCT d.dst AS acct, d.via FROM " + bl + " s JOIN " + bl + " d ON d.kind = 'deposit_of' AND d.src = s.dst WHERE s.kind = 'sent_to' AND s.src IN ";
        List<Map<String, Object>> accts = rows(funded + in(c.get("addresses")));
        assertEquals(ids(c.get("accounts")), new TreeSet<>(accts.stream().map(r -> (String) r.get("acct")).toList()), "the cluster pays exactly the 40 accounts");
        assertEquals(22, accts.stream().filter(r -> ((String) r.get("via")).startsWith("exchange:Fernhill Coin Exchange|")).count());
        assertEquals(18, accts.stream().filter(r -> ((String) r.get("via")).startsWith("exchange:Pebblebrook Exchange|")).count());
        assertEquals(40, count("SELECT count(DISTINCT regexp_extract(via, '\\|name:([^|]*)\\|', 1)) FROM " + bl + " WHERE kind = 'kyc_of' AND src IN " + in(c.get("accounts"))),
                "forty different names");
        List<Map<String, Object>> co = rows("SELECT dst, via FROM " + bl + " WHERE kind = 'cashed_out_to' AND src IN " + in(c.get("accounts")));
        assertEquals(ids(c.get("banks")), new TreeSet<>(co.stream().map(r -> (String) r.get("dst")).toList()), "40 accounts, three bank accounts");
        assertEquals(40, co.size());
        for (Map<String, Object> r : co) assertTrue(Integer.parseInt(((String) r.get("via")).replaceAll(".*\\|accounts_to_this_bank:([0-9]+)$", "$1")) >= 13, (String) r.get("via"));
        List<Map<String, Object>> otc = rows(funded + "(SELECT src FROM " + bl + " WHERE kind = 'same_cluster' AND dst = '" + c.get("lookalike_cluster").asText() + "')");
        assertEquals(ids(c.get("lookalike_accounts")), new TreeSet<>(otc.stream().map(r -> (String) r.get("acct")).toList()), "the desk pays its 60 customers");
        assertTrue(count("SELECT count(DISTINCT dst) FROM " + bl + " WHERE kind = 'cashed_out_to' AND src IN " + in(c.get("lookalike_accounts"))) >= 50,
                "each customer cashes out to a bank account of their own");
        assertEquals(0, count("SELECT count(*) FROM " + bl + " WHERE kind = 'cashed_out_to' AND src IN " + in(c.get("lookalike_accounts"))
                + " AND TRY_CAST(regexp_extract(via, 'accounts_to_this_bank:([0-9]+)$', 1) AS INTEGER) > 2"),
                "a customer's bank account takes that customer's own accounts' cash-outs only (one or two accounts)");
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
