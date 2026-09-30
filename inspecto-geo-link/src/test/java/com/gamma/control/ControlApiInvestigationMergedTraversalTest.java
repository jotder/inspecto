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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 merged traversal over real HTTP (operator decisions 2026-09-30; design §8.3): {@code merged: true} on
 * {@code expand} / {@code exclude}, opt-in per op, while a {@code resolve} is in force. A merged expand fans out from
 * EVERY member of the groups it touches (including a member value never admitted, found through its sealed type
 * normaliser); a merged exclude removes every admitted member and blocks the group's keys; the fences count the
 * COMBINED fan-out; replay / fork / template / Dossier / masking all read the seal.
 *
 * <p>Fixture over {@code (caller, callee, channel)}, both columns {@code MSISDN}: {@code A-B call},
 * {@code C-D call}, {@code D-E sms}. One assertion: {@code msisdn:+447700900001 = msisdn:+447700900003}, i.e. A and C
 * (C is spelled {@code "0044 7700-900003"}, so only the sealed e164 normaliser finds it).
 */
class ControlApiInvestigationMergedTraversalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "+447700900001", B = "+447700900002", C = "0044 7700-900003";
    private static final String D = "+447700900004", E = "+447700900005";
    private static final String KEY_A = "msisdn:+447700900001", KEY_C = "msisdn:+447700900003";
    private static final String ROWS = "('" + A + "','" + B + "','call'),('" + C + "','" + D + "','call'),('"
            + D + "','" + E + "','sms')";
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"case-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
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
                    "SELECT * FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-09-30T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"),
                            Map.of("name", "callee", "classification", "MSISDN"))));
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

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c, "POST", path, body);
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode op(Ctx c, String body) throws Exception {
        return data(post(c, OPS, body), 200);
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> out = new TreeSet<>();
        for (JsonNode n : array) out.add(n.asText());
        return out;
    }

    private static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    private static Path logFile(Path root, String id) {
        return root.resolve("audit/snapshots/investigations").resolve(id).resolve("log.jsonl");
    }

    /** The assertion A = C, the Investigation, and seed A. */
    private void base(Ctx c) throws Exception {
        data(post(c, "/inv/entity-identities", "{\"a\":\"" + KEY_A + "\",\"b\":\"" + KEY_C + "\",\"reason\":\"same SIM swap\"}"), 201);
        data(post(c, "/inv/investigations", CREATE), 200);
        op(c, "{\"op\":\"seed\",\"ids\":[\"" + A + "\"]}");
    }

    private Map<String, Integer> hops(Ctx c) throws Exception {
        JsonNode ws = data(post(c, "/inv/investigations/case-a/replay", "{}"), 200);
        Map<String, Integer> out = new TreeMap<>();
        for (JsonNode e : ws.at("/workingSet/entities")) out.put(e.get("id").asText(), e.get("hop").asInt());
        return out;
    }

    @Test
    void aMergedExpandFansOutFromEveryMemberAndAPlainOneStaysOnTheRawIdentifier(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            op(c, "{\"op\":\"resolve\"}");
            JsonNode plain = op(c, "{\"op\":\"expand\"}");
            assertEquals(Set.of(B), texts(plain.at("/delta/admitted")), "display-only default: A's own row only");
            data(post(c, "/inv/investigations/case-a/undo", "{}"), 200);

            JsonNode merged = op(c, "{\"op\":\"expand\",\"merged\":true}");
            assertEquals(Set.of(B, C, D), texts(merged.at("/delta/admitted")), "fans out from A AND its member C: " + merged);
            assertEquals(Map.of(A, 0, B, 1, C, 0, D, 1), hops(c), "C is A's identity (its hop and seed), D one hop from it");
            assertEquals(2, merged.at("/read/rowCount").asInt());
            assertEquals(C, merged.at("/read/rung/merged/anchorOf").fieldNames().next(), merged.at("/read/rung").toString());
            assertEquals(A, merged.at("/read/rung/merged/anchorOf/" + C.replace("/", "~1")).asText());

            JsonNode rep = data(post(c, "/inv/investigations/case-a/replay", "{\"reread\":true}"), 200);
            assertTrue(rep.get("equivalent").asBoolean(), rep.toString());
            assertFalse(rep.get("diverged").asBoolean(), "the sealed widened read re-runs identically: " + rep);
            JsonNode d = data(send(c, "GET", "/inv/investigations/case-a/dossier", null), 200);
            assertTrue(d.at("/integrity/intact").asBoolean(), d.get("integrity").toString());
            JsonNode m = data(send(c, "GET", "/inv/investigations/case-a/measures", null), 200);
            Map<String, Double> measures = new TreeMap<>();
            for (JsonNode n : m.get("measures")) measures.put(n.get("name").asText(), n.get("value").asDouble());
            assertEquals(4.0, measures.get("entities"));
            assertEquals(3.0, measures.get("identities"), "A and C count once: " + m);
        }
    }

    @Test
    void mergedIsRefusedWithoutAResolutionOrWhenNothingResolvesAndNothingReachesTheLog(@TempDir Path cfg,
                                                                                       @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            HttpResponse<String> r = post(c, OPS, "{\"op\":\"expand\",\"merged\":true}");
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("no resolve is in force"), r.body());
            assertEquals(422, post(c, OPS, "{\"op\":\"exclude\",\"ids\":[\"" + A + "\"],\"reason\":\"x\",\"merged\":true}")
                    .statusCode(), "a merged exclude needs a resolution too");
            assertEquals(422, post(c, OPS, "{\"op\":\"expand\",\"merged\":\"yes\"}").statusCode(), "merged is a boolean");
            op(c, "{\"op\":\"resolve\"}");
            op(c, "{\"op\":\"expand\"}");   // admits B, which is in no group
            assertEquals(422, post(c, OPS, "{\"op\":\"exclude\",\"ids\":[\"" + B + "\"],\"reason\":\"x\",\"merged\":true}")
                    .statusCode(), "B resolves to no group");
            assertEquals(3, Files.readAllLines(logFile(root, "case-a")).size(), "no refused op reached the log");
        }
    }

    /** maxFanOut and budget bound the WHOLE group's widened fan-out, and four-eyes applies to it unchanged. */
    @Test
    void theFencesCountTheCombinedFanOutOfTheGroup(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            op(c, "{\"op\":\"resolve\"}");
            JsonNode capped = op(c, "{\"op\":\"expand\",\"merged\":true,\"maxFanOut\":1}");
            assertEquals(1, capped.at("/read/rowCount").asInt(), "ONE row for the group, not one per member: " + capped);
            assertEquals(1, capped.at("/read/fanOutCapped").asLong());
            data(post(c, "/inv/investigations/case-a/undo", "{}"), 200);
            JsonNode budget = op(c, "{\"op\":\"expand\",\"merged\":true,\"budget\":1}");
            assertEquals(1, budget.at("/read/rowCount").asInt());
            assertTrue(budget.get("truncated").asBoolean(), budget.toString());
            data(post(c, "/inv/investigations/case-a/undo", "{}"), 200);

            settings(c, "masking_mode: none\nfour_eyes_fan_out_above: 5\n");
            JsonNode pending = op(c, "{\"op\":\"expand\",\"merged\":true}");
            assertEquals("pending", pending.get("status").asText(), "an unbounded merged fan-out needs a second person: " + pending);
            assertTrue(pending.at("/pending/params/merged").asBoolean(), "the request carries the opt-in to the approver");
        }
    }

    @Test
    void aMergedExcludeRemovesEveryMemberBlocksTheGroupAndLogsOneLine(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            op(c, "{\"op\":\"seed\",\"ids\":[\"" + D + "\"]}");
            op(c, "{\"op\":\"expand\"}");   // A->B, D->C, D->E
            op(c, "{\"op\":\"resolve\"}");
            JsonNode plain = op(c, "{\"op\":\"exclude\",\"ids\":[\"" + A + "\"],\"reason\":\"test SIM\"}");
            assertEquals(Set.of(A), texts(plain.at("/delta/removed")), "display-only default: C stays");
            data(post(c, "/inv/investigations/case-a/undo", "{}"), 200);

            JsonNode x = op(c, "{\"op\":\"exclude\",\"ids\":[\"" + A + "\"],\"reason\":\"test SIM\",\"merged\":true}");
            assertEquals(Set.of(A, C),
                    texts(x.at("/delta/removed")), "A and its member C both leave: " + x);
            JsonNode again = op(c, "{\"op\":\"expand\"}");
            assertFalse(texts(again.at("/delta/admitted")).contains(C), "the group's keys are never admitted again: " + again);

            JsonNode logView = data(send(c, "GET", "/inv/investigations/case-a/log", null), 200);
            String line = logView.at("/entries/6/text").asText();
            assertTrue(line.contains("Excluded identity group " + KEY_A) && line.contains(KEY_A + ", " + KEY_C), line);
            assertTrue(data(post(c, "/inv/investigations/case-a/replay", "{}"), 200).get("equivalent").asBoolean());
        }
    }

    /** D-U6: the merged exclude's line lists members, each masked by its own type. */
    @Test
    void theMergedExcludeLineMasksEachMemberKey(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: typed\n");   // msisdn is a masked type by default
            base(c);
            op(c, "{\"op\":\"resolve\"}");
            op(c, "{\"op\":\"exclude\",\"ids\":[\"" + A + "\"],\"reason\":\"test SIM\",\"merged\":true}");
            String logView = data(send(c, "GET", "/inv/investigations/case-a/log", null), 200).toString();
            for (String raw : List.of("7700900001", "7700900003"))
                assertFalse(logView.contains(raw), raw + " leaked: " + logView);
            assertTrue(logView.contains("Excluded identity group masked:"), logView);
        }
    }

    /** D-E8: the flag is method - a template carries it inside the expand; a fork re-reads the widened frontier. */
    @Test
    void aTemplateCarriesTheFlagInsideTheOpAndAForkReReadsIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            settings(c, "masking_mode: none\n");
            base(c);
            op(c, "{\"op\":\"resolve\"}");
            op(c, "{\"op\":\"expand\",\"merged\":true}");

            data(post(c, "/inv/investigations/case-a/reorder", "{\"id\":\"case-f\",\"order\":[1,2,3]}"), 200);
            JsonNode fork = data(post(c, "/inv/investigations/case-f/replay", "{}"), 200);
            assertTrue(fork.get("equivalent").asBoolean(), fork.toString());
            assertEquals(4, fork.at("/workingSet/entities").size(), fork.toString());
            HttpResponse<String> early = post(c, "/inv/investigations/case-a/reorder", "{\"id\":\"case-g\",\"order\":[1,3,2]}");
            assertEquals(422, early.statusCode(), "a merged expand re-ordered before its resolve has nothing to merge: " + early.body());

            JsonNode tpl = data(post(c, "/inv/investigations/case-a/template", "{\"id\":\"tpl-m\"}"), 200);
            assertTrue(tpl.at("/ops/2/merged").asBoolean(), tpl.at("/ops").toString());
            data(post(c, "/inv/investigation-templates/tpl-m/instantiate",
                    "{\"id\":\"case-t\",\"purpose\":\"rerun\",\"params\":{\"seed1\":[\"" + A + "\"]}}"), 200);
            JsonNode t = data(post(c, "/inv/investigations/case-t/replay", "{}"), 200);
            Set<String> ids = new TreeSet<>();
            for (JsonNode e : t.at("/workingSet/entities")) ids.add(e.get("id").asText());
            assertEquals(Set.of(A, B, C, D), ids, "the instantiated method fans out merged too: " + t);
        }
    }
}
