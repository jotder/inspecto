package com.gamma.control;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-U9 remainder (operator 2026-09-30) — link annotations addressed by a wire id: the evaluator's key
 * {@code source␀target␀kind}, base64url-encoded behind {@code lk.}, served as {@code linkId} on the Working Set's
 * {@code links} relation and accepted by the {@code annotate} op's {@code links}. Under masking the id is minted from
 * the pseudonyms, so it never carries a raw masked value, and it resolves back through the Investigation's mask.
 */
class ControlApiInvestigationLinkAnnotationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003";
    private static final String CREATE = "{\"id\":\"case-a\",\"purpose\":\"Fraud referral FR-7\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String INV = "/inv/investigations/case-a";
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
                    "SELECT caller, callee, channel FROM (VALUES ('" + A + "','" + B + "','voice'),('" + A + "','" + C
                            + "','sms')) AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(c, method, path, body);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static String encode(String s, String t, String k) {
        return "lk." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString((s + "\u0000" + t + "\u0000" + k).getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String id) {
        return new String(Base64.getUrlDecoder().decode(id.substring(3)), StandardCharsets.UTF_8);
    }

    /** linkId per "source→target" as the links relation serves it. */
    private Map<String, String> linkIds(Ctx c) throws Exception {
        JsonNode ws = ok(c, "GET", INV + "/working-set?of=links", null);
        assertTrue(ws.get("columns").toString().contains("linkId"), ws.toString());
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode r : ws.get("rows")) out.put(r.get("source").asText() + "→" + r.get("target").asText(), r.get("linkId").asText());
        return out;
    }

    private void seedAndExpand(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE);
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}");
    }

    @Test
    void aLinkIsAnnotatedByItsServedIdAndTheNoteIsSealedLoggedAndOutlivesAnExclusion(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedAndExpand(c);
            Map<String, String> ids = linkIds(c);
            String ab = ids.get(A + "→" + B);
            assertEquals(encode(A, B, "voice"), ab, "the id IS the encoded evaluator key");

            JsonNode step = ok(c, "POST", INV + "/ops", "{\"op\":\"annotate\",\"links\":[\"" + ab + "\"],\"note\":\"shared SIM box\","
                    + "\"confidence\":\"B2\"}");
            assertEquals("annotate", step.get("op").asText());

            JsonNode replay = ok(c, "POST", INV + "/replay", "{}");
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            JsonNode notes = replay.at("/workingSet/linkAnnotations");
            assertEquals(1, notes.size(), replay.toString());
            assertEquals(A, notes.get(0).get("source").asText());
            assertEquals(B, notes.get(0).get("target").asText());
            assertEquals("voice", notes.get(0).get("kind").asText());
            assertEquals("B2", notes.get(0).get("confidence").asText());
            assertEquals(ab, notes.get(0).get("linkId").asText(), "the note is addressable by the same id");
            assertTrue(replay.at("/workingSet/annotations").isMissingNode(), "no entity was annotated");

            String text = ok(c, "GET", INV + "/log", null).at("/entries/2/text").asText();
            assertTrue(text.contains("link " + A + " → " + B + " (voice)") && text.contains("shared SIM box"), text);

            ok(c, "POST", INV + "/ops", "{\"op\":\"exclude\",\"ids\":[\"" + B + "\"],\"reason\":\"not relevant\"}");
            JsonNode after = ok(c, "POST", INV + "/replay", "{}");
            assertEquals(1, after.at("/workingSet/linkAnnotations").size(), "a note is history: it outlives the exclusion");
            assertFalse(linkIds(c).containsKey(A + "→" + B), "...though the link itself left the Working Set");
        }
    }

    @Test
    void refusesALinkNotInTheWorkingSetOrAnIdItDidNotServeAndWritesNothing(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedAndExpand(c);
            Path log = root.resolve("audit/snapshots/investigations/case-a/log.jsonl");
            long before = Files.readAllLines(log).size();
            for (String body : List.of(
                    "{\"op\":\"annotate\",\"links\":[\"" + encode(B, A, "voice") + "\"],\"note\":\"n\"}",   // reversed
                    "{\"op\":\"annotate\",\"links\":[\"" + encode(A, B, "sms") + "\"],\"note\":\"n\"}",     // other kind
                    "{\"op\":\"annotate\",\"links\":[\"" + A + "|" + B + "|voice\"],\"note\":\"n\"}",       // not a wire id
                    "{\"op\":\"annotate\",\"links\":[\"lk.%%%\"],\"note\":\"n\"}",
                    "{\"op\":\"annotate\",\"links\":[],\"note\":\"n\"}",
                    "{\"op\":\"annotate\",\"links\":\"" + encode(A, B, "voice") + "\",\"note\":\"n\"}",
                    "{\"op\":\"annotate\",\"links\":[\"" + encode(A, B, "voice") + "\"]}")) {                 // no note
                HttpResponse<String> r = send(c, "POST", INV + "/ops", body);
                assertEquals(422, r.statusCode(), body + " → " + r.body());
            }
            assertEquals(before, Files.readAllLines(log).size(), "a refusal writes nothing");
        }
    }

    @Test
    void underMaskingTheIdCarriesOnlyPseudonymsAndResolvesBackToTheRawLink(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedAndExpand(c);
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: all\n");
            Map<String, String> ids = linkIds(c);
            assertEquals(2, ids.size());
            for (var e : ids.entrySet()) {
                assertFalse(e.getKey().contains(A), "the endpoints are masked: " + e.getKey());
                String key = decode(e.getValue());
                assertFalse(key.contains(A) || key.contains(B) || key.contains(C), "the id leaks no raw key: " + key);
                assertTrue(key.startsWith("masked:"), key);
            }
            String masked = ids.values().stream().filter(v -> decode(v).endsWith("\u0000voice")).findFirst().orElseThrow();
            ok(c, "POST", INV + "/ops", "{\"op\":\"annotate\",\"links\":[\"" + masked + "\"],\"note\":\"masked link\"}");

            String sealed = Files.readAllLines(root.resolve("audit/snapshots/investigations/case-a/log.jsonl")).get(2);
            assertTrue(sealed.contains("\"source\":\"" + A + "\"") && sealed.contains("\"target\":\"" + B + "\""),
                    "the pseudonyms resolve to the raw link before sealing: " + sealed);

            JsonNode replay = ok(c, "POST", INV + "/replay", "{}");
            JsonNode note = replay.at("/workingSet/linkAnnotations/0");
            assertEquals(masked, note.get("linkId").asText(), "the same id reads back under the same mask");
            assertFalse(replay.toString().contains(A), "nothing raw in a masked answer: " + replay);
            assertFalse(ok(c, "GET", INV + "/log", null).toString().contains(A), "nor in the log");

            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            assertEquals(encode(A, B, "voice"), linkIds(c).get(A + "→" + B), "unmasked, the id is the raw key again");
        }
    }
}
