package com.gamma.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-police} Space Template RUN end to end from a verbatim copy: the eleven Pipelines ingest the shipped extracts
 * ({@code data/inbox/<feed>}, written by {@code tools/gen-la-police-data.py}), the {@code police_links} Job folds them into
 * the link Dataset, and the three derived Jobs build the Datasets the saved views read. Pins the link contract and, against
 * the generator's ground truth ({@code tools/la-police-ground-truth.json}), that every story's entities link up inside the
 * Dataset of its saved view and that no benign look-alike is linked to any story entity
 * (plan docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, la-police).
 */
class LaPoliceLinksJobTest {

    private static final Path ROOT = Path.of("..", "..").toAbsolutePath().normalize();
    private static final Path TEMPLATE = ROOT.resolve("spaces/_templates/la-police");
    private static final List<String> FEEDS = List.of("incident_report", "person", "incident_person", "vehicle", "plate_read",
            "call_record", "handset", "custody", "dealer_sale", "property_stolen", "address");
    private static final List<String> DERIVED = List.of("police_property_links", "police_call_links", "police_witness_links");
    private static final Set<String> KINDS = Set.of("called", "co_arrested", "involved_in", "keeper_of", "seen_near", "lives_at",
            "uses_number", "sold_item", "item_match");

    @TempDir static Path tmp;
    private static Path data;
    private static JsonNode truth;
    private static final Map<String, List<Map<String, Object>>> links = new HashMap<>();

