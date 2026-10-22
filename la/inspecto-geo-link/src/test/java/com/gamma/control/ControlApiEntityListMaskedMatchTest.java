package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.entitystore.MaskTokens;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entity List membership of a MASKED node over real HTTP (Entity profile plan §2, IMS rehearsal finding 1). Under
 * {@code typed} masking the canvas holds the exploration alias of an {@code msisdn}; {@code POST /entity-lists/{id}/match}
 * must match that alias, and the alias the list answers with must be that same alias - else the node dialog says
 * "on none of N lists" for a listed Entity. No response to an alias may carry the raw id.
 */
class ControlApiEntityListMaskedMatchTest {

    /**
     * The masking default is on where people sign in (LA-ONBOARD-1 OB-6, A-16) - not where Investigations are present. No test
     * classpath sets auth.mode, so these tests, which rely on the {@code typed} default, state that people sign in.
     */
    @org.junit.jupiter.api.BeforeEach
    void peopleSignIn() {
        System.setProperty("auth.mode", "oidc");
    }

    @org.junit.jupiter.api.AfterEach
    void clearSignIn() {
        System.clearProperty("auth.mode");
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String A = "27820000001", B = "27820000002", C = "27820000003";
    private static final String ROWS = String.join(",",
            "('" + A + "','" + B + "','voice')",
            "('" + A + "','" + C + "','sms')");
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
                    "SELECT caller, callee, channel FROM (VALUES " + ROWS + ") AS t(caller,callee,channel)",
                    "2026-10-15T00:00:00Z"));
            // the caller column is an MSISDN: under typed masking (the default) every id of the read is masked
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds", Map.of("view", "calls_view",
                    "columns", List.of(Map.of("name", "caller", "classification", "MSISDN"))));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private HttpResponse<String> match(Ctx c, String list, String... values) throws Exception {
        return post(c, "/entity-lists/" + list + "/match", JSON.writeValueAsString(Map.of("values", List.of(values))));
    }

    private void list(Ctx c, String id, String type, String member) throws Exception {
        data(post(c, "/entity-lists", "{\"id\":\"" + id + "\",\"title\":\"" + id + "\",\"purpose\":\"watch\","
                + "\"entityType\":\"" + type + "\",\"reason\":\"r\"}"), 201);
        data(post(c, "/entity-lists/" + id + "/members", "{\"add\":[\"" + member + "\"],\"reason\":\"r\"}"), 200);
    }

    @Test
    void aMaskedNodesExplorationAliasMatchesItsListAndTheListAnswersWithThatAlias(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            list(c, "wl", "msisdn", A);            // msisdn: masked, e164
            list(c, "hs", "handset", "Nokia 3310"); // handset: not masked

            HttpResponse<String> proj = post(c, "/inv/projection",
                    "{\"dataset\":\"calls_ds\",\"sourceCol\":\"caller\",\"targetCol\":\"callee\"}");
            JsonNode p = data(proj, 200);
            assertTrue(p.at("/masking/masked").asBoolean(), p.toString());
            byte[] key = MaskTokens.spaceKey(root);
            String aliasA = MaskTokens.token(key, A), aliasB = MaskTokens.token(key, B);
            assertTrue(proj.body().contains(aliasA) && proj.body().contains(aliasB), "the canvas holds these aliases: " + proj.body());
            assertFalse(proj.body().contains(A), proj.body());

            // the raw value matches, and the list names it by the canvas's alias
            JsonNode raw = data(match(c, "wl", A), 200).get("matches").get(0);
            assertTrue(raw.get("matched").asBoolean(), raw.toString());
            assertEquals("key", raw.get("match").asText());
            assertEquals(aliasA, raw.get("entry").asText(), "the list's alias must be the exploration alias");

            // the exploration alias matches; a non-member's alias does not; neither answer carries a raw id
            HttpResponse<String> byAlias = match(c, "wl", aliasA, aliasB);
            JsonNode m = data(byAlias, 200).get("matches");
            assertTrue(m.get(0).get("matched").asBoolean(), "a member's exploration alias matches: " + m);
            assertEquals("key", m.get(0).get("match").asText());
            assertEquals(aliasA, m.get(0).get("entry").asText());
            assertFalse(m.get(1).get("matched").asBoolean(), "a non-member's alias does not match: " + m);
            for (String id : List.of(A, B, C)) assertFalse(byAlias.body().contains(id), "raw id leaked: " + byAlias.body());

            // an unmasked type: the raw value matches and shows raw; its alias (a masked read aliases every id) matches
            // and is answered with that alias, never the raw key
            JsonNode plain = data(match(c, "hs", "nokia 3310 "), 200).get("matches").get(0);
            assertTrue(plain.get("matched").asBoolean(), plain.toString());
            assertEquals("nokia 3310", plain.get("entry").asText());
            String aliasH = MaskTokens.token(key, "nokia 3310");
            HttpResponse<String> hAlias = match(c, "hs", aliasH);
            JsonNode h = data(hAlias, 200).get("matches").get(0);
            assertTrue(h.get("matched").asBoolean(), h.toString());
            assertEquals(aliasH, h.get("entry").asText());
            assertFalse(hAlias.body().toLowerCase().contains("nokia"), hAlias.body());
        }
    }
}
