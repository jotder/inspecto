package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.metrics.MetricRegistry;
import com.gamma.screening.ScreeningLists;
import com.gamma.service.SpaceManager;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCREENING-1 over real HTTP: {@code POST /screening/check}, {@code GET /screening/hits[/{id}]} and
 * {@code POST /screening/hits/{id}/decide}, against real Spaces on disk with a Subject per caller — a route with no
 * Subject makes {@code withCapability} a no-op, so the 403s prove the gate is armed.
 *
 * <p>Callers: {@code operations} holds canWorkIncidents; {@code pipeline-developer} does not; {@code none} holds nothing.
 */
class ControlApiScreeningTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OPS = "Bearer ops", OPS2 = "Bearer ops2", DEV = "Bearer dev", NONE = "Bearer none";
    private final HttpClient client = HttpClient.newHttpClient();
    private String inheritedWriteRoot;

    @BeforeEach
    void arm() {
        inheritedWriteRoot = System.getProperty("assist.write.root");
        System.clearProperty("assist.write.root");
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            if (NONE.equals(h)) return Optional.of(new Subject("nobody", Set.of()));
            String[] who = switch (h) {
                case OPS -> new String[] {"ops-1", "operations"};
                case OPS2 -> new String[] {"ops-2", "operations"};
                case DEV -> new String[] {"dev-1", "pipeline-developer"};
                default -> null;
            };
            if (who == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(who[1]);
            ComponentAccess.heldRoles(ex, Set.of(who[1]));
            return Optional.of(new Subject(who[0], def.capabilities(), def.dataScopes()));
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        if (inheritedWriteRoot != null) System.setProperty("assist.write.root", inheritedWriteRoot);
    }

    /** A Space whose config root holds an unmasked {@code sanctions} list. */
    private static Path space(Path spaces, String id) throws Exception {
        Path cfg = spaces.resolve(id).resolve("config");
        Files.createDirectories(cfg.resolve("registry"));
        Files.createDirectories(spaces.resolve(id).resolve("data"));
        ScreeningLists.unmasked(cfg);
        ScreeningLists.create(cfg, "sanctions", "block", "subscriber", "default");
        ScreeningLists.add(cfg, "sanctions", "default", List.of("Vladimir Putin", "Osama bin Laden"));
        return cfg;
    }

    private HttpResponse<String> send(int port, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private interface Body {
        void run(int port) throws Exception;
    }

    private void withSpaces(Path spaces, Body body) throws Exception {
        SpaceManager manager = SpaceManager.discover(spaces);
        ControlApi api = new ControlApi(manager, 0);
        api.start();
        try {
            body.run(api.port());
        } finally {
            api.close();
            manager.close();
            MetricRegistry.global().reset();
        }
    }

    private static String decide(String decision, int version) {
        return "{\"decision\":\"" + decision + "\",\"reason\":\"checked the date of birth\",\"version\":" + version + "}";
    }

    @Test
    void checkScoresSubjectsAgainstListsAndPersistsNothing(@TempDir Path spaces) throws Exception {
        Path cfg = space(spaces, "acme");
        withSpaces(spaces, port -> {
            String body = "{\"lists\":[\"sanctions\"],\"subjects\":[{\"key\":\"a\",\"name\":\"Putin, Vladimir\"},"
                    + "{\"key\":\"b\",\"name\":\"Vlаdimir Рutin\"},{\"key\":\"c\",\"name\":\"Jane Doe\"}]}";
            JsonNode out = data(send(port, "POST", "/spaces/acme/screening/check", body, DEV), 200);
            assertEquals(0.85, out.get("threshold").asDouble());
            JsonNode r = out.get("results");
            assertEquals(3, r.size());
            assertEquals("vladimir putin", r.at("/0/matches/0/entry").asText());
            assertEquals("name", r.at("/0/matches/0/method").asText());
            assertEquals("sanctions", r.at("/0/matches/0/listId").asText());
            assertEquals(1.0, r.at("/1/matches/0/score").asDouble(), "Cyrillic look-alike letters fold to Latin");
            assertEquals(0, r.at("/2/matches").size());
            assertFalse(Files.exists(cfg.resolve("screening-hits")), "check persists nothing");

            HttpResponse<String> unknown = send(port, "POST", "/spaces/acme/screening/check",
                    "{\"lists\":[\"nope\"],\"subjects\":[{\"name\":\"x\"}]}", DEV);
            assertEquals(422, unknown.statusCode(), unknown.body());
            assertTrue(unknown.body().contains("nope"), unknown.body());
            assertEquals(422, send(port, "POST", "/spaces/acme/screening/check",
                    "{\"lists\":[\"sanctions\"],\"subjects\":[{\"key\":\"x\"}]}", DEV).statusCode(), "no name, no identifier");
            assertEquals(422, send(port, "POST", "/spaces/acme/screening/check",
                    "{\"lists\":[\"sanctions\"],\"subjects\":[{\"name\":\"x\"}],\"threshold\":0.1}", DEV).statusCode());
        });
    }

    /** SCREENING-1 (9): a screening.run Job with params it can never run is refused at save, not at 05:00. */
    @Test
    void aScreeningJobWithBadParamsIsRefusedAtSave(@TempDir Path spaces) throws Exception {
        Path cfg = space(spaces, "acme");
        withSpaces(spaces, port -> {
            String base = "\"name\":\"s1\",\"type\":\"screening.run\",\"dataset\":\"ghost\",\"keyField\":\"id\","
                    + "\"nameField\":\"n\",";
            HttpResponse<String> bad = send(port, "POST", "/spaces/acme/jobs", "{" + base
                    + "\"lists\":\"sanctions\",\"threshold\":\"abc\",\"enabled\":false}", DEV);
            assertEquals(422, bad.statusCode(), bad.body());
            assertTrue(bad.body().contains("threshold"), bad.body());
            assertFalse(Files.exists(cfg.resolve("jobs").resolve("s1_job.toon")), "nothing was written");

            HttpResponse<String> noDataset = send(port, "POST", "/spaces/acme/jobs", "{" + base
                    + "\"lists\":\"sanctions\",\"enabled\":true}", DEV);
            assertEquals(422, noDataset.statusCode(), noDataset.body());
            assertTrue(noDataset.body().contains("unknown dataset 'ghost'"), noDataset.body());

            HttpResponse<String> noList = send(port, "POST", "/spaces/acme/jobs", "{" + base.replace("ghost", "x")
                    + "\"lists\":\"nope\",\"enabled\":true}", DEV);
            assertEquals(422, noList.statusCode(), noList.body());

            // a DRAFT may name a Dataset / list that does not exist yet: only its params' shape is judged
            HttpResponse<String> draft = send(port, "POST", "/spaces/acme/jobs", "{" + base
                    + "\"lists\":\"later\",\"enabled\":false}", DEV);
            assertTrue(draft.statusCode() < 300, draft.body());
            assertTrue(Files.exists(cfg.resolve("jobs").resolve("s1_job.toon")));
        });
    }

    @Test
    void decideIsGatedOnCanWorkIncidents(@TempDir Path spaces) throws Exception {
        Path cfg = space(spaces, "acme");
        String id = ScreeningLists.seedHit(cfg, "c1", "Putin, Vladimir", "sanctions", "vladimir putin", 0.98);
        withSpaces(spaces, port -> {
            String path = "/spaces/acme/screening/hits/" + id + "/decide";
            assertEquals(401, send(port, "POST", path, decide("dismiss", 1), null).statusCode(), "no credential");
            assertEquals(403, send(port, "POST", path, decide("dismiss", 1), NONE).statusCode(), "no capability");
            assertEquals(403, send(port, "POST", path, decide("dismiss", 1), DEV).statusCode(),
                    "a builder role does not work triage items");
            JsonNode hit = data(send(port, "POST", path, decide("dismiss", 1), OPS), 200);
            assertEquals("dismissed", hit.get("state").asText());
            assertEquals(2, hit.get("version").asInt());
            assertEquals("ops-1", hit.get("decidedBy").asText());
            assertEquals("checked the date of birth", hit.get("reason").asText());
            assertFalse(hit.has("mac"), hit.toString());
            assertEquals(404, send(port, "POST", "/spaces/acme/screening/hits/sh-20260101000000-abcdef/decide",
                    decide("dismiss", 1), OPS).statusCode(), "an unknown hit, past the gate");
        });
    }

    @Test
    void theReviewFlowEscalatesThenConfirmsAndRefusesStaleOrFinalDecisions(@TempDir Path spaces) throws Exception {
        Path cfg = space(spaces, "acme");
        String id = ScreeningLists.seedHit(cfg, "c1", "Putin, Vladimir", "sanctions", "vladimir putin", 0.98);
        String other = ScreeningLists.seedHit(cfg, "c2", "Osama bin Laden", "sanctions", "osama bin laden", 1.0);
        withSpaces(spaces, port -> {
            String path = "/spaces/acme/screening/hits/" + id + "/decide";
            assertEquals(2, data(send(port, "GET", "/spaces/acme/screening/hits", null, DEV), 200).get("hits").size());
            assertEquals(id, data(send(port, "GET", "/spaces/acme/screening/hits/" + id, null, DEV), 200).get("id").asText());

            data(send(port, "POST", path, decide("escalate", 1), OPS), 200);
            HttpResponse<String> stale = send(port, "POST", path, decide("confirm", 1), OPS);
            assertEquals(409, stale.statusCode(), "a decision on a version that moved on: " + stale.body());
            assertTrue(stale.body().contains("version 2"), stale.body());
            // four-eyes (SCREENING-1-D1): the escalator may not confirm; nothing changed, no Case was opened
            HttpResponse<String> self = send(port, "POST", path, decide("confirm", 2), OPS);
            assertEquals(409, self.statusCode(), self.body());
            assertTrue(self.body().contains("four-eyes"), self.body());
            JsonNode still = data(send(port, "GET", "/spaces/acme/screening/hits/" + id, null, DEV), 200);
            assertEquals("escalated", still.get("state").asText());
            assertFalse(still.has("caseId"));
            JsonNode confirmed = data(send(port, "POST", path, decide("confirm", 2), OPS2), 200);
            assertEquals("confirmed", confirmed.get("state").asText());
            assertEquals(3, confirmed.get("history").size(), "open, escalated, confirmed");
            // SCREENING-1-D2: the confirm opened a Case that CONTAINS an Incident for the subject, ids stamped on the hit
            String caseId = confirmed.get("caseId").asText(), incidentId = confirmed.get("incidentId").asText();
            JsonNode kase = data(send(port, "GET", "/spaces/acme/objects/" + caseId, null, OPS), 200);
            assertEquals("CASE", kase.get("objectType").asText(), kase.toString());
            assertTrue(kase.toString().contains(incidentId) || data(send(port, "GET", "/spaces/acme/objects/" + incidentId, null, OPS), 200)
                    .get("attributes").get("entityKey").asText().equals("c1"));
            assertFalse(kase.toString().contains("Putin"), "the screened name never reaches the Case");
            assertEquals(409, send(port, "POST", path, decide("dismiss", 3), OPS).statusCode(), "confirmed is final");

            JsonNode open = data(send(port, "GET", "/spaces/acme/screening/hits?state=open", null, DEV), 200).get("hits");
            assertEquals(1, open.size());
            assertEquals(other, open.at("/0/id").asText());
            assertEquals(1, data(send(port, "GET", "/spaces/acme/screening/hits?state=confirmed", null, DEV), 200)
                    .get("hits").size());
            assertEquals(422, send(port, "GET", "/spaces/acme/screening/hits?state=closed", null, DEV).statusCode());
            assertEquals(422, send(port, "POST", "/spaces/acme/screening/hits/" + other + "/decide",
                    "{\"decision\":\"approve\",\"reason\":\"r\",\"version\":1}", OPS).statusCode(), "unknown decision");
            assertEquals(422, send(port, "POST", "/spaces/acme/screening/hits/" + other + "/decide",
                    "{\"decision\":\"dismiss\",\"version\":1}", OPS).statusCode(), "a decision needs a reason");
            assertEquals(404, send(port, "GET", "/spaces/acme/screening/hits/sh-20260101000000-abcdef", null, DEV).statusCode());
            assertEquals(404, send(port, "GET", "/spaces/acme/screening/hits/..%2Froles", null, DEV).statusCode(),
                    "an id that is not a hit id never names a file");
        });
    }

    /** An open hit has no escalator, so one person may confirm it; the Case is still opened. */
    @Test
    void anOpenHitConfirmedByOnePersonOpensACase(@TempDir Path spaces) throws Exception {
        Path cfg = space(spaces, "acme");
        String id = ScreeningLists.seedHit(cfg, "c9", "Osama bin Laden", "sanctions", "osama bin laden", 1.0);
        withSpaces(spaces, port -> {
            JsonNode hit = data(send(port, "POST", "/spaces/acme/screening/hits/" + id + "/decide", decide("confirm", 1), OPS), 200);
            assertTrue(hit.get("caseId").asText().length() > 0);
            JsonNode dismissed = data(send(port, "POST", "/spaces/acme/screening/hits/" + ScreeningLists.seedHit(cfg, "c8",
                    "X Y", "sanctions", "x y", 0.9) + "/decide", decide("dismiss", 1), OPS), 200);
            assertFalse(dismissed.has("caseId"), "only a confirm opens a Case");
        });
    }

    @Test
    void aForgedHitIsShownInvalidAndCannotBeDecided(@TempDir Path spaces) throws Exception {
        Path cfg = space(spaces, "acme");
        String id = ScreeningLists.seedHit(cfg, "c1", "Putin, Vladimir", "sanctions", "vladimir putin", 0.98);
        Path f = ScreeningLists.hitFile(cfg, id);
        Files.writeString(f, Files.readString(f).replace("\"open\"", "\"escalated\""));
        withSpaces(spaces, port -> {
            JsonNode hit = data(send(port, "GET", "/spaces/acme/screening/hits/" + id, null, DEV), 200);
            assertEquals("invalid", hit.get("integrity").asText());
            HttpResponse<String> refused = send(port, "POST", "/spaces/acme/screening/hits/" + id + "/decide",
                    decide("dismiss", 1), OPS);
            assertEquals(409, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("integrity"), refused.body());
        });
    }
}
