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
        JobConfig job = JobConfig.load(space.resolve("config/jobs/card_links_job.toon").toString());
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
        truth = new ObjectMapper().readTree(REPO.resolve("tools/gen-la-card-data.ground-truth.json").toFile());
        links = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT src, dst, kind FROM " + parquet("card_links")))
            links.add(new String[]{(String) r.get("src"), (String) r.get("dst"), (String) r.get("kind")});
    }

    @Test
    void theTemplateIsBasicTierOnly() throws Exception {
        assertEquals("basic", ToonHelper.load(TEMPLATE.resolve("template.toon").toString()).get("minEdition"));
        for (String advanced : List.of("config/demo-users.toon", "config/roles.toon", "config/registry/alert-rules"))
            assertFalse(Files.exists(TEMPLATE.resolve(advanced)), advanced + " belongs to the Advanced tier (wave D)");
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
        Map<String, String> viewHub = Map.of("card_testing", "card_testing", "card_takeover_ring", "account_takeover",
                "card_merchant_disputes", "colluding_merchant", "card_common_point", "common_point_of_purchase");
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
