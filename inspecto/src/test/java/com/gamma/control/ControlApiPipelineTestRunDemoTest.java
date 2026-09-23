package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.CollectorProcessor;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
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
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TESTRUN-SEED-IS-MAPPED-OUTPUT-1} — run-to-here over the SHIPPED {@code spaces/demo} Pipelines, over
 * real HTTP, compared against a REAL ingest of the same committed sample.
 *
 * <p>🔴 The test run used to seed the parse node with the rows the ingest WROTE — already mapped — so the walk
 * re-applied every mapping to canonical columns: {@code in_recharges} ({@code AMOUNT_MINOR}) and
 * {@code msc_cdr} ({@code EVENT_TIME}) refused 422 with a binder error, and {@code premed_events} answered 200
 * with every {@code EVENT_TS} NULL while a real ingest writes all 12. Operator decision 2026-09-23: seed with
 * the RAW parsed rows, so the preview runs the same mapping a real ingest does.
 *
 * <p>⚠ Each assertion is against the real ingest's own output, not a constant the preview could agree with by
 * coincidence: per-node row counts AND values. The committed config is staged into a temp root exactly as
 * {@code DemoCorpusIngestTest} does (every {@code spaces/demo/data/…} path re-pointed, siblings copied).
 */
class ControlApiPipelineTestRunDemoTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private final HttpClient client = HttpClient.newHttpClient();

    /** premed_events: all 12 EVENT_TS values match the real ingest, and route splits 4 · 3 · 5 like it does. */
    @Test
    void premedEventsPreviewsTheSameEventTimestampsARealIngestWrites(@TempDir Path dir) throws Exception {
        String pipeline = "config/premed/premed_events_pipeline.toon";
        String sample = "premed_events/EVENTS_20260801.csv";

        PipelineConfig real = stage(dir.resolve("real"), pipeline);
        seed(real, sample);
        CollectorProcessor.run(real);
        Map<String, String> ingested = pairs(dir.resolve("real/data/premed_events"), "EVENT_ID", "EVENT_TS::VARCHAR");
        assertEquals(12, ingested.size(), "the real ingest writes all 12 events");
        assertFalse(ingested.containsValue(null), "the real ingest parses every EVENT_TS: " + ingested);

        JsonNode b = testRun(dir.resolve("preview"), pipeline, sample, "EVENTS_20260801.csv");
        JsonNode map = relation(b, "map", "data");
        assertEquals(12, map.get("rowCount").asInt(), relations(b));
        Map<String, String> previewed = new TreeMap<>();
        for (JsonNode row : map.get("rows"))
            previewed.put(row.get("EVENT_ID").asText(), row.get("EVENT_TS").isNull() ? null : timestamp(row.get("EVENT_TS")));
        assertEquals(ingested, previewed, "the preview's mapped EVENT_TS must be the value a real ingest writes");

        // …and the walk carries on past map to route, with the real ingest's per-branch split.
        assertEquals(count(dir.resolve("real/data/premed_events/voice")), relation(b, "route", "route:voice").get("rowCount").asLong(), relations(b));
        assertEquals(count(dir.resolve("real/data/premed_events/sms")), relation(b, "route", "route:sms").get("rowCount").asLong(), relations(b));
        assertEquals(count(dir.resolve("real/data/premed_events/other")), relation(b, "route", "route:other").get("rowCount").asLong(), relations(b));
    }

    /** in_recharges (fixed-width): past map (AMOUNT_MINOR) to route, 10 accepted · 4 rejected, amounts matching. */
    @Test
    void inRechargesRunsPastTheMappingToItsRouteWithTheRealIngestsSplitAndAmounts(@TempDir Path dir) throws Exception {
        String pipeline = "config/recharge/in_recharges_pipeline.toon";
        String sample = "in_recharges/RCH_20260801.dat";

        PipelineConfig real = stage(dir.resolve("real"), pipeline);
        seed(real, sample);
        CollectorProcessor.run(real);
        Path accepted = dir.resolve("real/data/in_recharges/accepted");
        Path rejected = dir.resolve("real/data/in_recharges/rejected");
        Map<String, String> ingested = pairs(dir.resolve("real/data/in_recharges"), "SEQ_NO::VARCHAR", "CAST(AMOUNT AS DECIMAL(18,2))::VARCHAR");
        assertEquals(14, ingested.size(), "the real ingest writes 14 recharges");

        JsonNode b = testRun(dir.resolve("preview"), pipeline, sample, "RCH_20260801.dat");
        JsonNode map = relation(b, "map", "data");
        assertEquals(14, map.get("rowCount").asInt(), relations(b));
        Map<String, String> previewed = new TreeMap<>();
        for (JsonNode row : map.get("rows"))
            previewed.put(row.get("SEQ_NO").asText(), decimal(row.get("AMOUNT")));
        assertEquals(ingested, previewed, "AMOUNT = AMOUNT_MINOR / 100, exactly as the real ingest computes it");

        assertEquals(count(accepted), relation(b, "route", "route:accepted").get("rowCount").asLong(), relations(b));
        assertEquals(count(rejected), relation(b, "route", "route:rejected").get("rowCount").asLong(), relations(b));
    }

    /** msc_cdr (ASN.1): each segment's map (EVENT_TIME → EVENT_TS) runs, 5 · 4 · 3, timestamps matching. */
    @Test
    void mscCdrRunsEachSegmentsMappingWithTheRealIngestsCountsAndTimestamps(@TempDir Path dir) throws Exception {
        String pipeline = "config/msc/msc_cdr_pipeline.toon";
        String sample = "msc_cdr/MSC01_20260801_0800.ber";

        PipelineConfig real = stage(dir.resolve("real"), pipeline);
        seed(real, sample);
        CollectorProcessor.run(real);
        Path db = Path.of(real.dirs().database());

        JsonNode b = testRun(dir.resolve("preview"), pipeline, sample, "MSC01_20260801_0800.ber");
        for (String seg : List.of("moCallRecord", "mtCallRecord", "moSMSRecord")) {
            Map<String, String> ingested = pairs(db.resolve(seg), "IMSI", "EVENT_TS::VARCHAR");
            JsonNode map = relation(b, "map_" + seg, "data");
            assertEquals(count(db.resolve(seg)), map.get("rowCount").asLong(), seg + ": " + relations(b));
            Map<String, String> previewed = new TreeMap<>();
            for (JsonNode row : map.get("rows"))
                previewed.put(row.get("IMSI").asText(), row.get("EVENT_TS").isNull() ? null : timestamp(row.get("EVENT_TS")));
            assertEquals(ingested, previewed, seg + ": EVENT_TS = strptime(EVENT_TIME), as the real ingest writes it");
        }
    }

    // ── harness ─────────────────────────────────────────────────────────────────────────────────

    /** Stage {@code pipeline} under {@code dir}, seed {@code sample} into its inbox, and run-to-here over it. */
    private JsonNode testRun(Path dir, String pipeline, String sample, String file) throws Exception {
        PipelineConfig cfg = stage(dir, pipeline);
        seed(cfg, sample);
        CollectorService svc = new CollectorService(List.of(dir.resolve(pipeline)), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + api.port() + "/api/v1/pipelines/authored/"
                                    + cfg.identity().pipelineName() + "/run"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{\"files\":[\"" + file + "\"]}")).build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
            JsonNode n = JSON.readTree(r.body());
            return n.has("data") ? n.get("data") : n;
        } finally {
            api.close();
            svc.close();
        }
    }

    private static JsonNode relation(JsonNode b, String node, String rel) {
        for (JsonNode r : b.get("relations"))
            if (node.equals(r.get("node").asText()) && rel.equals(r.get("rel").asText())) return r;
        return fail("no relation " + node + "/" + rel + " — the walk did not reach it: " + relations(b)
                + " warnings=" + b.get("warnings"));
    }

    private static String relations(JsonNode b) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : b.get("relations"))
            out.add(r.get("node").asText() + "/" + r.get("rel").asText() + "=" + r.get("rowCount").asInt());
        return out.toString();
    }

    /**
     * A previewed TIMESTAMP in DuckDB's {@code ::VARCHAR} spelling. ⚠ The preview serialises a
     * {@code java.sql.Timestamp} as epoch millis, and the driver built that Timestamp in the HOST zone — so it
     * is read back through {@code toLocalDateTime()}, never as UTC epoch (which is off by the host offset).
     */
    private static String timestamp(JsonNode v) throws Exception {
        String ts = v.isNumber()
                ? new java.sql.Timestamp(v.asLong()).toLocalDateTime().toString().replace('T', ' ')
                : v.asText();
        return scalarSql("CAST(TIMESTAMP '" + ts + "' AS VARCHAR)");
    }

    /** Numeric equality at cents: the preview and the written file may spell the same amount differently. */
    private static String decimal(JsonNode v) throws Exception {
        return scalarSql("CAST(CAST('" + v.asText() + "' AS DECIMAL(18,2)) AS VARCHAR)");
    }

    private static String scalarSql(String expr) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + expr)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** {@code key → value} over every Parquet file under {@code root}, as the real ingest wrote them. */
    private static Map<String, String> pairs(Path root, String key, String value) throws Exception {
        Map<String, String> out = new TreeMap<>();
        String glob = root.toString().replace('\\', '/') + "/**/*.parquet";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + key + ", " + value + " FROM read_parquet('" + glob + "')")) {
            while (rs.next()) out.put(rs.getString(1), rs.getString(2));
        }
        return out;
    }

    private static long count(Path root) throws Exception {
        String glob = root.toString().replace('\\', '/') + "/**/*.parquet";
        return Long.parseLong(scalarSql("COUNT(*)::VARCHAR FROM read_parquet('" + glob + "')"));
    }

    private static PipelineConfig stage(Path dir, String pipeline) throws Exception {
        String data = dir.resolve("data").toString().replace('\\', '/') + "/";
        Path source = REPO.resolve("spaces/demo").resolve(pipeline);
        Path toon = dir.resolve(pipeline);
        Files.createDirectories(toon.getParent());
        try (var siblings = Files.list(source.getParent())) {
            for (Path f : siblings.filter(Files::isRegularFile).toList())
                if (!f.equals(source)) Files.copy(f, toon.getParent().resolve(f.getFileName()));
        }
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(source))
            out.add(rewrite(line, "spaces/demo/data/", data));
        Files.write(toon, out);
        return PipelineConfig.load(toon.toString());
    }

    /** Replace {@code from} with {@code to}, quoting the path field when the line is a TOON array row. */
    private static String rewrite(String line, String from, String to) {
        if (!line.contains(from)) return line;
        String r = line.replace(from, to);
        if (line.stripLeading().matches("^[A-Za-z_][A-Za-z0-9_]*\\s*:.*")) return r;
        int at = r.indexOf(to);
        int end = r.indexOf(',', at);
        if (end < 0) end = r.length();
        return r.substring(0, at) + '"' + r.substring(at, end) + '"' + r.substring(end);
    }

    private static void seed(PipelineConfig cfg, String sample) throws Exception {
        Path inbox = Files.createDirectories(Path.of(cfg.dirs().poll()));
        Path src = REPO.resolve("spaces/demo/data/samples").resolve(sample);
        Files.copy(src, inbox.resolve(src.getFileName()));
    }
}
