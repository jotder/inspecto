package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-GROW-EXCLUDE-1 phases 1-2 over real HTTP: the Grow preview. {@code POST /inv/investigations/{id}/expand/preview} dry-runs
 * the expand its body describes - its counts equal the delta of that expand appended right after (PARITY), it appends
 * nothing, it masks held ids as the appended step would, it reports four-eyes as {@code needsApproval} instead of holding,
 * and it is refused on Basic. {@code POST /inv/projection/neighbors/preview} answers the same counts for the explorer, with no
 * Investigation, on Basic too. An {@code exclude} seals the "busy number" reason the Remove-held action sends.
 *
 * <p>Fixture over {@code (caller, callee, channel)}: {@code A1-HUB9}, {@code A1-B2}, {@code HUB9-X1..X5} (voice) and
 * {@code B2-C3} (sms). Distinct contacts: {@code HUB9} 6, {@code A1} 2, {@code B2} 2, the rest 1. List {@code cc}
 * (subscriber, default normaliser) holds {@code x1, x2, x3}.
 */
class ControlApiGrowPreviewTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROWS = String.join(",",
            "('A1','HUB9','voice')", "('A1','B2','voice')", "('HUB9','X1','voice')", "('HUB9','X2','voice')", "('HUB9','X3','voice')",
            "('HUB9','X4','voice')", "('HUB9','X5','voice')", "('B2','C3','sms')");
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"%s\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"}";
    private static final String BINDING = "\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\"";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void restore() {
        Authenticators.forTest(null);
        EditionFeatures.overrideForTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot, String settings) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                    "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)", "2026-10-22T00:00:00Z"));
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view"));
            Files.writeString(writeRoot.resolve("link-analysis.toon"), settings);
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c, "POST", path, body, null);
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private JsonNode op(Ctx c, String id, String body) throws Exception {
        return data(post(c, "/inv/investigations/" + id + "/ops", body), 200);
    }

    private JsonNode preview(Ctx c, String id, String body) throws Exception {
        return data(post(c, "/inv/investigations/" + id + "/expand/preview", body), 200);
    }

    /** A new Investigation over the fixture, seeded with {@code A1}. */
    private void seeded(Ctx c, String id) throws Exception {
        data(post(c, "/inv/investigations", CREATE.formatted(id)), 200);
        op(c, id, "{\"op\":\"seed\",\"ids\":[\"A1\"]}");
    }

    private void callCentres(Ctx c) throws Exception {
        data(post(c, "/entity-lists", "{\"id\":\"cc\",\"title\":\"Call centres\",\"purpose\":\"exclusion\",\"entityType\":\"subscriber\","
                + "\"reason\":\"switchboards\"}"), 201);
        data(post(c, "/entity-lists/cc/members", "{\"add\":[\"x1\",\"x2\",\"x3\"],\"reason\":\"r\"}"), 200);
    }

    private static int logLines(Path root, String id) throws Exception {
        return Files.readAllLines(root.resolve("audit/snapshots/investigations").resolve(id).resolve("log.jsonl")).size();
    }

    private String workingSetHash(Ctx c, String id) throws Exception {
        return data(post(c, "/inv/investigations/" + id + "/replay", "{}"), 200).at("/workingSet").toString();
    }

    /**
     * The parity check: preview {@code body}, prove it appended nothing, append the same body as an {@code expand}, and
     * compare - nodes = admitted, links = linksAdded, heldCount = hubsFlagged and the held ids = the read's flagged hubs,
     * truncated and the read path as the append read. Returns the preview.
     */
    private JsonNode previewThenAppend(Ctx c, String id, String body) throws Exception {
        int lines = logLines(c.root(), id);
        String before = workingSetHash(c, id);
        JsonNode p = preview(c, id, body);
        assertEquals(lines, logLines(c.root(), id), "a preview appends nothing: " + p);
        assertEquals(before, workingSetHash(c, id), "a preview leaves the Working Set as it was");
        assertFalse(p.get("needsApproval").asBoolean(), p.toString());

        JsonNode step = op(c, id, "{}".equals(body) ? "{\"op\":\"expand\"}" : "{\"op\":\"expand\"," + body.substring(1));
        assertEquals(lines + 1, logLines(c.root(), id));
        assertEquals(step.at("/delta/admitted").size(), p.get("addsNodes").asInt(), "nodes: " + p + " vs " + step);
        assertEquals(step.at("/delta/linksAdded").asInt(), p.get("addsLinks").asInt(), "links: " + p + " vs " + step);
        assertEquals(step.at("/read/hubsFlagged").asInt(0), p.get("heldCount").asInt(), "held: " + p + " vs " + step);
        assertEquals(p.get("heldCount").asInt(), p.get("held").size());
        for (JsonNode h : p.get("held")) {
            boolean flagged = false;
            for (JsonNode hub : step.at("/read/hubs"))
                flagged |= hub.get("id").asText().equals(h.get("id").asText()) && hub.get("degree").asLong() == h.get("contacts").asLong();
            assertTrue(flagged, "each held id is a hub the append flags, at its degree: " + h + " vs " + step.at("/read/hubs"));
        }
        assertEquals(step.get("truncated").asBoolean(), p.get("truncated").asBoolean());
        assertEquals(step.at("/read/index").isMissingNode() ? "flat" : "index", p.get("readPath").asText());
        if (!step.at("/read/fallback/reason").isMissingNode())
            assertEquals(step.at("/read/fallback/reason").asText(), p.get("reason").asText());
        return p;
    }

    // ── the Investigation preview ──────────────────────────────────────────────────────────────────────

    @Test
    void previewCountsEqualTheExpandAppendedRightAfterAndAppendNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            seeded(c, "p");
            JsonNode first = previewThenAppend(c, "p", "{\"hubThreshold\":3}");
            assertEquals(2, first.get("addsNodes").asInt(), first.toString());   // B2, HUB9
            assertEquals(2, first.get("addsLinks").asInt());
            assertEquals(1, first.get("heldCount").asInt());
            assertEquals("HUB9", first.at("/held/0/id").asText());
            assertEquals(6, first.at("/held/0/contacts").asInt(), "contacts = distinct contacts over the Dataset");
            assertEquals(0, first.get("excludedByLists").size(), "no list in force");

            JsonNode second = previewThenAppend(c, "p", "{}");   // HUB9 is held out of the frontier, so only C3 is added
            assertEquals(1, second.get("addsNodes").asInt(), second.toString());
            assertEquals(0, second.get("heldCount").asInt());

            seeded(c, "q");   // direction, link kinds and a truncating budget read and count as the append does
            assertEquals(1, previewThenAppend(c, "q", "{\"direction\":\"out\",\"linkKinds\":[\"voice\"],\"budget\":1}").get("addsNodes").asInt());
            JsonNode in = previewThenAppend(c, "q", "{\"direction\":\"in\",\"hubThreshold\":3}");
            assertEquals(0, in.get("addsNodes").asInt(), "nothing calls A1 or its admitted neighbour: " + in);
            JsonNode through = previewThenAppend(c, "q", "{\"includeHubs\":true,\"hubThreshold\":10}");
            assertTrue(through.get("addsNodes").asInt() > 0, through.toString());
        }
    }

    @Test
    void theSameValidationAsTheAppend(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            seeded(c, "v");
            int lines = logLines(root, "v");
            assertEquals(422, post(c, "/inv/investigations/v/expand/preview", "{\"direction\":\"sideways\"}").statusCode());
            assertEquals(422, post(c, "/inv/investigations/v/expand/preview", "{\"ids\":[\"C3\"]}").statusCode(), "not in the Working Set");
            assertEquals(422, post(c, "/inv/investigations/v/expand/preview", "{\"op\":\"exclude\",\"ids\":[\"A1\"]}").statusCode(),
                    "a preview dry-runs an expand only");
            assertEquals(422, post(c, "/inv/investigations/v/expand/preview", "{\"limit\":5}").statusCode(), "the renamed key, as the append");
            assertEquals(404, post(c, "/inv/investigations/nope/expand/preview", "{}").statusCode());
            assertEquals(lines, logLines(root, "v"), "no refusal wrote anything");
        }
    }

    @Test
    void exclusionListsInForceReportTheirRemovedCountRounded(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            callCentres(c);
            seeded(c, "x");
            op(c, "x", "{\"op\":\"expand\",\"hubThreshold\":3}");
            op(c, "x", "{\"op\":\"excludeBy\",\"listId\":\"cc\",\"reason\":\"call centres\"}");
            JsonNode p = previewThenAppend(c, "x", "{\"ids\":[\"HUB9\"],\"expandHubs\":[\"HUB9\"]}");
            assertEquals(2, p.get("addsNodes").asInt(), "X1..X3 are removed by the list: " + p);
            assertEquals(1, p.get("excludedByLists").size(), p.toString());
            JsonNode line = p.at("/excludedByLists/0");
            assertEquals("cc", line.get("listId").asText());
            assertEquals("Call centres", line.get("title").asText());
            assertEquals(5, line.get("removed").asInt(), "3 removed reads as 5: a list without open visibility is rounded");
            assertTrue(line.get("rounded").asBoolean());

            JsonNode none = previewThenAppend(c, "x", "{\"ids\":[\"B2\"]}");   // C3: the list is in force and removes nothing
            assertEquals(0, none.at("/excludedByLists/0/removed").asInt(), "0 stays 0: " + none);
        }
    }

    @Test
    void heldIdsAreMaskedAsTheAppendedStepMasksThem(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            seeded(c, "m");
            HttpResponse<String> raw = post(c, "/inv/investigations/m/expand/preview", "{\"hubThreshold\":3}");
            JsonNode p = data(raw, 200);
            String token = p.at("/held/0/id").asText();
            assertTrue(token.matches("masked:[0-9a-f]{16}"), p.toString());
            assertFalse(raw.body().contains("HUB9"), raw.body());
            assertEquals("all", p.at("/masking/mode").asText());
            JsonNode step = op(c, "m", "{\"op\":\"expand\",\"hubThreshold\":3}");
            assertEquals(token, step.at("/read/hubs/0/id").asText(), "the same pseudonym the appended step shows");
        }
    }

    @Test
    void aSensitiveExpandIsReportedNotHeldAndNotRead(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\nfour_eyes_budget_above: 100\n")) {
            seeded(c, "s");
            int lines = logLines(root, "s");
            JsonNode p = preview(c, "s", "{\"budget\":500}");
            assertTrue(p.get("needsApproval").asBoolean(), p.toString());
            assertTrue(p.get("addsNodes").isNull(), "nothing is read before approval: " + p);
            assertEquals("[\"budget 500 > 100\"]", p.get("exceeded").toString());
            assertEquals(lines, logLines(root, "s"), "nothing was appended");
            JsonNode log = data(send(c, "GET", "/inv/investigations/s/log", null, null), 200);
            assertTrue(log.path("pending").isMissingNode() || log.path("pending").isNull() || log.path("pending").isEmpty(),
                    "no pending request was opened: " + log.path("pending"));
            assertFalse(preview(c, "s", "{\"budget\":50}").get("needsApproval").asBoolean(), "below the threshold it previews");
        }
    }

    /** Armed: no credential 401, a Subject without canManageIncidents 403, a non-member 404, the lead 200. */
    @Test
    void thePreviewIsGatedAsTheAppend(@TempDir Path cfg, @TempDir Path root) throws Exception {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer owner" -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case "Bearer other" -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            case "Bearer plain" -> Optional.of(new Subject("analyst-1", Set.of()));
            default -> Optional.empty();
        });
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            assertEquals(200, send(c, "POST", "/inv/investigations", CREATE.formatted("g"), "Bearer owner").statusCode());
            assertEquals(200, send(c, "POST", "/inv/investigations/g/ops", "{\"op\":\"seed\",\"ids\":[\"A1\"]}", "Bearer owner").statusCode());
            String path = "/inv/investigations/g/expand/preview";
            assertEquals(401, send(c, "POST", path, "{}", null).statusCode());
            assertEquals(403, send(c, "POST", path, "{}", "Bearer plain").statusCode());
            assertEquals(404, send(c, "POST", path, "{}", "Bearer other").statusCode(), "a non-member reads absence");
            assertEquals(200, send(c, "POST", path, "{}", "Bearer owner").statusCode());
        }
    }

    // ── Remove held super nodes = an exclude with a reason ────────────────────────────────────────────

    @Test
    void anExcludeSealsTheBusyNumberReasonAndReplaysIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            seeded(c, "r");
            op(c, "r", "{\"op\":\"expand\",\"hubThreshold\":3}");
            JsonNode removed = op(c, "r", "{\"op\":\"exclude\",\"ids\":[\"HUB9\"],\"reason\":\"busy number above 3 contacts\"}");
            assertEquals("[\"HUB9\"]", removed.at("/delta/excluded").toString());
            assertTrue(Files.readString(root.resolve("audit/snapshots/investigations/r/log.jsonl"))
                    .contains("\"reason\":\"busy number above 3 contacts\""), "the reason is sealed in the op");
            JsonNode replay = data(post(c, "/inv/investigations/r/replay", "{}"), 200);
            assertTrue(replay.get("equivalent").asBoolean(), replay.toString());
            assertEquals("busy number above 3 contacts", replay.at("/workingSet/excluded/0/reason").asText());
            assertEquals(422, post(c, "/inv/investigations/r/ops", "{\"op\":\"exclude\",\"ids\":[\"B2\"]}").statusCode(),
                    "an exclude without a reason is refused, as before");
        }
    }

    // ── the explorer preview (Basic) ───────────────────────────────────────────────────────────────────

    private JsonNode neighbours(Ctx c, String body) throws Exception {
        return data(post(c, "/inv/projection/neighbors/preview", "{" + BINDING + "," + body.substring(1)), 200);
    }

    @Test
    void basicServesTheNeighboursPreviewAndRefusesTheInvestigationOne(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic: no module declares the investigations feature
        try (Ctx c = open(cfg, root, "masking_mode: none\n")) {
            callCentres(c);
            JsonNode a = neighbours(c, "{\"ids\":[\"A1\"],\"hubThreshold\":3}");
            assertEquals(2, a.get("addsNodes").asInt(), a.toString());
            assertEquals(2, a.get("addsLinks").asInt());
            assertEquals(1, a.get("heldCount").asInt());
            assertEquals("HUB9", a.at("/held/0/id").asText());
            assertEquals(6, a.at("/held/0/contacts").asInt());
            assertEquals("flat", a.get("readPath").asText());
            assertFalse(a.get("truncated").asBoolean());

            JsonNode hub = neighbours(c, "{\"ids\":[\"HUB9\"],\"excludeBy\":[\"cc\"]}");
            assertEquals(3, hub.get("addsNodes").asInt(), "A1, X4, X5 - X1..X3 are on the list: " + hub);
            assertEquals(3, hub.get("addsLinks").asInt());
            assertEquals(5, hub.at("/excludedByLists/0/removed").asInt(), "3 rounds to 5");
            assertEquals("Call centres", hub.at("/excludedByLists/0/title").asText());
            assertEquals(5, neighbours(c, "{\"ids\":[\"HUB9\"],\"direction\":\"out\"}").get("addsNodes").asInt());
            assertEquals(1, neighbours(c, "{\"ids\":[\"HUB9\"],\"direction\":\"in\"}").get("addsNodes").asInt());
            assertEquals(1, neighbours(c, "{\"ids\":[\"B2\"],\"linkKinds\":[\"sms\"]}").get("addsNodes").asInt(), "B2-C3 only");
            JsonNode two = neighbours(c, "{\"ids\":[\"A1\",\"B2\"],\"direction\":\"out\"}");
            assertEquals(2, two.get("addsNodes").asInt(), "A1-B2 joins two frontier entities, so only HUB9 and C3 are new: " + two);
            assertEquals(2, two.get("addsLinks").asInt(), two.toString());

            assertEquals(a.toString(), neighbours(c, "{\"value\":\"A1\",\"hubThreshold\":3}").toString(),
                    "the neighbours read's one-entity body (value) is ids: [value]");
            assertEquals(422, post(c, "/inv/projection/neighbors/preview", "{" + BINDING + "}").statusCode(), "ids are required");
            assertEquals(404, post(c, "/inv/projection/neighbors/preview", "{" + BINDING + ",\"ids\":[\"A1\"],\"excludeBy\":[\"nope\"]}")
                    .statusCode(), "an unknown list");

            HttpResponse<String> inv = post(c, "/inv/investigations/x/expand/preview", "{}");
            assertEquals(503, inv.statusCode(), inv.body());
            assertEquals(ErrorCodes.CAPABILITY_UNAVAILABLE, JSON.readTree(inv.body()).at("/error/errorCode").asText(), inv.body());
        }
    }

    @Test
    void theNeighboursPreviewMasksHeldIdsAsTheExplorerDoes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, "masking_mode: all\n")) {
            HttpResponse<String> raw = post(c, "/inv/projection/neighbors/preview", "{" + BINDING + ",\"ids\":[\"A1\"],\"hubThreshold\":3}");
            JsonNode a = data(raw, 200);
            assertEquals(1, a.get("heldCount").asInt(), raw.body());
            assertFalse(raw.body().contains("HUB9"), raw.body());
            assertTrue(a.get("masking").isObject(), raw.body());
        }
    }
}
