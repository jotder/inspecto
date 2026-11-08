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
 * The {@code la-health} Space Template (Basic tier) RUN end to end from a verbatim copy: the nine Pipelines land the shipped
 * extracts with nothing quarantined, the {@code health_links} {@code sql.template} Job folds them, and the derived
 * {@code health_screen_links} Job builds the four program-integrity screens the saved views read. Over the REAL link Datasets it
 * pins the generator's ground truth ({@code tools/gen-la-health-data.ground-truth.json}): every story's entities form one connected
 * group, no benign look-alike has a link to any of them, and every saved view, read with its own filter, holds its story whole,
 * shows the look-alike beside it but apart from the story, and fits the canvas (under 1 000 nodes, under 4 000 rows). The facts
 * behind each story in the files themselves are pinned by {@code tools/gen-la-health-data.test.mjs}. The Advanced tier's
 * {@code health_phantom_counts} Job runs after them: its Alert Rule fires on the real counts only because of the phantom-billing
 * clinic, and never counts the look-alike clinic, the busy seaside hospital or the kickback ring.
 */
class HealthLinksJobGoldenTest {

    private static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = REPO.resolve("spaces/_templates/la-health");
    private static final List<String> FEEDS = List.of("address", "owner", "provider", "provider_owner", "member", "claim_line", "admission", "referral", "prescription");
    private static final List<String> JOBS = List.of("health_links", "health_screen_links");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static List<String[]> links;
    private static String hl;

