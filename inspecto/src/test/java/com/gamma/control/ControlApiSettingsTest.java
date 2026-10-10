package com.gamma.control;

import com.gamma.spi.auth.Authenticator;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code SettingsRoutes} per-space branding, geo, Link Analysis caps and icon map over real HTTP: defaults before any save, a PUT round-trip that
 * persists {@code branding.toon} in the space's config tree, blank-folds-to-null, per-space isolation via the
 * {@code /spaces/{id}/settings/branding} seam, and the over-large-logo 422 guard. Drives a discover-mode
 * ControlApi so each space has a real (writable) config root.
 */
class ControlApiSettingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
            MetricRegistry.global().reset();
        }
    }

    private Ctx open(Path root) throws Exception {
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port(), root);
    }

    @Test
    void brandingRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // defaults before any save — all fields null (client falls back to the shipped defaults)
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/branding", null));
            assertTrue(def.get("logoDataUrl").isNull() && def.get("caption").isNull() && def.get("footerText").isNull());

            // PUT round-trip; blank footer folds to null
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/branding",
                    "{\"logoDataUrl\":\"data:image/png;base64,AAAA\",\"caption\":\"Chase the anomaly\",\"footerText\":\"  \"}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("Chase the anomaly", json(put).get("caption").asText());
            assertTrue(json(put).get("footerText").isNull(), "blank folds to null");

            // persisted on disk in the space's config tree (not a *_pipeline.toon suffix, so config discovery ignores it)
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("branding.toon")));
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/branding", null));
            assertEquals("data:image/png;base64,AAAA", got.get("logoDataUrl").asText());
            assertEquals("Chase the anomaly", got.get("caption").asText());

            // per-space isolation: 'beta' is untouched
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/branding", null)).get("caption").isNull());

            // over-large logo → 422
            String bigLogo = "\"logoDataUrl\":\"" + "x".repeat(600 * 1024) + "\"";
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/branding", "{" + bigLogo + "}").statusCode());
        }
    }

    @Test
    void geoSettingsRoundTripAndIsolatePerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — null (no self-hosted tile server)
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/geo", null)).get("tileServerUrl").isNull());

            // PUT round-trip, persisted as geo.toon in the space's config tree
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/geo",
                    "{\"tileServerUrl\":\"http://tiles.example/{z}/{x}/{y}.png\"}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("http://tiles.example/{z}/{x}/{y}.png", json(put).get("tileServerUrl").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("geo.toon")));
            assertEquals("http://tiles.example/{z}/{x}/{y}.png",
                    json(send(c.port, "GET", "/spaces/acme/settings/geo", null)).get("tileServerUrl").asText());

            // per-space isolation + blank-folds-to-null on save
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/geo", null)).get("tileServerUrl").isNull());
            assertTrue(json(send(c.port, "PUT", "/spaces/acme/settings/geo", "{\"tileServerUrl\":\"  \"}"))
                    .get("tileServerUrl").isNull(), "blank folds to null");
        }
    }

    @Test
    void iconMapRoundTripsAndIsolatesPerSpace(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // default before any save — empty map
            assertEquals(0, json(send(c.port, "GET", "/spaces/acme/config/icon-map", null)).size(), "no rules yet");

            // PUT round-trip: a type entry + a category entry, persisted as icon-map.toon
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/config/icon-map",
                    "{\"parser.dsv\":{\"glyph\":\"table\",\"color\":\"#00aaff\"},\"PARSE\":{\"glyph\":\"filter\",\"color\":\"#ff8800\"}}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("table", json(put).get("parser.dsv").get("glyph").asText());
            assertEquals("#ff8800", json(put).get("PARSE").get("color").asText());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("icon-map.toon")));

            JsonNode got = json(send(c.port, "GET", "/spaces/acme/config/icon-map", null));
            assertEquals("filter", got.get("PARSE").get("glyph").asText());
            assertEquals("#00aaff", got.get("parser.dsv").get("color").asText());

            // per-space isolation: 'beta' is untouched
            assertEquals(0, json(send(c.port, "GET", "/spaces/beta/config/icon-map", null)).size());

            // a malformed entry (missing color) → 422
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/config/icon-map",
                    "{\"parser.json\":{\"glyph\":\"braces\"}}").statusCode());
        }
    }

    @Test
    void linkAnalysisCapsRoundTripAndRefuseBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            // absent document reads as both-null (inherit the shipped defaults), not a failure
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("projectionNodeCap").isNull() && def.get("analysisNodeCap").isNull()
                            && def.get("suspicionNodeCap").isNull(),
                    "no document yet ⇒ inherit all three");

            // PUT round-trip of both values, persisted as link-analysis.toon in the space's config tree
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"projectionNodeCap\":1200,\"analysisNodeCap\":4000,\"suspicionNodeCap\":800}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals(1200, json(put).get("projectionNodeCap").asInt());
            assertEquals(4000, json(put).get("analysisNodeCap").asInt());
            // Suspicion score's ceiling is INDEPENDENT of the shared one: its cost is quadratic while the
            // other 26 algorithms are trivial at the shared cap, so it must be settable lower (D-S3).
            assertEquals(800, json(put).get("suspicionNodeCap").asInt());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve("link-analysis.toon")));
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertEquals(1200, got.get("projectionNodeCap").asInt());
            assertEquals(4000, got.get("analysisNodeCap").asInt());
            assertEquals(800, got.get("suspicionNodeCap").asInt(), "the third cap survives the round trip");

            // per-space isolation: 'beta' still inherits
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/link-analysis", null))
                    .get("projectionNodeCap").isNull());

            // null round-trips as null — an omitted/null cap means "inherit", never "unbounded"
            HttpResponse<String> cleared = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"projectionNodeCap\":null,\"analysisNodeCap\":900}");
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertTrue(json(cleared).get("projectionNodeCap").isNull(), "null ⇒ inherit the shipped default");
            assertEquals(900, json(cleared).get("analysisNodeCap").asInt());
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null))
                    .get("projectionNodeCap").isNull(), "cleared on disk too");

            // fail closed, never a silent clamp: 0, a non-integer, and above the sanity ceiling are all 422
            HttpResponse<String> zero = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"projectionNodeCap\":0}");
            assertEquals(422, zero.statusCode(), zero.body());
            assertTrue(zero.body().contains("projectionNodeCap") && zero.body().contains("1..100000"),
                    "the 422 names the field and the allowed range: " + zero.body());

            HttpResponse<String> nan = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"analysisNodeCap\":\"lots\"}");
            assertEquals(422, nan.statusCode(), nan.body());
            assertTrue(nan.body().contains("analysisNodeCap"), nan.body());

            HttpResponse<String> huge = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"analysisNodeCap\":100001}");
            assertEquals(422, huge.statusCode(), huge.body());
            assertTrue(huge.body().contains("analysisNodeCap") && huge.body().contains("1..100000"), huge.body());

            HttpResponse<String> badSuspicion = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"suspicionNodeCap\":0}");
            assertEquals(422, badSuspicion.statusCode(), badSuspicion.body());
            assertTrue(badSuspicion.body().contains("suspicionNodeCap")
                            && badSuspicion.body().contains("1..100000"),
                    "the third cap is refused by name and range like the other two: " + badSuspicion.body());

            // a refused write left the last good values standing
            assertEquals(900, json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null))
                    .get("analysisNodeCap").asInt());
        }
    }

    /** PIPELINE-CONFIG-HISTORY-1: history retention is a per-Space setting over a shipped default of 50. */
    @Test
    void pipelineHistoryRetentionRoundTripsPerSpaceAndRefusesBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"beta\"}").statusCode());

            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/pipeline-history", null));
            assertTrue(def.get("keep").isNull(), "no document ⇒ the shipped default: " + def);
            assertEquals(PipelineHistorySettings.DEFAULT_KEEP, def.get("effectiveKeep").asInt());
            assertEquals(PipelineHistorySettings.DEFAULT_KEEP, def.get("defaultKeep").asInt());
            assertEquals(PipelineHistorySettings.MAX_KEEP, def.get("maxKeep").asInt());

            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/pipeline-history", "{\"keep\":7}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals(7, json(put).get("effectiveKeep").asInt());
            assertTrue(Files.exists(root.resolve("acme").resolve("config").resolve(PipelineHistorySettings.FILE)));
            assertEquals(7, json(send(c.port, "GET", "/spaces/acme/settings/pipeline-history", null)).get("keep").asInt());
            assertTrue(json(send(c.port, "GET", "/spaces/beta/settings/pipeline-history", null)).get("keep").isNull(),
                    "per-Space: beta still has the default");

            for (String bad : List.of("0", "1001", "\"lots\"")) {
                HttpResponse<String> r = send(c.port, "PUT", "/spaces/acme/settings/pipeline-history", "{\"keep\":" + bad + "}");
                assertEquals(422, r.statusCode(), bad + " → " + r.body());
                assertTrue(r.body().contains("keep"), r.body());
            }
            assertEquals(7, json(send(c.port, "GET", "/spaces/acme/settings/pipeline-history", null)).get("keep").asInt(),
                    "a refused write left the last good value standing");

            HttpResponse<String> cleared = send(c.port, "PUT", "/spaces/acme/settings/pipeline-history", "{\"keep\":null}");
            assertEquals(200, cleared.statusCode(), cleared.body());
            assertTrue(json(cleared).get("keep").isNull());
            assertEquals(PipelineHistorySettings.DEFAULT_KEEP, json(cleared).get("effectiveKeep").asInt());
        }
    }

    /** A hand-edited out-of-range value reads as the default — `keep: 0` must never prune every version. */
    @Test
    void anOutOfRangeStoredRetentionReadsAsTheDefault(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(PipelineHistorySettings.FILE);
        Files.writeString(f, "keep: 0\n");
        assertEquals(PipelineHistorySettings.DEFAULT_KEEP, PipelineHistorySettings.read(f).effectiveKeep());
        Files.writeString(f, "keep: 12\n");
        assertEquals(12, PipelineHistorySettings.read(f).effectiveKeep());
    }

    /** LA-19 (D-U6, D-U7): the masking mode and the two four-eyes thresholds share the document and its rules. */
    @Test
    void linkAnalysisMaskingAndFourEyesRoundTripAndRefuseBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("maskingMode").isNull() && def.get("fourEyesBudgetAbove").isNull()
                    && def.get("fourEyesFanOutAbove").isNull(), "absent ⇒ inherit: typed masking, no four-eyes");

            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"maskingMode\":\"ALL\",\"fourEyesBudgetAbove\":5000,\"fourEyesFanOutAbove\":25}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals("all", json(put).get("maskingMode").asText(), "normalised to the declared spelling");
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("masking_mode: all") && toon.contains("four_eyes_budget_above: 5000")
                    && toon.contains("four_eyes_fan_out_above: 25"), toon);
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertEquals(5000, got.get("fourEyesBudgetAbove").asInt());
            assertEquals(25, got.get("fourEyesFanOutAbove").asInt());

            HttpResponse<String> bad = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"maskingMode\":\"some\"}");
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("[typed, all, none]"), "the 422 names the declared modes: " + bad.body());
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"fourEyesBudgetAbove\":0}").statusCode());
            assertEquals("all", json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null))
                    .get("maskingMode").asText(), "a refused write left the last good values standing");
        }
    }

    /** LA-17 merged traversal (operator 2026-09-30): the merged-expand distinct-value cap is a per-Space setting. */
    @Test
    void linkAnalysisMergedDistinctCapRoundTripsAndDefaults(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("mergedDistinctCap").isNull(), "absent => inherit");
            assertEquals(20_000, def.get("mergedDistinctCapInForce").asInt(), "the shipped default");
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"mergedDistinctCap\":500}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("merged_distinct_cap: 500"), toon);
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertEquals(500, got.get("mergedDistinctCapInForce").asInt());
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"mergedDistinctCap\":0}").statusCode(), "a cap is >= 1");
        }
    }

    /** Supernode suppression: the expand hub threshold is a per-Space setting, default 500. */
    @Test
    void linkAnalysisHubThresholdRoundTripsAndDefaults(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("hubThreshold").isNull(), "absent => inherit");
            assertEquals(500, def.get("hubThresholdInForce").asInt(), "the shipped default");
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"hubThreshold\":250}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("hub_threshold: 250"), toon);
            assertEquals(250, json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null)).get("hubThresholdInForce").asInt());
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"hubThreshold\":0}").statusCode(), "a threshold is >= 1");
        }
    }

    /** The seedBy twin of merged_distinct_cap: a per-Space setting, 1..100 000, default 20 000. */
    @Test
    void linkAnalysisSeedByDistinctCapRoundTripsAndDefaults(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("seedByDistinctCap").isNull(), "absent => inherit");
            assertEquals(20_000, def.get("seedByDistinctCapInForce").asInt(), "the shipped default");
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"seedByDistinctCap\":700}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("seed_by_distinct_cap: 700"), toon);
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertEquals(700, got.get("seedByDistinctCap").asInt());
            assertEquals(700, got.get("seedByDistinctCapInForce").asInt());
            assertEquals(20_000, got.get("mergedDistinctCapInForce").asInt(), "independent of the merged cap");
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"seedByDistinctCap\":0}").statusCode(), "a cap is >= 1");
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"seedByDistinctCap\":100001}").statusCode(), "a cap is <= 100 000");
        }
    }

    /** DR-U3: the read-only echoes the Settings pane shows (masking, index, store backend) and a full-document round trip of the keys it edits. */
    @Test
    void linkAnalysisInForceEchoesAndFullDocumentRoundTrip(@TempDir Path root) throws Exception {
        String prior = System.getProperty("investigations.backend");
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String url = "/spaces/acme/settings/link-analysis";
            System.clearProperty("investigations.backend");
            JsonNode def = json(send(c.port, "GET", url, null));
            assertEquals("typed", def.get("maskingModeInForce").asText());
            assertEquals("fs", def.get("investigationStoreInForce").asText(), "default backend is the filesystem");
            assertEquals(1, def.get("indexInForce").get("threads").asInt());
            assertEquals(4, def.get("indexInForce").get("queue").asInt());
            System.setProperty("investigations.backend", "db");
            assertEquals("db", json(send(c.port, "GET", url, null)).get("investigationStoreInForce").asText());
            System.setProperty("investigations.backend", "bogus");
            assertEquals("fs", json(send(c.port, "GET", url, null)).get("investigationStoreInForce").asText(), "anything but db reads as fs");

            String doc = "{\"maskingMode\":\"none\",\"graphRun\":{\"maxNodes\":1000,\"maxEdges\":2000,\"timeoutMs\":5000},"
                    + "\"index\":{\"enabled\":true,\"threads\":2,\"queue\":8,\"maxDiskBytes\":1048576}}";
            assertEquals(200, send(c.port, "PUT", url, doc).statusCode());
            JsonNode got = json(send(c.port, "GET", url, null));
            assertEquals("none", got.get("maskingModeInForce").asText());
            assertTrue(got.get("indexInForce").get("enabled").asBoolean());
            assertEquals(8, got.get("indexInForce").get("queue").asInt());
            // The SPA's save: the whole document as read (echo keys included) with one unrelated key edited keeps every other key.
            com.fasterxml.jackson.databind.node.ObjectNode back = (com.fasterxml.jackson.databind.node.ObjectNode) got.deepCopy();
            back.put("fourEyesBudgetAbove", 77);
            assertEquals(200, send(c.port, "PUT", url, back.toString()).statusCode());
            JsonNode after = json(send(c.port, "GET", url, null));
            assertEquals("none", after.get("maskingMode").asText());
            assertEquals(1000, after.get("graphRun").get("maxNodes").asInt());
            assertEquals(5000, after.get("graphRun").get("timeoutMs").asInt());
            assertEquals(2, after.get("index").get("threads").asInt());
            assertEquals(1048576, after.get("index").get("maxDiskBytes").asLong());
            assertEquals(77, after.get("fourEyesBudgetAbove").asInt());
        } finally {
            if (prior == null) System.clearProperty("investigations.backend"); else System.setProperty("investigations.backend", prior);
        }
    }

    /** The per-set size limit: a per-Space setting, 1 KiB..1 GiB, default 64 MiB (operator 2026-10-10). */
    @Test
    void linkAnalysisMaxSetBytesRoundTripsAndDefaults(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            JsonNode def = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null));
            assertTrue(def.get("maxSetBytes").isNull(), "absent => inherit");
            assertEquals(67_108_864, def.get("maxSetBytesInForce").asInt(), "the shipped default is 64 MiB");
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"maxSetBytes\":1048576}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("max_set_bytes: 1048576"), toon);
            assertEquals(1_048_576, json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null)).get("maxSetBytesInForce").asInt());
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"maxSetBytes\":1024}").statusCode(), "the floor passes");
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"maxSetBytes\":1073741824}").statusCode(), "the ceiling passes");
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"maxSetBytes\":1023}").statusCode(), "below 1 KiB");
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"maxSetBytes\":1073741825}").statusCode(), "above 1 GiB");
        }
    }

    /** The per-Investigation total set budget: a per-Space setting, 1 MiB..1 TiB, default 4 GiB (operator 2026-10-10); beyond int range. */
    @Test
    void linkAnalysisMaxInvestigationBytesRoundTripsDefaultsAndIsRangeChecked(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String url = "/spaces/acme/settings/link-analysis";
            JsonNode def = json(send(c.port, "GET", url, null));
            assertTrue(def.get("maxInvestigationBytes").isNull(), "absent => inherit");
            assertEquals(4_294_967_296L, def.get("maxInvestigationBytesInForce").asLong(), "the shipped default is 4 GiB");
            HttpResponse<String> put = send(c.port, "PUT", url, "{\"maxInvestigationBytes\":8589934592}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("max_investigation_bytes: 8589934592"), toon);
            JsonNode back = json(send(c.port, "GET", url, null));
            assertEquals(8_589_934_592L, back.get("maxInvestigationBytes").asLong());
            assertEquals(8_589_934_592L, back.get("maxInvestigationBytesInForce").asLong());
            assertEquals(200, send(c.port, "PUT", url, "{\"maxInvestigationBytes\":1048576}").statusCode(), "the floor passes");
            assertEquals(200, send(c.port, "PUT", url, "{\"maxInvestigationBytes\":1099511627776}").statusCode(), "the ceiling passes");
            assertEquals(422, send(c.port, "PUT", url, "{\"maxInvestigationBytes\":1048575}").statusCode(), "below 1 MiB");
            assertEquals(422, send(c.port, "PUT", url, "{\"maxInvestigationBytes\":1099511627777}").statusCode(), "above 1 TiB");
            assertEquals(422, send(c.port, "PUT", url, "{\"maxInvestigationBytes\":\"lots\"}").statusCode(), "not an integer");
            assertEquals(200, send(c.port, "PUT", url, "{}").statusCode());
            assertTrue(json(send(c.port, "GET", url, null)).get("maxInvestigationBytes").isNull(), "a save that omits it returns to inherit");
        }
    }

    /** D-4 step 6: the graph-run knobs are a nested block; absent = inherit, a stated block round-trips and a bad one is 422. */
    @Test
    void linkAnalysisGraphRunRoundTripsAndRefusesBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null)).get("graphRun").isNull(), "absent => inherit");
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"graphRun\":{\"maxNodes\":900,\"timeoutMs\":4000,\"threads\":3,\"maxResultItems\":250}}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("graph_run") && toon.contains("max_nodes: 900") && toon.contains("timeout_ms: 4000"), toon);
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null)).get("graphRun");
            assertEquals(900, got.get("maxNodes").asInt());
            assertEquals(3, got.get("threads").asInt());
            assertEquals(250, got.get("maxResultItems").asInt());
            assertTrue(toon.contains("max_result_items: 250"), toon);
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"graphRun\":{\"maxResultItems\":0}}").statusCode());
            assertTrue(got.get("maxEdges").isNull() && got.get("queue").isNull(), "unstated knobs stay unstated");
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"graphRun\":{\"threads\":0}}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"graphRun\":{\"bogus\":1}}").statusCode());
            assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"graphRun\":7}").statusCode());
        }
    }

    /** D-3 step 4: the index knobs are a nested block; absent = inherit, a stated block round-trips and a bad one is 422. */
    @Test
    void linkAnalysisIndexRoundTripsAndRefusesBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            assertTrue(json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null)).get("index").isNull(), "absent => inherit");
            HttpResponse<String> put = send(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    "{\"index\":{\"enabled\":false,\"maxDiskBytes\":5000000000,\"keepVersions\":3,\"threads\":2}}");
            assertEquals(200, put.statusCode(), put.body());
            String toon = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(toon.contains("index") && toon.contains("max_disk_bytes: 5000000000") && toon.contains("keep_versions: 3")
                    && toon.contains("enabled: false"), toon);
            JsonNode got = json(send(c.port, "GET", "/spaces/acme/settings/link-analysis", null)).get("index");
            assertFalse(got.get("enabled").asBoolean(), "an explicit false survives the round trip");
            assertEquals(5_000_000_000L, got.get("maxDiskBytes").asLong());
            assertEquals(3, got.get("keepVersions").asInt());
            assertEquals(2, got.get("threads").asInt());
            assertTrue(got.get("queue").isNull(), "unstated knobs stay unstated");
            assertEquals(200, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", "{\"index\":{\"maxDiskBytes\":0}}").statusCode(), "0 = no limit");
            for (String bad : new String[] {"{\"index\":{\"keepVersions\":0}}", "{\"index\":{\"threads\":65}}", "{\"index\":{\"queue\":1001}}",
                    "{\"index\":{\"maxDiskBytes\":-1}}", "{\"index\":{\"enabled\":\"yes\"}}", "{\"index\":{\"bogus\":1}}", "{\"index\":7}"})
                assertEquals(422, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", bad).statusCode(), bad);
        }
    }

    /** LA-DRAFT-PROMOTE-COST-1: the Draft lifecycle knobs are a nested block; absent = inherit, refusals are 422 and leave the file as it was. */
    @Test
    void linkAnalysisDraftsRoundTripAndRefuseBadValues(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String path = "/spaces/acme/settings/link-analysis";
            JsonNode def = json(send(c.port, "GET", path, null));
            assertTrue(def.get("drafts").isNull(), "absent => inherit");
            assertEquals(50, def.get("draftsInForce").get("maxOpen").asInt());
            assertEquals(60, def.get("draftsInForce").get("hibernateAfterMinutes").asInt());
            assertEquals(30, def.get("draftsInForce").get("expireAfterDays").asInt());
            HttpResponse<String> put = send(c.port, "PUT", path, "{\"drafts\":{\"maxOpen\":8,\"hibernateAfterMinutes\":30,\"expireAfterDays\":14}}");
            assertEquals(200, put.statusCode(), put.body());
            String file = Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"));
            assertTrue(file.contains("max_open: 8") && file.contains("hibernate_after_minutes: 30") && file.contains("expire_after_days: 14"), file);
            JsonNode got = json(send(c.port, "GET", path, null));
            assertEquals(8, got.get("drafts").get("maxOpen").asInt());
            assertEquals(14, got.get("draftsInForce").get("expireAfterDays").asInt());
            // each refusal states ONE bad value next to values that would otherwise be accepted
            for (String bad : new String[] {"{\"drafts\":{\"maxOpen\":0}}", "{\"drafts\":{\"maxOpen\":1001}}",
                    "{\"drafts\":{\"hibernateAfterMinutes\":10081}}", "{\"drafts\":{\"expireAfterDays\":3651}}",
                    "{\"drafts\":{\"maxOpen\":5,\"expireAfterDays\":0}}", "{\"drafts\":{\"maxOpen\":\"many\"}}",
                    "{\"drafts\":{\"maxOpen\":5,\"bogus\":1}}", "{\"drafts\":7}",
                    "{\"drafts\":{\"hibernateAfterMinutes\":10080,\"expireAfterDays\":7}}"})
                assertEquals(422, send(c.port, "PUT", path, bad).statusCode(), bad);
            assertEquals(file, Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon")), "a refusal changes nothing");
            // positive controls: the same shapes one step inside the limit are accepted
            assertEquals(200, send(c.port, "PUT", path, "{\"drafts\":{\"hibernateAfterMinutes\":10080,\"expireAfterDays\":8}}").statusCode());
            assertEquals(200, send(c.port, "PUT", path, "{\"drafts\":{\"maxOpen\":1000,\"expireAfterDays\":3650}}").statusCode());
            assertTrue(json(send(c.port, "PUT", path, "{}")).get("drafts").isNull(), "an unstated block reads back as inherit");
        }
    }

    /** LA-17 step 2: per-Space Entity Types — inherit the seeded nine, a stated list replaces them, 422 on bad. */
    @Test
    void linkAnalysisEntityTypesRoundTripAndRefuseBadLists(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertEquals(200, send(c.port, "POST", "/spaces", "{\"id\":\"acme\"}").statusCode());
            String path = "/spaces/acme/settings/link-analysis";
            JsonNode def = json(send(c.port, "GET", path, null));
            assertTrue(def.get("entityTypes").isNull(), "absent ⇒ inherit: " + def);
            assertEquals(9, def.get("entityTypesInForce").size());
            JsonNode agent = def.get("entityTypesInForce").get(6);
            assertEquals("agent", agent.get("id").asText());
            assertEquals("Agent / till", agent.get("label").asText());
            assertEquals("upper-trim", agent.get("normaliser").asText());
            assertFalse(agent.get("masked").asBoolean());
            assertEquals("[\"AGENT\",\"TILL\"]", agent.get("classifications").toString());

            String list = "[{\"id\":\"msisdn\",\"label\":\"MSISDN\",\"normaliser\":\"e164\",\"masked\":true,"
                    + "\"classifications\":[\"MSISDN\"]},{\"id\":\"till\",\"label\":\"Till\",\"normaliser\":"
                    + "\"upper-trim\",\"masked\":false,\"classifications\":[\"TILL\",\"AGENT\"]}]";
            HttpResponse<String> put = send(c.port, "PUT", path, "{\"maskingMode\":\"all\",\"entityTypes\":" + list + "}");
            assertEquals(200, put.statusCode(), put.body());
            assertEquals(JSON.readTree(list), json(put).get("entityTypes"));
            assertEquals(JSON.readTree(list), json(put).get("entityTypesInForce"), "a stated list replaces the defaults");
            assertTrue(Files.readString(root.resolve("acme").resolve("config").resolve("link-analysis.toon"))
                    .contains("entity_types"));
            assertEquals(JSON.readTree(list), json(send(c.port, "GET", path, null)).get("entityTypes"),
                    "survives the round trip through link-analysis.toon");

            String one = "{\"id\":\"%s\",\"label\":\"%s\",\"normaliser\":\"%s\",\"masked\":%s,\"classifications\":%s}";
            Map<String, String> bad = new java.util.LinkedHashMap<>();
            bad.put("[]", "may not be empty");
            bad.put("[" + one.formatted("Bad-Id", "X", "default", "true", "[]") + "]", "must match");
            bad.put("[" + one.formatted("a", "X", "default", "true", "[]") + "," + one.formatted("a", "Y", "digits", "false", "[]")
                    + "]", "duplicate entity type id");
            bad.put("[" + one.formatted("entity", "X", "default", "true", "[]") + "]", "'entity' is reserved");
            bad.put("[" + one.formatted("a", " ", "default", "true", "[]") + "]", "label is blank");
            bad.put("[" + one.formatted("a", "X", "lower", "true", "[]") + "]", "normaliser must be one of");
            bad.put("[" + one.formatted("a", "X", "default", "true", "[\"\"]") + "]", "classification is blank");
            bad.put("[" + one.formatted("a", "X", "default", "true", "[\"IMSI\"]") + ","
                    + one.formatted("b", "Y", "digits", "false", "[\" imsi \"]") + "]", "claimed by both");
            bad.put("[" + one.formatted("a", "X", "default", "\"yes\"", "[]") + "]", "masked must be true or false");
            StringBuilder many = new StringBuilder("[");
            for (int i = 0; i < 65; i++) many.append(i == 0 ? "" : ",").append(one.formatted("t" + i, "T", "default", "false", "[]"));
            bad.put(many.append("]").toString(), "at most 64");
            for (Map.Entry<String, String> b : bad.entrySet()) {
                HttpResponse<String> r = send(c.port, "PUT", path, "{\"entityTypes\":" + b.getKey() + "}");
                assertEquals(422, r.statusCode(), b.getValue() + " → " + r.body());
                assertTrue(r.body().contains(b.getValue()), "the 422 names the problem: " + r.body());
            }
            assertEquals(JSON.readTree(list), json(send(c.port, "GET", path, null)).get("entityTypes"),
                    "a refused write left the last good list standing");

            HttpResponse<String> reset = send(c.port, "PUT", path, "{\"maskingMode\":\"all\"}");
            assertEquals(200, reset.statusCode(), reset.body());
            JsonNode got = json(send(c.port, "GET", path, null));
            assertTrue(got.get("entityTypes").isNull(), "a PUT without entityTypes resets to inherit: " + got);
            assertEquals(9, got.get("entityTypesInForce").size());
        }
    }

    /**
     * The capability gate on the settings writes, exercised with an ARMED Authenticator.
     *
     * 🔴 <b>Why this exists.</b> `withCapability` is a NO-OP unless a {@link Subject} is attached, and no test
     * attaches one unless it installs a fake authenticator. Every other test in this class runs
     * Personal-shaped, so they pass identically whether or not these routes are gated at all — adding or
     * removing the gate would change no assertion here. `tools/check-authgate-coverage.mjs` flagged
     * `PUT /settings/link-analysis` for exactly that on 2026-09-22, taking the ratchet from 79 to 80.
     *
     * ⚠ Three statuses on purpose: 401 is authentication, <b>403 is the GATE</b> — a present Subject LACKING
     * the capability — and only the 403 distinguishes a gated route from one that merely needs a login.
     */
    @Test
    void settingsWritesRequireCanAuthorWorkbench(@TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> "Bearer valid".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("jdoe", Set.of("canAuthorWorkbench")))
                : "Bearer plain".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("nobody", Set.of()))
                : "Bearer admin".equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("root", Set.of("canAdminister")))
                : Optional.empty());
        try (Ctx c = open(root)) {
            // ⚠ The setup call needs a credential too, and canAdminister: POST /spaces is administration even
            // while no Space is hosted (TEMPLATE-RECOVERY-IMPORT-GATE-1, 2026-10-03).
            assertEquals(200, sendAs(c.port, "POST", "/spaces", "{\"id\":\"acme\"}", "Bearer admin").statusCode());
            String body = "{\"projectionNodeCap\":600}";

            assertEquals(401, send(c.port, "PUT", "/spaces/acme/settings/link-analysis", body).statusCode(),
                    "no credential is a clean 401, never a 500");

            HttpResponse<String> denied = sendAs(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    body, "Bearer plain");
            assertEquals(403, denied.statusCode(), "a Subject WITHOUT the capability is refused: " + denied.body());
            assertTrue(denied.body().contains("canAuthorWorkbench"), "the refusal names the capability");

            assertEquals(200, sendAs(c.port, "GET", "/spaces/acme/settings/link-analysis", null, "Bearer plain")
                    .statusCode(), "reads stay open by policy: the same capability-less Subject may READ");

            HttpResponse<String> allowed = sendAs(c.port, "PUT", "/spaces/acme/settings/link-analysis",
                    body, "Bearer valid");
            assertEquals(200, allowed.statusCode(), "a Subject WITH the capability is allowed: " + allowed.body());

            // PIPELINE-CONFIG-HISTORY-1: the retention setting is gated the same way.
            String keep = "{\"keep\":5}";
            assertEquals(401, send(c.port, "PUT", "/spaces/acme/settings/pipeline-history", keep).statusCode());
            HttpResponse<String> keepDenied = sendAs(c.port, "PUT", "/spaces/acme/settings/pipeline-history", keep, "Bearer plain");
            assertEquals(403, keepDenied.statusCode(), keepDenied.body());
            assertTrue(keepDenied.body().contains("canAuthorWorkbench"), keepDenied.body());
            assertEquals(200, sendAs(c.port, "GET", "/spaces/acme/settings/pipeline-history", null, "Bearer plain").statusCode());
            HttpResponse<String> keepAllowed = sendAs(c.port, "PUT", "/spaces/acme/settings/pipeline-history", keep, "Bearer valid");
            assertEquals(200, keepAllowed.statusCode(), keepAllowed.body());
        } finally {
            Authenticators.forTest(null);
        }
    }

    private HttpResponse<String> sendAs(int port, String method, String path, String body, String auth)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception { return V1Body.of(r.body()); }
}
