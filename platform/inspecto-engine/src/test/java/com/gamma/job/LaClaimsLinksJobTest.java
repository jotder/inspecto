package com.gamma.job;

import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-claims} Space Template's insurance-claims corpus RUN end to end from a verbatim copy: the six Pipelines
 * ingest the shipped extracts ({@code data/inbox/<feed>}, written by {@code tools/gen-la-claims-data.py}), then the two
 * {@code sql.template} Jobs build {@code claims_links} and {@code claims_policy_timing}. Pins the link contract (columns, the seven
 * kinds and their row counts), the de-duplication and placeholder rules of docs/superpower/la-multi-vertical-showcase-plan.md
 * section 4, and the planted figures that live in tables rather than in the graph (early-claim policies, the near-duplicate
 * pair NOT linked by phone). The graph-side plants (Louvain, cliques, HITS ...) are pinned by {@code LaClaimsGoldenTest} in
 * inspecto-graph over the same link counts.
 */
class LaClaimsLinksJobTest {

    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "la-claims").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("claim", "claim_party", "policy", "provider_bill", "accident_location",
            "provider_directory");
    private static final List<String> JOBS = List.of("claims_links", "claims_policy_timing");

    /** Per link kind, the rows the committed corpus folds to (the SAME numbers LaClaimsGoldenTest derives independently). */
    static final Map<String, Integer> KIND_ROWS = new TreeMap<>(Map.of("claimed_with", 678, "same_accident", 291,
            "serviced_by", 1298, "represented_by", 241, "referred_to", 22, "shares_phone", 20, "shares_address", 24));

    @TempDir static Path tmp;
    private static Path data;
    private static List<Map<String, Object>> links;

    @BeforeAll
    static void runThePipelinesAndJobs() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-claims");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS) {
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/claims/" + feed + "_pipeline.toon").toString());
            CollectorProcessor.run(pc);
            assertFalse(hasFiles(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
        }
        data = space.resolve("data");
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : JOBS) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
        PlatformServiceRegistry platform = new PlatformServiceRegistry();
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
            for (String j : JOBS) {
                assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                JobRun r = awaitRun(js, j);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
        links = rows("SELECT * FROM " + parquet("claims_links"));
    }

    @Test
    void theLinkDatasetHasTheContractColumnsAndEveryKindWithItsRowCount() throws Exception {
        assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"), new ArrayList<>(links.get(0).keySet()));
        Map<String, Integer> perKind = new TreeMap<>();
        for (Map<String, Object> l : links) perKind.merge((String) l.get("kind"), 1, Integer::sum);
        assertEquals(KIND_ROWS, perKind, "exactly the seven link kinds, with their rows");
    }

    @Test
    void everyLinkHasAnEventDayAViaAndIsMaterialisedOnce() {
        Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> l : links) {
            String key = l.get("src") + "|" + l.get("dst") + "|" + l.get("kind");
            assertTrue(seen.add(key), "duplicate link " + key);
            assertNotNull(l.get("event_date"), key + ": event_date");
            assertEquals(String.valueOf(l.get("ts")).substring(0, 10), String.valueOf(l.get("event_date")), key + ": event_date is the day of ts");
            String via = (String) l.get("via");
            assertTrue(via != null && !via.isBlank(), key + ": via");
            if (Set.of("claimed_with", "same_accident", "shares_phone", "shares_address").contains(l.get("kind")))
                assertTrue(((String) l.get("src")).compareTo((String) l.get("dst")) < 0, key + ": a symmetric link is stored once, src < dst");
        }
    }

    @Test
    void blankAndPlaceholderSharedValuesMakeNoLink() {
        for (Map<String, Object> l : links) {
            String kind = (String) l.get("kind");
            if (!kind.startsWith("shares_")) continue;
            String via = (String) l.get("via");
            assertFalse(via.matches(".*phone:0+(\\||$).*"), "the all-zero placeholder phone made a link: " + via);
            assertFalse(via.toLowerCase().contains("unknown"), "the UNKNOWN placeholder address made a link: " + via);
            assertFalse(via.equals("phone:") || via.equals("address:"), "a blank value made a link: " + via);
        }
    }

    @Test
    void theNearDuplicateIsNotLinkedByPhoneBecauseItIsWrittenNationally() throws Exception {
        // the same number as a ring member's, but 07700... against +447700...: digits differ, so the fold makes no link; identity resolution joins them
        List<Map<String, Object>> pair = rows("SELECT a.party_id AS a, b.party_id AS b FROM claim_party_rows a JOIN claim_party_rows b "
                + "ON a.dob = b.dob AND a.party_id <> b.party_id AND '0' || substr(a.phone, 4) = b.phone");
        assertEquals(1, pair.size(), "exactly one national/E.164 pair of the same number and date of birth");
        String a = (String) pair.get(0).get("a"), b = (String) pair.get(0).get("b");
        for (Map<String, Object> l : links)
            if (l.get("kind").equals("shares_phone")) {
                boolean both = Set.of(l.get("src"), l.get("dst")).containsAll(Set.of(a, b));
                assertFalse(both, "the near-duplicate pair must not share a phone link");
            }
    }

    @Test
    void sevenPoliciesWereClaimedOnWithinTwentyDaysAndAllCameThroughOneBroker() throws Exception {
        List<Map<String, Object>> early = rows("SELECT broker_id, count(*) AS n FROM " + parquet("claims_policy_timing")
                + " WHERE days_to_claim <= 20 GROUP BY broker_id");
        assertEquals(1, early.size(), "one broker: " + early);
        assertEquals("BRK-05", early.get(0).get("broker_id"));
        assertEquals(7L, ((Number) early.get(0).get("n")).longValue());
        long soonest = ((Number) rows("SELECT min(days_to_claim) AS d FROM " + parquet("claims_policy_timing")
                + " WHERE broker_id <> 'BRK-05'").get(0).get("d")).longValue();
        assertTrue(soonest > 60, "no other broker's policy is claimed on inside two months: " + soonest);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------

    private static String parquet(String sink) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + "/*.parquet')";
    }

    private static List<Map<String, Object>> rows(String sql) throws Exception {
        DuckDbUtil.loadDriver();
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement()) {
            st.execute("CREATE VIEW claim_party_rows AS SELECT DISTINCT party_id, dob, phone FROM " + parquet("claim_party/database"));
            try (ResultSet rs = st.executeQuery(sql)) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) r.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    out.add(r);
                }
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

    /** The Job's latest non-SKIPPED terminal Run, waiting up to a minute; {@code null} when it never ran. */
    private static JobRun awaitRun(JobService js, String job) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            JobRun r = js.lastRunOf(job).orElse(null);
            if (r != null && !"SKIPPED".equals(r.status()) && !"RUNNING".equals(r.status())) return r;
            Thread.sleep(50);
        }
        return null;
    }

    /** Registers a stand-in for a Platform Service this chain never calls; skipped when the interface is not on this classpath. */
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
}