    @BeforeAll
    static void runThePipelinesAndTheJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-health");
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
        runJobs(space, JOBS);
        // the Advanced tier's counts Job in a service of its own, after the link Job, so no signal-fired run races a read
        runJobs(space, List.of("health_phantom_counts"));
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-health-data.ground-truth.json").toFile());
        hl = parquet("health_links");
        links = triples("SELECT src, dst, kind FROM " + hl);
    }

    private static void runJobs(Path space, List<String> names) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : names) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
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
            for (String j : names) {
                // the derived Job may already be running off the main Job's produced signal; a trigger then is a no-op
                if (j.equals(names.get(0))) assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                else js.triggerRun(j, null, Map.of());
                JobRun r = awaitRun(js, j);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
    }

    @Test
    void theAdvancedTierShipsInTheBasicTemplateAndItsRuleFiresOnTheRealCounts() throws Exception {
        // Edition rule (operator 2026-11-08): a minEdition: basic template carries its roster and Alert Rules, inert / unarmed on Basic
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/jobs/health_phantom_counts_job.toon",
                "config/jobs/health_phantom_alerts_job.toon"))
            assertTrue(Files.exists(TEMPLATE.resolve(advanced)), advanced);
        assertEquals("*/5 * * * *", JobConfig.load(TEMPLATE.resolve("config/jobs/health_links_job.toon").toString()).cron(),
                "a commit landing while the Job runs is skipped (JOB-SIGNAL-OVERLAP-SKIP-1); the cron catches it up");
        assertTrue(Files.isRegularFile(TEMPLATE.resolve("config/examples/sla-escalation-examples.md")));
        com.gamma.alert.AlertRule rule = rule();
        assertEquals("health_phantom_counts_dataset", rule.dataset());
        assertEquals(List.of(), rule.by(), "health_phantom_counts is a Job output: a by column cannot be checked when the Space is created");
        assertEquals("gte", rule.comparator());
        String c = parquet("health_phantom_counts");
        assertEquals(1, count("SELECT count(*) FROM " + c), "one row of counts");
        // the real figures on the shipped data: the clinic's 89 open lines in another hospital's stay and 70 after a death, for 40
        // members, one provider; the 30 reversed date errors (15 at the look-alike clinic, 15 elsewhere) are never counted
        Map<String, Object> row = rows("SELECT * FROM " + c).get(0);
        assertEquals(89L, ((Number) row.get("lines_in_admission")).longValue());
        assertEquals(70L, ((Number) row.get("lines_after_death")).longValue());
        assertEquals(159L, ((Number) row.get("lines_in_admission_or_after_death")).longValue());
        assertEquals(40L, ((Number) row.get("members_billed")).longValue());
        assertEquals(1L, ((Number) row.get("providers_billing")).longValue());
        assertEquals(30L, ((Number) row.get("corrected_lines")).longValue());
        assertEquals(1, count("SELECT count(*) FROM (SELECT " + rule.measure() + " AS v FROM " + c + ") WHERE v >= " + rule.threshold()), "the rule fires");
    }

    @Test
    void withoutThePhantomBillingClinicTheRuleWouldNotFire() throws Exception {
        String clinic = truth.get("phantom_billing").get("clinic").asText();
        Map<String, Object> without = countsOver("src <> '" + clinic + "' AND dst <> '" + clinic + "'");
        assertEquals(0L, ((Number) without.get("providers_billing")).longValue(), "no other provider bills an open line in a stay or after a death: " + without);
        assertTrue(((Number) without.get("lines_in_admission_or_after_death")).doubleValue() < rule().threshold(), "without " + clinic + " it would not fire: " + without);
        assertEquals(30L, ((Number) without.get("corrected_lines")).longValue(), "the reversed date errors are all still there, and none counts");
    }

    @Test
    void theLookAlikeClinicTheBusyHospitalAndTheKickbackRingAreNeverCounted() throws Exception {
        JsonNode p = truth.get("phantom_billing");
        JsonNode k = truth.get("referral_kickbacks");
        // the look-alike clinic's 15 date errors fall in a stay too, but each was reversed within a week
        Map<String, Object> lookalike = countsOver("src = '" + p.get("lookalike_clinic").asText() + "'");
        assertEquals(15L, ((Number) lookalike.get("corrected_lines")).longValue(), lookalike.toString());
        assertEquals(0L, ((Number) lookalike.get("lines_in_admission_or_after_death")).longValue(), lookalike.toString());
        // the seaside hospital is the busiest node of the referral_kickbacks view (Busiest nodes ranks it first), and a naive count of
        // every open line dated inside any stay would name it for inpatient lines inside its OWN stays; the counts leave the admitting
        // hospital out, so it - and the kickback ring (two practices, the imaging centre) - count nothing
        String hospital = k.get("lookalike_hospital").asText();
        Map<String, Object> top = rows("WITH r AS (SELECT * FROM " + parquet("health_screen_links") + " WHERE screen = 'referral_concentration'), e AS (SELECT src AS n FROM r "
                + "UNION ALL SELECT dst FROM r) SELECT n, count(*) AS d FROM e GROUP BY n ORDER BY d DESC LIMIT 1").get(0);
        assertEquals(hospital, top.get("n"), "the busiest node of the referral view: " + top);
        long naive = count("SELECT count(*) FROM " + parquet("claim_line/database", "/**/*.parquet") + " cl JOIN " + parquet("admission/database", "/**/*.parquet")
                + " ad ON ad.member_id = cl.member_id AND CAST(cl.service_date AS DATE) > CAST(ad.from_date AS DATE) AND CAST(cl.service_date AS DATE) < CAST(ad.to_date AS DATE)"
                + " WHERE cl.provider_id = '" + hospital + "' AND (cl.reverses IS NULL OR trim(CAST(cl.reverses AS VARCHAR)) = '')");
        assertTrue(naive > 0, hospital + " bills lines inside its own stays, which a naive in-a-stay count would flag");
        List<String> ring = new ArrayList<>(ids(k.get("practices")));
        ring.add(k.get("imaging_centre").asText());
        ring.add(hospital);
        for (String provider : ring) {
            Map<String, Object> only = countsOver("src = '" + provider + "'");
            assertEquals(0L, ((Number) only.get("lines_in_admission_or_after_death")).longValue(), provider + " " + only);
        }
    }

    private static com.gamma.alert.AlertRule rule() throws Exception {
        return com.gamma.alert.AlertRule.fromMap(ToonHelper.load(
                TEMPLATE.resolve("config/registry/alert-rules/health_billed_in_admission_or_after_death.toon").toString()));
    }

    /** The counts Job's own SQL over the real health_links, narrowed. */
    private static Map<String, Object> countsOver(String linkFilter) throws Exception {
        String sql = JobConfig.load(TEMPLATE.resolve("config/jobs/health_phantom_counts_job.toon").toString()).require("sql");
        DuckDbUtil.loadDriver();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW health_links AS SELECT * FROM " + hl + " WHERE " + linkFilter);
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
        assertEquals(truth.get("counts").get("claim_line_landed").asLong(), count("SELECT count(*) FROM " + parquet("claim_line/database", "/**/*.parquet")));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("claim_line/database", "/**/*.parquet") + " WHERE CAST(service_date AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("prescription/database", "/**/*.parquet") + " WHERE CAST(filled_on AS DATE) >= DATE '2026-10-01'"));
        assertEquals(0, count("SELECT count(*) FROM " + parquet("referral/database", "/**/*.parquet") + " WHERE CAST(referred_on AS DATE) >= DATE '2026-10-01'"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndSixKinds() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"),
                new ArrayList<>(rows("SELECT * FROM " + hl + " LIMIT 1").get(0).keySet()));
        Map<String, Long> perKind = new LinkedHashMap<>();
        for (Map<String, Object> r : rows("SELECT kind, count(*) AS n FROM " + hl + " GROUP BY kind ORDER BY kind"))
            perKind.put((String) r.get("kind"), (Long) r.get("n"));
        assertEquals(List.of("filled_at", "lives_at", "owned_by", "prescribed", "referred_to", "treated"), new ArrayList<>(perKind.keySet()));
        assertEquals(truth.get("counts").get("provider_owner").asLong(), perKind.get("owned_by"), "one owned_by link per provider and owner");
        assertEquals(truth.get("counts").get("owner_persons").asLong(), perKind.get("lives_at"), "one home per owner who is a person");
        long total = perKind.values().stream().mapToLong(Long::longValue).sum();
        assertTrue(total >= 20_000 && total <= 60_000, "the plan's 20-60 k link rows: " + total);
        assertEquals(0, count("SELECT count(*) FROM (SELECT src, dst, kind FROM " + hl + " GROUP BY ALL HAVING count(*) > 1)"), "one row per pair");
        assertEquals(0, count("SELECT count(*) FROM " + hl + " WHERE ts IS NULL OR event_date IS NULL OR src IS NULL OR dst IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM " + hl + " WHERE kind = 'treated' AND amount < 0"), "reversed lines are left out, never netted below zero");
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
    void twoPracticesSendEightyFivePercentToTheImagingCentreOfOneOwnersSpouseAndTheSeasidePracticeToItsOnlyHospital() throws Exception {
        JsonNode k = truth.get("referral_kickbacks");
        String share = "TRY_CAST(regexp_extract(via, '[|]share:([0-9.]+)', 1) AS DOUBLE)";
        String total = "TRY_CAST(regexp_extract(via, 'of_all_referrals:([0-9]+)', 1) AS INTEGER)";
        for (String gp : ids(k.get("practices"))) {
            double s = ((Number) rows("SELECT " + share + " AS s FROM " + hl + " WHERE kind = 'referred_to' AND src = '" + gp + "' AND dst = '"
                    + k.get("imaging_centre").asText() + "'").get(0).get("s")).doubleValue();
            assertTrue(s >= 0.78 && s <= 0.92, gp + " sends about 85 % to the imaging centre: " + s);
        }
        Set<String> concentrated = new TreeSet<>(rows("SELECT src FROM " + hl + " WHERE kind = 'referred_to' AND " + total + " >= 20 AND " + share + " >= 0.6")
                .stream().map(r -> (String) r.get("src")).toList());
        Set<String> expected = ids(k.get("practices"));
        expected.addAll(ids(k.get("scarborough_practices")));
        assertEquals(expected, concentrated, "only the two city practices and the seaside town's concentrate their referrals");
        assertEquals(Set.of(k.get("practice_owner").asText(), k.get("spouse").asText()),
                new TreeSet<>(rows("SELECT src FROM " + hl + " WHERE kind = 'lives_at' AND dst = '" + k.get("home").asText() + "'").stream().map(r -> (String) r.get("src")).toList()),
                "the practice's owner and the imaging centre's owner share one home");
        assertEquals(1, count("SELECT count(*) FROM " + hl + " WHERE kind = 'owned_by' AND src = '" + k.get("imaging_centre").asText() + "' AND dst = '" + k.get("spouse").asText() + "'"));
        String gpr = k.get("lookalike_practice").asText();
        double s = ((Number) rows("SELECT " + share + " AS s FROM " + hl + " WHERE kind = 'referred_to' AND src = '" + gpr + "' AND dst = '"
                + k.get("lookalike_hospital").asText() + "'").get(0).get("s")).doubleValue();
        assertTrue(s >= 0.78 && s <= 0.92, "the seaside practice sends about 85 % to the only hospital nearby: " + s);
        assertEquals(1, count("SELECT count(*) FROM " + hl + " WHERE kind = 'owned_by' AND src = '" + k.get("lookalike_hospital").asText() + "' AND dst = '"
                + k.get("lookalike_hospital_owner").asText() + "'"), "the hospital belongs to a public trust");
        assertEquals(0, count("SELECT count(*) FROM " + hl + " WHERE kind = 'referred_to' AND dst = '" + k.get("imaging_centre").asText() + "' AND src IN "
                + in(k.get("scarborough_practices"))), "no seaside practice refers to the imaging centre");
    }

    @Test
    void theClinicBillsFortyPatientsInHospitalOrAfterDeathAndTheLookAlikesErrorsAreReversedWithinAWeek() throws Exception {
        JsonNode p = truth.get("phantom_billing");
        String n = "TRY_CAST(regexp_extract(via, '%s:([0-9]+)', 1) AS INTEGER)";
        String open = String.format(n, "in_admission") + " + " + String.format(n, "after_death");
        List<Map<String, Object>> flagged = rows("SELECT src, dst FROM " + hl + " WHERE kind = 'treated' AND " + open + " > 0");
        assertEquals(Set.of(p.get("clinic").asText()), new TreeSet<>(flagged.stream().map(r -> (String) r.get("src")).toList()),
                "only the one clinic bills open lines in a stay or after a death");
        Set<String> forty = ids(p.get("in_hospital"));
        forty.addAll(ids(p.get("after_death")));
        assertEquals(40, forty.size());
        assertEquals(forty, new TreeSet<>(flagged.stream().map(r -> (String) r.get("dst")).toList()));
        String clinic = "SELECT dst FROM " + hl + " WHERE kind = 'treated' AND src = '" + p.get("clinic").asText() + "' AND ";
        assertEquals(ids(p.get("in_hospital")), new TreeSet<>(rows(clinic + String.format(n, "in_admission") + " > 0 AND via LIKE '%|admitted_at:%'")
                .stream().map(r -> (String) r.get("dst")).toList()), "28 billed while in hospital elsewhere");
        assertEquals(ids(p.get("after_death")), new TreeSet<>(rows(clinic + String.format(n, "after_death") + " > 0 AND via LIKE '%|died:%'")
                .stream().map(r -> (String) r.get("dst")).toList()), "12 billed after they died");
        List<Map<String, Object>> fixed = rows("SELECT dst, " + String.format(n, "corrected_within_days") + " AS days, " + open + " AS open FROM " + hl
                + " WHERE kind = 'treated' AND src = '" + p.get("lookalike_clinic").asText() + "' AND " + String.format(n, "corrected") + " > 0");
        assertEquals(ids(p.get("lookalike_patients")), new TreeSet<>(fixed.stream().map(r -> (String) r.get("dst")).toList()), "the look-alike's errors are all reversed");
        for (Map<String, Object> r : fixed)
            assertTrue(((Number) r.get("days")).intValue() <= 7 && ((Number) r.get("open")).intValue() == 0, "reversed within a week, nothing left open: " + r);
        assertEquals(30, count("SELECT count(*) FROM " + hl + " WHERE kind = 'treated' AND " + String.format(n, "corrected") + " > 0"),
                "15 at the look-alike and 15 single errors elsewhere");
    }

    @Test
    void theMillsPatientsTravelAndPayCashAtTwoPharmaciesAndTheOncologyCentresAreLocalAndInsured() throws Exception {
        JsonNode m = truth.get("pill_mill");
        String n = "TRY_CAST(regexp_extract(via, '%s:([0-9]+)', 1) AS INTEGER)";
        Set<String> big = new TreeSet<>(rows("SELECT src FROM " + hl + " WHERE kind = 'prescribed' AND " + String.format(n, "strong_opioid_scripts")
                + " > 0 GROUP BY src HAVING count(*) >= 40").stream().map(r -> (String) r.get("src")).toList());
        assertEquals(Set.of(m.get("prescriber").asText(), m.get("lookalike").asText()), big, "two prescribers have 40 or more strong-opioid patients");
        String rx = "SELECT dst, " + String.format(n, "distance_km") + " AS km, " + String.format(n, "cash_scripts") + " AS cash, " + String.format(n, "strong_opioid_scripts")
                + " AS scripts, " + String.format(n, "strong_opioid_qty") + " AS qty FROM " + hl + " WHERE kind = 'prescribed' AND src = '";
        List<Map<String, Object>> mill = rows(rx + m.get("prescriber").asText() + "'");
        assertEquals(ids(m.get("patients")), new TreeSet<>(mill.stream().map(r -> (String) r.get("dst")).toList()));
        long cash = 0, scripts = 0;
        for (Map<String, Object> r : mill) {
            assertTrue(((Number) r.get("km")).intValue() > 50, "every mill patient lives over 50 km away: " + r);
            assertTrue(((Number) r.get("qty")).intValue() >= 90 * ((Number) r.get("scripts")).intValue(), "large quantities: " + r);
            cash += ((Number) r.get("cash")).longValue();
            scripts += ((Number) r.get("scripts")).longValue();
        }
        assertTrue(cash >= 0.8 * scripts, "most mill scripts are paid in cash: " + cash + " of " + scripts);
        List<Map<String, Object>> onc = rows(rx + m.get("lookalike").asText() + "'");
        assertEquals(ids(m.get("lookalike_patients")), new TreeSet<>(onc.stream().map(r -> (String) r.get("dst")).toList()));
        long oncCash = 0;
        for (Map<String, Object> r : onc) {
            assertTrue(((Number) r.get("km")).intValue() < 15, "every oncology patient is local: " + r);
            oncCash += ((Number) r.get("cash")).longValue();
        }
        assertTrue(oncCash <= 0.1 * onc.size(), "the oncology patients are insured: " + oncCash);
        assertEquals(ids(m.get("pharmacies")), new TreeSet<>(rows("SELECT DISTINCT dst FROM " + hl + " WHERE kind = 'filled_at' AND " + String.format(n, "strong_opioid_fills")
                + " > 0 AND src IN " + in(m.get("patients"))).stream().map(r -> (String) r.get("dst")).toList()), "the mill's patients fill strong opioids at its two pharmacies only");
        assertEquals(0, count("SELECT count(*) FROM " + hl + " WHERE kind = 'filled_at' AND dst IN " + in(m.get("pharmacies")) + " AND src IN " + in(m.get("lookalike_patients"))));
    }

    @Test
    void threeClinicsUnderOneOwnerBillTheTopLevelFourTimesTheirPatientsAgesAndTheElderlyCareClinicDoesNot() throws Exception {
        JsonNode u = truth.get("upcoding");
        String d = "TRY_CAST(regexp_extract(via, '%s:([0-9.]+)', 1) AS DOUBLE)";
        assertEquals(ids(u.get("clinics")), new TreeSet<>(rows("SELECT src FROM " + hl + " WHERE kind = 'owned_by' AND dst = '" + u.get("owner").asText() + "'")
                .stream().map(r -> (String) r.get("src")).toList()), "one owner, exactly the three clinics");
        String prof = "SELECT src, " + String.format(d, "top_level_share") + " AS s, " + String.format(d, "all_providers_share") + " AS a, "
                + String.format(d, "ratio_to_same_age_mix") + " AS r, " + String.format(d, "patients_80_plus") + " AS old FROM " + hl + " WHERE kind = 'owned_by' AND src IN ";
        for (Map<String, Object> r : rows(prof + in(u.get("clinics")))) {
            assertTrue(((Number) r.get("s")).doubleValue() >= 1.75 * ((Number) r.get("a")).doubleValue(), "a high top-level share: " + r);
            assertTrue(((Number) r.get("r")).doubleValue() >= 3.0, "three to five times what the patients' ages predict: " + r);
        }
        Map<String, Object> g = rows(prof + "('" + u.get("lookalike").asText() + "')").get(0);
        assertTrue(((Number) g.get("s")).doubleValue() >= 1.75 * ((Number) g.get("a")).doubleValue(), "the elderly-care clinic's share is as high: " + g);
        assertTrue(((Number) g.get("r")).doubleValue() < 1.5 && ((Number) g.get("old")).doubleValue() >= 0.95, "but its patients are over 80 and it bills as their ages predict: " + g);
        assertEquals(0, count("SELECT count(*) FROM " + hl + " WHERE kind = 'owned_by' AND " + String.format(d, "office_visits") + " >= 150 AND "
                + String.format(d, "top_level_share") + " >= 1.75 * " + String.format(d, "all_providers_share") + " AND " + String.format(d, "ratio_to_same_age_mix")
                + " >= 1.6 AND src NOT IN " + in(u.get("clinics"))), "no other high biller is far above its patients' ages");
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
