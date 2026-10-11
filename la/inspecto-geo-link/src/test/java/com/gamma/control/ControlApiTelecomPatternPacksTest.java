package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.ConsignmentEventBus;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.job.JobConfig;
import com.gamma.job.JobRun;
import com.gamma.job.JobService;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.util.DuckDbUtil;
import com.gamma.util.Scheduler;
import com.gamma.util.ToonHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code la-showcase} Space Template's three authored telecom pattern packs ({@code config/registry/pattern-packs/
 * telecom-*.toon}) find their planted rings on the shipped seed. The template's telecom Pipelines ingest the extracts and the
 * real {@code telecom_links} Job builds the link Dataset (as {@code TelecomLinksGoldenTest} does); each pack's flat TOON
 * {@code stages} rows are mapped to the route body the way the toolbox's {@code patternPackFromContent} maps them, and sent to
 * {@code POST /inv/pattern/branching} over real HTTP. Each pack must match exactly its planted lines and no background line.
 */
/* Test-scope split package com.gamma.control, like ControlApiInvPatternTest: it drives the real dispatcher. */
class ControlApiTelecomPatternPacksTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TEMPLATE = Path.of("..", "..", "spaces", "_templates", "la-showcase").toAbsolutePath().normalize();
    private static final List<String> FEEDS = List.of("voice_cdr", "sms_cdr", "hlr_eir", "crm_kyc", "recharge", "provisioning");

    static final String IRSF_01 = "99979200001";
    static final List<String> SUBFRAUD = List.of("99979300001", "99979300002", "99979300003", "99979300004", "99979300005");
    static final List<String> MULES = List.of("99979500001", "99979500002", "99979500003", "99979500004", "99979500005");

    @TempDir static Path tmp;
    /** The whole {@code telecom_links} snapshot as a VALUES relation - every kind, so the packs run over the background too. */
    private static String linksSql;
    private static long linkRows;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void buildTheLinkDataset() throws Exception {
        Path space = tmp.resolve("spaces").resolve("la-showcase");
        try (Stream<Path> w = Files.walk(TEMPLATE)) {
            for (Path p : w.toList()) {
                Path to = space.resolve(TEMPLATE.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        for (String feed : FEEDS)
            CollectorProcessor.run(PipelineConfig.load(space.resolve("config/telecom/" + feed + "_pipeline.toon").toString()));
        Path data = space.resolve("data");
        try (Scheduler s = new Scheduler();
             JobService js = new JobService(List.of(JobConfig.load(space.resolve("config/jobs/telecom_links_job.toon").toString())),
                     new ConsignmentEventBus(), s, null, space.resolve("audit").toString(), null, null, data.toString())) {
            js.start();
            assertTrue(js.triggerRun("telecom_links", null, Map.of()).isPresent());
            JobRun r = null;
            long deadline = System.nanoTime() + 60_000_000_000L;
            while ((r = js.lastRunOf("telecom_links").orElse(null)) == null && System.nanoTime() < deadline) Thread.sleep(50);
            assertNotNull(r, "telecom_links never ran");
            assertEquals("SUCCESS", r.status(), r.message());
        }
        DuckDbUtil.loadDriver();
        List<String> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT a_msisdn, b_msisdn, link_kind, strftime(first_seen, '%Y-%m-%d %H:%M:%S') FROM read_parquet('"
                     + data.resolve("telecom_links").toString().replace('\\', '/') + "/*.parquet')")) {
            while (rs.next())
                rows.add("('" + rs.getString(1) + "','" + rs.getString(2) + "','" + rs.getString(3) + "',TIMESTAMP '" + rs.getString(4) + "')");
        }
        linkRows = rows.size();
        linksSql = "SELECT * FROM (VALUES " + String.join(",", rows) + ") AS t(a_msisdn, b_msisdn, link_kind, first_seen)";
    }

    /** One authored pack's TOON, mapped to the route's stages exactly as {@code pattern-packs.ts stagesOf} maps it. */
    private static ObjectNode body(String pack) throws Exception {
        Map<String, Object> content = ToonHelper.load(TEMPLATE.resolve("config/registry/pattern-packs/" + pack + ".toon").toString());
        assertEquals(pack, content.get("name"));
        assertEquals("telecom", content.get("category"));
        assertFalse(String.valueOf(content.get("label")).isBlank());
        assertFalse(String.valueOf(content.get("description")).isBlank());
        ObjectNode b = JSON.createObjectNode();
        b.put("dataset", "tel_ds").put("sourceCol", "a_msisdn").put("targetCol", "b_msisdn")
         .put("linkKindCol", "link_kind").put("timeCol", "first_seen").put("limit", 200);
        var stages = b.putArray("stages");
        for (Object o : (List<?>) content.get("stages")) {
            Map<?, ?> row = (Map<?, ?>) o;
            ObjectNode st = stages.addObject();
            st.put("shape", String.valueOf(row.get("shape")));
            st.put("minBranches", ((Number) row.get("minBranches")).intValue());
            if (row.get("edgeKind") instanceof String k && !k.isBlank()) st.put("edgeKind", k);
            if (row.get("windowHours") instanceof Number w && w.doubleValue() > 0) st.put("windowHours", w.doubleValue());
            if (Boolean.TRUE.equals(row.get("afterPrevious")) || "true".equals(row.get("afterPrevious"))) {
                st.put("afterPrevious", true);
                if (row.get("maxGapHours") instanceof Number g && g.doubleValue() > 0) st.put("maxGapHours", g.doubleValue());
            }
            if (Boolean.TRUE.equals(row.get("closesToStart")) || "true".equals(row.get("closesToStart"))) st.put("closesToStart", true);
            assertTrue(!(row.get("thresholdAttr") instanceof String a) || a.isBlank(), "the telecom packs carry no threshold band");
        }
        return b;
    }

    /** Run one pack over the seed: its matches' layers (layer i = the nodes at stage boundary i). */
    private List<List<List<String>>> run(String pack) throws Exception {
        Path cfg = tmp.resolve("cfg-" + pack), root = tmp.resolve("root-" + pack);
        Files.createDirectories(cfg);
        Files.createDirectories(root);
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", root.toString());
        CollectorService svc;
        ControlApi api;
        try {
            svc = new CollectorService(List.of(pipe), 3600, 1);
            api = new ControlApi(svc, 0);
            api.start();
        } finally {
            System.clearProperty("assist.write.root");
        }
        try {
            new ViewStore(root.resolve("views")).write(new ViewDefinition("tel_view", "flow-x", List.of(), linksSql, "2026-10-11T00:00:00Z"));
            new ComponentStore(root.resolve("registry")).write("dataset", "tel_ds", Map.of("view", "tel_view"));
            var r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + api.port() + "/api/v1/inv/pattern/branching"))
                    .method("POST", BodyPublishers.ofString(body(pack).toString())).build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode data = JSON.readTree(r.body()).get("data");
            assertTrue(data.get("refusal") == null || data.get("refusal").isNull(), data.toString());
            assertFalse(data.get("truncated").asBoolean(), pack + " is not truncated");
            List<List<List<String>>> out = new ArrayList<>();
            for (JsonNode m : data.get("matches")) {
                List<List<String>> layers = new ArrayList<>();
                for (JsonNode layer : m.get("layers")) {
                    List<String> l = new ArrayList<>();
                    layer.forEach(n -> l.add(n.asText()));
                    layers.add(l);
                }
                out.add(layers);
            }
            return out;
        } finally {
            api.close();
            svc.close();
        }
    }

    private static Set<String> layer(List<List<List<String>>> matches, int i) {
        Set<String> out = new TreeSet<>();
        for (List<List<String>> m : matches) out.addAll(m.get(i));
        return out;
    }

    private static Set<String> everyNode(List<List<List<String>>> matches) {
        Set<String> out = new TreeSet<>();
        for (List<List<String>> m : matches) m.forEach(out::addAll);
        return out;
    }

    private static List<String> plus(List<String> a, String... b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(List.of(b));
        return out;
    }

    @Test
    void theSeedIsTheWholeLinkDataset() {
        assertTrue(linkRows > 16_000, "every link kind, background included: " + linkRows);
    }

    /** One identity document across IRSF-01 and SUBFRAUD-01..05: each of the six collects the other five within a week. */
    @Test
    void subscriptionRingFindsTheSixLinesOnOneIdentity() throws Exception {
        List<List<List<String>>> m = run("telecom-subscription-ring");
        assertFalse(m.isEmpty(), "the planted ring is found");
        assertEquals(new TreeSet<>(plus(SUBFRAUD, IRSF_01)), layer(m, 1), "the collectors are exactly the six ring lines");
        for (String n : everyNode(m))
            assertTrue(n.startsWith("999792") || n.startsWith("999793"), "no background household is a ring: " + n);
    }

    /** MULE-01 forwards to MULE-02, which forwards on to MULE-03 five seconds later; background forwards are single hops. */
    @Test
    void forwardingChainFindsTheMuleChainOnly() throws Exception {
        List<List<List<String>>> m = run("telecom-forwarding-chain");
        assertEquals(1, m.size(), "one chain: " + m);
        assertEquals(List.of(List.of(MULES.get(0)), List.of(MULES.get(1)), List.of(MULES.get(2))), m.get(0));
    }

    /** The rogue agent AGT-666 and the cloned vouchers tie MULE-01..05 and SUBFRAUD-05; background shares one card per pair. */
    @Test
    void paymentRingFindsTheRogueAgentRing() throws Exception {
        List<List<List<String>>> m = run("telecom-payment-ring");
        assertFalse(m.isEmpty(), "the planted ring is found");
        Set<String> ring = new TreeSet<>(plus(MULES, SUBFRAUD.get(4)));
        assertEquals(ring, layer(m, 0), "every ring line closes a ring, and only those");
        assertEquals(ring, everyNode(m), "no background pair is a ring");
        for (List<List<String>> match : m)
            assertEquals(match.get(0), match.get(match.size() - 1), "the closing step lands back on the start: " + match);
    }
}