    @BeforeAll
    static void runThePipelinesAndJobs() throws Exception {
        truth = new ObjectMapper().readTree(ROOT.resolve("tools/la-police-ground-truth.json").toFile());
        Path space = tmp.resolve("spaces").resolve("la-police");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS) {
            PipelineConfig pc = PipelineConfig.load(space.resolve("config/police/" + feed + "_pipeline.toon").toString());
            CollectorProcessor.run(pc);
            assertFalse(hasFiles(Path.of(pc.dirs().quarantine())), feed + ": nothing in the corpus is quarantined");
            assertFalse(hasFiles(Path.of(pc.dirs().errors())), feed + ": nothing in the corpus is rejected");
        }
        data = space.resolve("data");
        // the link Job first, then the derived Jobs in a service of their own, so no signal-fired run races a read
        run(space, List.of("police_links"));
        run(space, DERIVED);
        for (String sink : union(List.of("police_links"), DERIVED)) links.put(sink, rows("SELECT * FROM " + parquet(sink)));
    }

    @Test
    void everyFeedLandsEveryRow() throws Exception {
        for (String feed : FEEDS) {
            long csv;
            try (Stream<String> lines = Files.lines(TEMPLATE.resolve("data/inbox/" + feed + "/" + feed.toUpperCase() + "_20261031.csv"))) {
                csv = lines.count() - 1;
            }
            long landed = ((Number) rows("SELECT count(*) AS n FROM " + parquet(feed + "/database")).get(0).get("n")).longValue();
            assertEquals(csv, landed, feed);
        }
    }

    @Test
    void theLinkDatasetsHaveTheContractColumnsAndEachLinkOnce() {
        Set<String> kinds = new TreeSet<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : links.entrySet()) {
            assertEquals(List.of("src", "dst", "kind", "ts", "amount", "event_date", "via"), new ArrayList<>(e.getValue().get(0).keySet()), e.getKey());
            Set<String> seen = new HashSet<>();
            for (Map<String, Object> l : e.getValue()) {
                String key = l.get("src") + "|" + l.get("dst") + "|" + l.get("kind");
                assertTrue(seen.add(key), e.getKey() + ": duplicate link " + key);
                assertEquals(String.valueOf(l.get("ts")).substring(0, 10), String.valueOf(l.get("event_date")), key);
                assertTrue(((Number) l.get("amount")).doubleValue() >= 1, key + ": amount counts the records behind the link");
                String via = (String) l.get("via");
                assertTrue(via != null && !via.isBlank(), key + ": via");
                if (l.get("kind").equals("co_arrested")) assertTrue(((String) l.get("src")).compareTo((String) l.get("dst")) < 0, key);
                if (e.getKey().equals("police_links")) kinds.add((String) l.get("kind"));
            }
        }
        assertEquals(new TreeSet<>(KINDS), kinds, "the nine link kinds");
        int total = links.get("police_links").size();
        assertTrue(total >= 20_000 && total <= 60_000, "20-60 k link rows: " + total);
        // a saved view draws its whole Dataset; past the canvas's projection_node_cap (1 000 nodes) or the explorer's default
        // read (traversal.rows_default, 4 000 rows) it is cut short and a story may not be on screen
        for (String view : DERIVED) {
            assertTrue(links.get(view).size() < 4_000, view + " fits one read: " + links.get(view).size());
            assertTrue(nodes(links.get(view)).size() < 1_000, view + " fits the canvas: " + nodes(links.get(view)).size());
        }
    }

    // ---- 1 burglary series ---------------------------------------------------------------------------------------------------

    @Test
    void theBurglarySeriesJoinsUpInItsSavedViewAndTheVehicleIsSeenNearElevenScenes() throws Exception {
        JsonNode b = truth.get("burglary_series");
        Set<String> incidents = strings(b.get("incidents")), sellers = strings(b.get("sellers"));
        assertEquals(18, incidents.size());
        assertEquals(3, sellers.size());
        List<Map<String, Object>> rows = rows("SELECT incident_id, offence_type, district, start_at FROM " + parquet("incident_report/database")
                + " WHERE incident_id IN (" + in(incidents) + ")");
        assertEquals(18, rows.size());
        for (Map<String, Object> r : rows) {
            assertEquals("burglary", r.get("offence_type"));
            assertTrue(Set.of("Leeds North", "Leeds East").contains(r.get("district")), String.valueOf(r));
            String day = String.valueOf(r.get("start_at")).substring(0, 10);
            assertTrue(day.compareTo("2026-02-01") >= 0 && day.compareTo("2026-05-01") < 0, "three months: " + day);
        }
        List<Map<String, Object>> view = links.get("police_property_links");
        String plate = b.get("plate").asText();
        assertEquals(strings(b.get("vehicle_scenes")), neighbours(view, plate, "seen_near").stream().filter(incidents::contains)
                .collect(TreeSet::new, Set::add, Set::addAll), "the vehicle is seen near exactly these 11 scenes");
        assertEquals(11, strings(b.get("vehicle_scenes")).size());
        assertTrue(has(view, b.get("keeper").asText(), plate, "keeper_of"), "the partner keeps the vehicle");
        assertTrue(has(view, b.get("keeper").asText(), b.get("home").asText(), "lives_at"), "the partner lives at the home");
        assertTrue(sellers.stream().anyMatch(s -> has(view, s, b.get("home").asText(), "lives_at")), "with one of the sellers");
        Set<String> matched = new TreeSet<>();
        for (JsonNode m : b.get("matches")) {
            assertTrue(has(view, m.get("incident").asText(), m.get("seller").asText(), "item_match"), m.toString());
            assertTrue(strings(b.get("dealers")).contains(m.get("dealer").asText()), m.toString());
            matched.add(m.get("incident").asText());
        }
        for (String s : sellers) assertTrue(neighbours(view, s, "sold_item").stream().anyMatch(strings(b.get("dealers"))::contains), s);
        // the realism figures: 40 % of the items resurface, the serial matches on 60 % of those
        double resurfaced = b.get("resurfaced").asDouble() / b.get("items").asDouble(), matching = b.get("matches").size() / b.get("resurfaced").asDouble();
        assertTrue(resurfaced > .35 && resurfaced < .45, "resurfaced " + resurfaced);
        assertTrue(matching > .55 && matching < .65, "matched " + matching);
        // every story entity is in one connected piece of the saved view's graph
        Set<String> story = union(incidents, sellers, Set.of(plate, b.get("keeper").asText(), b.get("home").asText()), strings(b.get("dealers")));
        assertTrue(reach(view, plate).containsAll(story), "the series joins up: " + minus(story, reach(view, plate)));
        assertEquals(incidents, union(matched, strings(b.get("vehicle_scenes"))), "every scene has a serial match or a vehicle read");
    }

    @Test
    void theTaxiIsSeenNearManyScenesButNoneOfTheSeries() {
        JsonNode t = truth.get("lookalike_taxi");
        Set<String> scenes = neighbours(links.get("police_links"), t.get("plate").asText(), "seen_near");
        assertTrue(scenes.size() >= 30, "a busy taxi: " + scenes.size());
        assertEquals(t.get("scenes_seen_near").asInt(), scenes.size());
        assertNotJoined(t.get("plate").asText(), t.get("keeper").asText());
        assertFalse(nodes(links.get("police_property_links")).contains(t.get("plate").asText()), "the taxi is not in the series view");
    }

    // ---- 2 drug supply and 3 drug bases ---------------------------------------------------------------------------------------

    @Test
    void theControllerNumberRotatesAndItsCustomersCarryOver() {
        JsonNode d = truth.get("drug_supply");
        List<Map<String, Object>> view = links.get("police_call_links");
        List<String> controllers = list(d.get("controllers"));
        Set<String> runners = strings(d.get("runners")), customers = strings(d.get("customers"));
        assertEquals(5, controllers.size());
        assertEquals(4, runners.size());
        assertEquals(60, customers.size());
        List<Set<String>> served = new ArrayList<>();
        String previousFirst = "";
        for (int i = 0; i < controllers.size(); i++) {
            String c = controllers.get(i);
            Set<String> n = neighbours(view, c, "called");
            assertTrue(n.containsAll(runners), c + " calls all four runners");
            Set<String> cust = new TreeSet<>(n);
            cust.retainAll(customers);
            assertEquals(minus(n, runners), cust, c + ": a controller talks to runners and customers only");
            assertTrue(cust.size() >= 30, c + ": about 35 customers, " + cust.size());
            served.add(cust);
            String first = firstTs(view, c), last = lastTs(view, c);
            JsonNode period = d.get("periods").get(i);
            assertTrue(first.compareTo(period.get(0).asText()) >= 0 && last.compareTo(period.get(1).asText()) < 0, c + " stays in its period");
            assertTrue(first.compareTo(previousFirst) > 0, "the controllers take over in turn");
            previousFirst = first;
        }
        for (int i = 0; i + 1 < served.size(); i++) {
            Set<String> kept = new TreeSet<>(served.get(i));
            kept.retainAll(served.get(i + 1));
            assertTrue(kept.size() >= .7 * served.get(i).size(), "at least 70 percent carry over from " + controllers.get(i) + ": " + kept.size());
        }
        assertEquals(customers, union(served.toArray(Set[]::new)), "60 customer numbers over the five periods");
        JsonNode bases = truth.get("drug_bases");
        Set<String> story = union(new TreeSet<>(controllers), runners, customers, strings(bases.get("numbers")),
                strings(bases.get("residents")), strings(bases.get("addresses")));
        assertTrue(reach(view, controllers.get(0)).containsAll(story), "the network joins up: " + minus(story, reach(view, controllers.get(0))));
    }

    @Test
    void theRunnersRingThreeVulnerableResidentsWithNoRecord() throws Exception {
        JsonNode bases = truth.get("drug_bases");
        Set<String> runners = strings(truth.get("drug_supply").get("runners"));
        List<Map<String, Object>> view = links.get("police_call_links"), all = links.get("police_links");
        for (int i = 0; i < 3; i++) {
            String number = bases.get("numbers").get(i).asText(), resident = bases.get("residents").get(i).asText();
            int calls = 0;
            for (Map<String, Object> l : view)
                if (l.get("kind").equals("called") && (number.equals(l.get("src")) && runners.contains(l.get("dst"))
                        || number.equals(l.get("dst")) && runners.contains(l.get("src"))))
                    calls += ((Number) l.get("amount")).intValue();
            assertTrue(calls >= 12, number + ": frequent runner calls, " + calls);
            assertTrue(has(view, resident, number, "uses_number"), resident);
            assertTrue(has(view, resident, bases.get("addresses").get(i).asText(), "lives_at"), resident);
            assertTrue(neighbours(all, resident, "involved_in").isEmpty() && neighbours(all, resident, "co_arrested").isEmpty(), resident + ": no record");
            Map<String, Object> p = rows("SELECT role_flags FROM " + parquet("person/database") + " WHERE person_id = '" + resident + "'").get(0);
            assertEquals("vulnerable", p.get("role_flags"), resident);
        }
    }

    @Test
    void theDeliveryDriverAndTheSharedHouseNeverTouchTheNetwork() {
        JsonNode driver = truth.get("lookalike_driver"), house = truth.get("lookalike_shared_house");
        List<Map<String, Object>> view = links.get("police_call_links");
        Set<String> contacts = neighbours(view, driver.get("number").asText(), "called");
        assertEquals(driver.get("contacts").asInt(), contacts.size());
        assertTrue(contacts.size() >= 200, "many short-call contacts: " + contacts.size());
        for (Map<String, Object> l : view)
            if (l.get("kind").equals("called") && driver.get("number").asText().equals(l.get("src")))
                assertTrue(((Number) l.get("amount")).intValue() <= 3, "each contact once or twice: " + l);
        assertNotJoined(driver.get("number").asText(), driver.get("person").asText());
        assertTrue(neighbours(links.get("police_links"), house.get("address").asText(), "lives_at").size() >= 10, "a busy shared house");
        assertNotJoined(union(Set.of(house.get("address").asText()), strings(house.get("residents")), strings(house.get("numbers"))).toArray(String[]::new));
    }

    // ---- 4 the witness ---------------------------------------------------------------------------------------------------------

    @Test
    void theWitnessWasCoArrestedWithTheSuspectAndIsTheOnlyOne() {
        JsonNode w = truth.get("witness");
        List<Map<String, Object>> view = links.get("police_witness_links");
        String witness = w.get("witness").asText(), suspect = w.get("suspect").asText(), assault = w.get("suspect_incident").asText();
        for (String inc : strings(w.get("incidents"))) assertEquals("role:witness", via(view, witness, inc, "involved_in"), inc);
        assertEquals("role:suspect", via(view, suspect, assault, "involved_in"));
        assertEquals("custody:" + w.get("custody_group").asText(), via(view, witness.compareTo(suspect) < 0 ? witness : suspect,
                witness.compareTo(suspect) < 0 ? suspect : witness, "co_arrested"));
        assertEquals(Set.of(witness, suspect, assault), reach(view, witness).stream().filter(Set.of(witness, suspect, assault)::contains)
                .collect(TreeSet::new, Set::add, Set::addAll));
        // nobody else witnessed an incident whose suspect they were co-arrested with
        Map<String, Set<String>> suspects = new HashMap<>(), coArrested = new HashMap<>();
        for (Map<String, Object> l : links.get("police_links")) {
            String src = (String) l.get("src"), dst = (String) l.get("dst");
            if (l.get("kind").equals("involved_in") && ((String) l.get("via")).contains("role:suspect"))
                suspects.computeIfAbsent(dst, k -> new HashSet<>()).add(src);
            if (l.get("kind").equals("co_arrested")) {
                coArrested.computeIfAbsent(src, k -> new HashSet<>()).add(dst);
                coArrested.computeIfAbsent(dst, k -> new HashSet<>()).add(src);
            }
        }
        Set<String> found = new TreeSet<>();
        for (Map<String, Object> l : links.get("police_links"))
            if (l.get("kind").equals("involved_in") && ((String) l.get("via")).contains("role:witness")) {
                Set<String> both = new HashSet<>(suspects.getOrDefault((String) l.get("dst"), Set.of()));
                both.retainAll(coArrested.getOrDefault((String) l.get("src"), Set.of()));
                if (!both.isEmpty()) found.add(l.get("src") + "@" + l.get("dst"));
            }
        assertEquals(Set.of(witness + "@" + assault), found);
    }

    @Test
    void theShopkeeperIsAGenuineRepeatWitnessWithNoCustodyRecord() {
        JsonNode s = truth.get("lookalike_shopkeeper");
        String shopkeeper = s.get("person").asText();
        List<Map<String, Object>> view = links.get("police_witness_links");
        Set<String> incidents = neighbours(view, shopkeeper, "involved_in");
        assertEquals(strings(s.get("incidents")), incidents);
        assertEquals(6, incidents.size());
        for (String inc : incidents) assertEquals("role:witness", via(view, shopkeeper, inc, "involved_in"));
        assertTrue(neighbours(links.get("police_links"), shopkeeper, "co_arrested").isEmpty(), "no custody record");
        assertNotJoined(shopkeeper);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------------------

    /** Every story's entities; a look-alike holds no link of any kind to any of them. */
    private static Set<String> storyEntities() {
        JsonNode b = truth.get("burglary_series"), d = truth.get("drug_supply"), bases = truth.get("drug_bases"), w = truth.get("witness");
        return union(strings(b.get("incidents")), strings(b.get("sellers")), Set.of(b.get("plate").asText(), b.get("keeper").asText(),
                        b.get("home").asText()), strings(d.get("controllers")), strings(d.get("runners")), strings(d.get("customers")),
                strings(bases.get("numbers")), strings(bases.get("residents")), strings(bases.get("addresses")),
                Set.of(w.get("witness").asText(), w.get("suspect").asText()), strings(w.get("incidents")));
    }

    private static void assertNotJoined(String... lookalikes) {
        Set<String> story = storyEntities();
        for (String l : lookalikes) {
            assertFalse(story.contains(l), l + " is not a story entity");
            Set<String> touched = new TreeSet<>(neighbours(links.get("police_links"), l, null));
            touched.retainAll(story);
            assertTrue(touched.isEmpty(), l + " is linked to story entities " + touched);
        }
    }

    private static Set<String> neighbours(List<Map<String, Object>> ls, String node, String kind) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> l : ls) {
            if (kind != null && !kind.equals(l.get("kind"))) continue;
            if (node.equals(l.get("src"))) out.add((String) l.get("dst"));
            if (node.equals(l.get("dst"))) out.add((String) l.get("src"));
        }
        return out;
    }

    private static boolean has(List<Map<String, Object>> ls, String src, String dst, String kind) {
        return via(ls, src, dst, kind) != null;
    }

    private static String via(List<Map<String, Object>> ls, String src, String dst, String kind) {
        for (Map<String, Object> l : ls)
            if (kind.equals(l.get("kind")) && src.equals(l.get("src")) && dst.equals(l.get("dst"))) return (String) l.get("via");
        return null;
    }

    private static Set<String> nodes(List<Map<String, Object>> ls) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> l : ls) {
            out.add((String) l.get("src"));
            out.add((String) l.get("dst"));
        }
        return out;
    }

    /** Everything reachable from start over the links, either direction. */
    private static Set<String> reach(List<Map<String, Object>> ls, String start) {
        Map<String, List<String>> adj = new HashMap<>();
        for (Map<String, Object> l : ls) {
            adj.computeIfAbsent((String) l.get("src"), k -> new ArrayList<>()).add((String) l.get("dst"));
            adj.computeIfAbsent((String) l.get("dst"), k -> new ArrayList<>()).add((String) l.get("src"));
        }
        Set<String> seen = new HashSet<>(List.of(start));
        ArrayDeque<String> q = new ArrayDeque<>(seen);
        while (!q.isEmpty())
            for (String n : adj.getOrDefault(q.poll(), List.of()))
                if (seen.add(n)) q.add(n);
        return seen;
    }

    private static String firstTs(List<Map<String, Object>> ls, String node) {
        return ls.stream().filter(l -> l.get("kind").equals("called") && (node.equals(l.get("src")) || node.equals(l.get("dst"))))
                .map(l -> String.valueOf(l.get("ts")).replace('T', ' ')).min(String::compareTo).orElseThrow();
    }

    /** The latest first-call of any of the node's pairs: a lower bound of its last call, enough to keep it inside its period. */
    private static String lastTs(List<Map<String, Object>> ls, String node) {
        return ls.stream().filter(l -> l.get("kind").equals("called") && (node.equals(l.get("src")) || node.equals(l.get("dst"))))
                .map(l -> String.valueOf(l.get("ts")).replace('T', ' ')).max(String::compareTo).orElseThrow();
    }

    private static Set<String> strings(JsonNode array) {
        return new TreeSet<>(list(array));
    }

    private static List<String> list(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @SafeVarargs
    private static <T> Set<T> union(java.util.Collection<T>... parts) {
        Set<T> out = new TreeSet<>();
        for (java.util.Collection<T> p : parts) out.addAll(p);
        return out;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static String in(Set<String> ids) {
        return String.join(",", ids.stream().map(s -> "'" + s + "'").toList());
    }

    private static void run(Path space, List<String> names) throws Exception {
        List<JobConfig> jobs = new ArrayList<>();
        for (String j : names) jobs.add(JobConfig.load(space.resolve("config/jobs/" + j + "_job.toon").toString()));
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
            for (String j : names) {
                assertTrue(js.triggerRun(j, null, Map.of()).isPresent(), j);
                JobRun r = awaitRun(js, j);
                assertNotNull(r, j + " never ran");
                assertEquals("SUCCESS", r.status(), j + " failed: " + r.message());
            }
        }
    }

    private static String parquet(String sink) {
        return "read_parquet('" + data.resolve(sink).toString().replace('\\', '/') + "/*.parquet')";
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

    /** The Job's latest non-SKIPPED terminal Run, waiting up to two minutes; {@code null} when it never ran. */
    private static JobRun awaitRun(JobService js, String job) throws InterruptedException {
        long deadline = System.nanoTime() + 120_000_000_000L;
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
