package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code compare} OP (LA-INVESTIGATION-OPS-DEFERRED-1, sealed form of comparison mode), over real HTTP. Same fixture as
 * {@link ControlApiInvestigationComparisonTest}: window A = 09-01..09-03, B = 09-03..09-06; a-b only in A, a-c in both, c-d only
 * in B; e-f is in the Dataset but never admitted.
 */
class ControlApiInvestigationCompareOpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('a','b','voice','2026-09-01 10:30:00')", "('a','c','voice','2026-09-02 12:00:00')",
            "('a','c','voice','2026-09-05 09:00:00')", "('c','d','sms','2026-09-05 08:00:00')",
            "('e','f','sms','2026-09-05 10:00:00')");
    private static final String COMPARE = "{\"op\":\"compare\","
            + "\"windowA\":{\"from\":\"2026-09-01T00:00:00-03:00\",\"to\":\"2026-09-03T00:00:00-03:00\",\"timezone\":\"America/Sao_Paulo\"},"
            + "\"windowB\":{\"from\":\"2026-09-03T00:00:00-03:00\",\"to\":\"2026-09-06T00:00:00-03:00\",\"timezone\":\"America/Sao_Paulo\"}}";
    private static final String OPS = "/inv/investigations/case-a/ops";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel, CAST(ts AS TIMESTAMP) AS ts FROM (VALUES " + ROWS
                            + ") AS t(caller,callee,channel,ts)", "2026-09-23T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode post(Ctx c, String path, String body) throws Exception {
        return ok(send(c, "POST", path, body, null));
    }

    private JsonNode get(Ctx c, String path) throws Exception {
        return ok(send(c, "GET", path, null, null));
    }

    private void build(Ctx c) throws Exception {
        post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\","
                + "\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"timeCol\":\"ts\",\"timeColZone\":\"America/Sao_Paulo\"}");
        post(c, OPS, "{\"op\":\"seed\",\"ids\":[\"a\"]}");
        post(c, OPS, "{\"op\":\"expand\"}");
        post(c, OPS, "{\"op\":\"expand\"}");
    }

    @Test
    void sealsTheDiffIntoTheLogWithoutMovingTheWorkingSet(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode before = get(c, "/inv/investigations/case-a/log");
            String hashBefore = before.at("/entries/2/workingSetHash").asText();

            JsonNode r = post(c, OPS, COMPARE);
            assertEquals("compare", r.get("op").asText());
            assertEquals(4, r.get("step").asInt());
            assertTrue(r.at("/comparison/sealed").asBoolean(), r.toString());
            assertEquals(1, r.at("/comparison/links/onlyA/count").asInt());
            assertEquals("a", r.at("/comparison/links/both/items/0/source").asText());
            assertEquals(1, r.at("/comparison/links/onlyB/count").asInt());
            assertEquals(0, r.at("/delta/entitiesAdded").size() + r.at("/delta/entitiesRemoved").size(), "the Working Set does not move");
            assertFalse(r.toString().contains("\"e\"") || r.toString().contains("\"f\""), "e-f was never admitted: " + r);

            JsonNode log = get(c, "/inv/investigations/case-a/log");
            JsonNode entry = log.at("/entries/3");
            assertEquals(hashBefore, entry.get("workingSetHash").asText(), "a marker leaves the Working Set hash untouched");
            assertTrue(entry.get("text").asText().contains("Compared") && entry.get("text").asText().contains("links only in A 1"), entry.get("text").asText());
            assertTrue(entry.at("/comparison/links/onlyA").isInt() || entry.at("/comparison/links/onlyA").isNumber(),
                    "the log view carries counts, not the sealed lists: " + entry);

            // The sealed diff is in the stored log line, with its fingerprint; replay equivalent without re-reading the Dataset.
            String line = Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).get(3);
            assertTrue(line.contains("\"comparison\"") && line.contains("\"fingerprint\""), line);
            assertTrue(post(c, "/inv/investigations/case-a/replay", "{}").get("equivalent").asBoolean());

            // Each side is a full window object: a TUE-only mask on A (2026-09-01 is a Tuesday) drops the Wednesday a-c event from A.
            JsonNode masked = post(c, OPS, COMPARE.replace("\"windowA\":{", "\"windowA\":{\"days\":[\"TUE\"],"));
            assertEquals(1, masked.at("/comparison/links/onlyA/count").asInt(), masked.toString());
            assertEquals(2, masked.at("/comparison/links/onlyB/count").asInt(), masked.toString());
            post(c, "/inv/investigations/case-a/undo", "");

            // A fork re-seals over its own state.
            JsonNode fork = post(c, "/inv/investigations/case-a/reorder", "{\"id\":\"case-b\",\"order\":[1,2,3,4]}");
            assertEquals(4, fork.get("steps").asInt());
            assertTrue(Files.readAllLines(root.resolve("audit/snapshots/investigations/case-b/log.jsonl")).get(3).contains("\"comparison\""));
        }
    }

    private static final String ACTIVITY = COMPARE.replace("\"op\":\"compare\",", "\"op\":\"compare\",\"mode\":\"activity\",");

    @Test
    void activityModeSealsPerLinkAndPerEntityEventCountDeltas(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode r = post(c, OPS, ACTIVITY);
            assertEquals("activity", r.at("/comparison/mode").asText());
            // a-b 1 -> 0, a-c 1 -> 1 (unchanged), c-d 0 -> 1; ties on |delta| order by id.
            assertEquals(2, r.at("/comparison/activity/links/changed/count").asInt(), r.toString());
            assertEquals(1, r.at("/comparison/activity/links/unchanged").asInt());
            assertEquals("a", r.at("/comparison/activity/links/changed/items/0/source").asText());
            assertEquals(-1, r.at("/comparison/activity/links/changed/items/0/delta").asInt());
            assertEquals(1, r.at("/comparison/activity/links/changed/items/1/delta").asInt());
            // a 2 -> 1, b 1 -> 0, c 1 -> 2, d 0 -> 1: every Working Set entity moved by one.
            assertEquals(4, r.at("/comparison/activity/entities/changed/count").asInt(), r.toString());
            assertEquals("a", r.at("/comparison/activity/entities/changed/items/0/id").asText());
            assertEquals(2, r.at("/comparison/activity/entities/changed/items/0/countA").asInt());
            assertEquals(1, r.at("/comparison/activity/entities/changed/items/0/countB").asInt());
            assertTrue(r.at("/comparison/links/onlyA/count").isNumber(), "presence sections stay alongside");

            JsonNode entry = get(c, "/inv/investigations/case-a/log").at("/entries/3");
            assertTrue(entry.get("text").asText().contains("by event count") && entry.get("text").asText().contains("changed on 2 links and 4 entities"),
                    entry.get("text").asText());
            assertEquals(2, entry.at("/comparison/activity/links/changed").asInt(), "the log view carries counts only: " + entry);

            // Same inputs, same fingerprint; a presence diff of the same windows seals a different one (mode is in the content).
            String fp = r.at("/comparison/fingerprint").asText();
            assertEquals(fp, post(c, OPS, ACTIVITY).at("/comparison/fingerprint").asText(), "deterministic");
            assertNotEquals(fp, post(c, OPS, COMPARE).at("/comparison/fingerprint").asText());

            assertTrue(post(c, "/inv/investigations/case-a/replay", "{}").get("equivalent").asBoolean());
            JsonNode d = get(c, "/inv/investigations/case-a/dossier");
            assertTrue(d.at("/integrity/intact").asBoolean(), d.at("/integrity").toString());
            assertEquals(d.at("/manifest/root").asText(), get(c, "/inv/investigations/case-a/dossier").at("/manifest/root").asText());

            // Tamper with a sealed delta on disk: the fingerprint no longer matches the content.
            Path logFile = root.resolve("audit/snapshots/investigations/case-a/log.jsonl");
            List<String> lines = Files.readAllLines(logFile);
            String forged = lines.get(3).replaceFirst("\"delta\":-1", "\"delta\":-9");
            assertNotEquals(lines.get(3), forged, "the probe edited nothing");
            lines.set(3, forged);
            Files.write(logFile, lines);
            assertFalse(get(c, "/inv/investigations/case-a/dossier").at("/integrity/intact").asBoolean());

            assertEquals(422, send(c, "POST", OPS, COMPARE.replace("\"op\":\"compare\",", "\"op\":\"compare\",\"mode\":\"bogus\","), null).statusCode());
        }
    }

    @Test
    void minAbsDeltaIsSealedOnlyWhenGivenAndHidesSmallerMoves(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            String plain = post(c, OPS, ACTIVITY).at("/comparison/fingerprint").asText();
            // Every move in this corpus is one event: a floor of 2 lists none and counts them as below the floor.
            JsonNode r = post(c, OPS, ACTIVITY.replace("\"mode\":\"activity\",", "\"mode\":\"activity\",\"minAbsDelta\":2,"));
            assertEquals(2, r.at("/comparison/activity/minAbsDelta").asInt(), r.toString());
            assertEquals(0, r.at("/comparison/activity/links/changed/count").asInt(), r.toString());
            assertEquals(2, r.at("/comparison/activity/links/belowMinDelta").asInt());
            assertEquals(4, r.at("/comparison/activity/entities/belowMinDelta").asInt());
            assertEquals(1, r.at("/comparison/activity/links/unchanged").asInt(), "unchanged keeps its meaning");
            assertNotEquals(plain, r.at("/comparison/fingerprint").asText(), "the floor is in the hashed content");
            // A floor of 1 lists everything the plain diff does, but states the floor.
            JsonNode one = post(c, OPS, ACTIVITY.replace("\"mode\":\"activity\",", "\"mode\":\"activity\",\"minAbsDelta\":1,"));
            assertEquals(2, one.at("/comparison/activity/links/changed/count").asInt());
            assertEquals(0, one.at("/comparison/activity/links/belowMinDelta").asInt());
            // Without it the sealed diff carries no trace of it, so earlier seals verify unchanged.
            assertFalse(post(c, OPS, ACTIVITY).at("/comparison/activity").has("minAbsDelta"));
            assertEquals(plain, post(c, OPS, ACTIVITY).at("/comparison/fingerprint").asText());
            assertTrue(post(c, "/inv/investigations/case-a/replay", "{}").get("equivalent").asBoolean());
            assertTrue(get(c, "/inv/investigations/case-a/dossier").at("/integrity/intact").asBoolean());
            // Refusals: presence mode, zero, non-integer.
            assertEquals(422, send(c, "POST", OPS, COMPARE.replace("\"op\":\"compare\",", "\"op\":\"compare\",\"minAbsDelta\":2,"), null).statusCode());
            assertEquals(422, send(c, "POST", OPS, ACTIVITY.replace("\"mode\":\"activity\",", "\"mode\":\"activity\",\"minAbsDelta\":0,"), null).statusCode());
            assertEquals(422, send(c, "POST", OPS, ACTIVITY.replace("\"mode\":\"activity\",", "\"mode\":\"activity\",\"minAbsDelta\":\"x\","), null).statusCode());
        }
    }

    @Test
    void aSideMayInheritTheInvestigationsWindow(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            String inheritB = ACTIVITY.replaceAll("\"windowB\":\\{[^}]*\\}", "\"windowB\":\"inherit\"");
            assertEquals(422, send(c, "POST", OPS, inheritB, null).statusCode(), "no window set yet: nothing to inherit");
            assertEquals(3, Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).size(), "the refusal appended nothing");

            post(c, OPS, "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-03T00:00:00-03:00\",\"to\":\"2026-09-06T00:00:00-03:00\","
                    + "\"timezone\":\"America/Sao_Paulo\"}}");
            JsonNode inherited = post(c, OPS, inheritB);
            JsonNode explicit = post(c, OPS, ACTIVITY);
            assertEquals("2026-09-03T03:00:00Z", inherited.at("/comparison/windowB/from").asText(), "frozen as the concrete window it was");
            assertEquals("B", inherited.at("/comparison/inherited/0").asText());
            assertEquals(explicit.at("/comparison/links").toString(), inherited.at("/comparison/links").toString());
            assertEquals(explicit.at("/comparison/activity").toString(), inherited.at("/comparison/activity").toString());
            String text = get(c, "/inv/investigations/case-a/log").at("/entries/4/text").asText();
            assertTrue(text.contains("the Investigation's window (from 2026-09-03T03:00:00Z"), text);
            assertNotEquals(explicit.at("/comparison/fingerprint").asText(), inherited.at("/comparison/fingerprint").asText(), "the inherit is part of the sealed content");

            // Both sides inherited is allowed (a window diffed with itself: nothing moves).
            JsonNode same = post(c, OPS, "{\"op\":\"compare\",\"windowA\":\"inherit\",\"windowB\":\"inherit\",\"mode\":\"activity\"}");
            assertEquals(0, same.at("/comparison/activity/links/changed/count").asInt());

            // The stored params keep "inherit"; replay carries the sealed diff.
            String line = Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).get(4);
            assertTrue(line.contains("\"windowB\":\"inherit\""), line);
            assertTrue(post(c, "/inv/investigations/case-a/replay", "{}").get("equivalent").asBoolean());
        }
    }

    @Test
    void aDossierCarriesTheSealedDiffInCustodyAndItsRootIsDeterministic(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            build(c);
            JsonNode plain = get(c, "/inv/investigations/case-a/dossier");
            assertTrue(plain.path("comparisons").isMissingNode(), "no compare op, no section");
            post(c, OPS, COMPARE);

            JsonNode d1 = get(c, "/inv/investigations/case-a/dossier");
            JsonNode d2 = get(c, "/inv/investigations/case-a/dossier");
            assertEquals(1, d1.get("comparisons").size(), d1.toString());
            assertEquals(4, d1.at("/comparisons/0/step").asInt());
            assertEquals("c", d1.at("/comparisons/0/comparison/links/onlyB/items/0/source").asText());
            assertEquals(d1.at("/manifest/root").asText(), d2.at("/manifest/root").asText(), "the root is deterministic");
            assertTrue(d1.at("/integrity/intact").asBoolean(), d1.at("/integrity").toString());
            assertTrue(d1.at("/renderings/steps").toString().contains("Compared"));

            JsonNode verified = post(c, "/inv/investigations/case-a/dossier/verify", d1.get("manifest").toString());
            assertTrue(verified.get("verified").asBoolean(), verified.toString());

            // Tamper with the sealed diff on disk: custody and integrity must both notice (a probe that WOULD pass if the
            // comparison were outside the manifest).
            Path logFile = root.resolve("audit/snapshots/investigations/case-a/log.jsonl");
            List<String> lines = Files.readAllLines(logFile);
            String forged = lines.get(3).replaceFirst("\"eventsA\":2", "\"eventsA\":9");
            assertNotEquals(lines.get(3), forged, "the probe edited nothing: " + lines.get(3));
            lines.set(3, forged);
            Files.write(logFile, lines);

            JsonNode afterVerify = post(c, "/inv/investigations/case-a/dossier/verify", d1.get("manifest").toString());
            assertFalse(afterVerify.get("verified").asBoolean(), afterVerify.toString());
            assertTrue(afterVerify.get("changed").toString().contains("log.jsonl#4"), afterVerify.toString());
            JsonNode d3 = get(c, "/inv/investigations/case-a/dossier");
            assertFalse(d3.at("/integrity/intact").asBoolean(), "the sealed fingerprint no longer matches the content");
            assertTrue(d3.at("/integrity/failures").toString().contains("sealed comparison"), d3.at("/integrity").toString());
        }
    }

    @Test
    void masksTheSealedDiffAndRefusesWhatItCannotSeal(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
        try (Ctx c = open(cfg, root)) {
            build(c);
            String body = post(c, OPS, COMPARE).toString();
            for (String raw : List.of("\"a\"", "\"b\"", "\"c\"", "\"d\"", "\"a>"))
                assertFalse(body.contains(raw), "a raw id leaked under masking_mode all: " + body);
            String dossier = get(c, "/inv/investigations/case-a/dossier").get("comparisons").toString();
            assertFalse(dossier.contains("\"a\"") || dossier.contains("\"c\""), dossier);

            assertEquals(422, send(c, "POST", OPS, "{\"op\":\"compare\",\"windowA\":{\"from\":\"2026-09-01T00:00:00Z\"}}", null).statusCode(), "window B missing");
            assertEquals(422, send(c, "POST", OPS, COMPARE.replace("\"op\":\"compare\",", "\"op\":\"compare\",\"ids\":[\"a\"],"), null).statusCode(), "ids");
            assertEquals(422, send(c, "POST", OPS, COMPARE.replace("2026-09-01T00:00:00-03:00", "2026-09-01T00:00:00"), null).statusCode(), "naive instant");
            post(c, "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"timeless\",\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}");
            assertEquals(422, send(c, "POST", "/inv/investigations/timeless/ops", COMPARE, null).statusCode(), "no time column");
            assertEquals(4, Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).size() - 0, "refusals append nothing");
        }
    }

    @Test
    void isGatedOnCanManageIncidents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer plain" -> Optional.of(new Subject("analyst-2", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, send(c, "POST", "/inv/investigations", "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\","
                    + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"timeCol\":\"ts\",\"timeColZone\":\"America/Sao_Paulo\"}", "Bearer owner").statusCode());
            assertEquals(200, send(c, "POST", OPS, "{\"op\":\"seed\",\"ids\":[\"a\"]}", "Bearer owner").statusCode());
            assertEquals(401, send(c, "POST", OPS, COMPARE, null).statusCode());
            assertEquals(403, send(c, "POST", OPS, COMPARE, "Bearer plain").statusCode());
            assertEquals(200, send(c, "POST", OPS, COMPARE, "Bearer owner").statusCode());
        } finally {
            Authenticators.forTest(null);
        }
    }
}
