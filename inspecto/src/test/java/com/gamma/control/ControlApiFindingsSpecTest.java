package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configurable Findings sections over real HTTP (C3 / BACKLOG D6): {@code GET /findings/{type}} resolves
 * the built-in default when nothing is authored and the authored {@code findings-spec} component when one
 * is, and the generic {@code /components} CRUD is the only authoring surface (no second config idiom).
 */
class ControlApiFindingsSpecTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir, Path writeRoot) throws Exception {
        if (writeRoot != null) {
            Files.createDirectories(writeRoot);
            System.setProperty("assist.write.root", writeRoot.toString());
        } else {
            System.clearProperty("assist.write.root");
        }
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
        ControlApi api = new ControlApi(svc, 0);
        api.start();
        return new Ctx(svc, api, api.port());
    }

    @AfterEach
    void clearWriteRoot() {
        System.clearProperty("assist.write.root");
    }

    /** No write root at all — the read still answers with the built-in shape rather than 503/500. */
    @Test
    void servesTheBuiltInDefaultWhenNothingIsAuthored(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            JsonNode spec = json(send(c.port, "GET", "/findings/CASE", null));
            assertEquals("case", spec.get("objectType").asText());
            assertEquals(List.of("disposition", "impactAmount", "recordsAffected", "summary"),
                    keys(spec));
            assertEquals(5, spec.get("sections").get(0).get("options").size());
            assertEquals(400, send(c.port, "GET", "/findings/bogus", null).statusCode());
        }
    }

    @Test
    void anAuthoredSpecReplacesTheDefaultForItsTypeOnly(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            // Authoring goes through the generic component CRUD — D6 adds no configuration endpoint.
            assertEquals(200, send(c.port, "POST", "/components/findings-spec",
                    """
                    {"name":"case","objectType":"case","sections":[
                      {"key":"outcome","label":"Outcome","type":"select","tier":"required",
                       "options":[{"value":"WIN","label":"Win"},{"value":"LOSS","label":"Loss"}]},
                      {"key":"note","label":"Note","type":"multiline","tier":"advanced",
                       "dependsOn":{"key":"outcome","equals":"LOSS"}}]}
                    """).statusCode());

            JsonNode authored = json(send(c.port, "GET", "/findings/case", null));
            assertEquals(List.of("outcome", "note"), keys(authored), "fully replaces, never merges");
            assertEquals("Loss", authored.get("sections").get(0).get("options").get(1).get("label").asText());
            assertEquals("LOSS", authored.get("sections").get(1).get("dependsOn").get("equals").asText());

            // A sibling type is untouched — one spec per ObjectType.
            assertEquals(List.of("disposition", "impactAmount", "recordsAffected", "summary"),
                    keys(json(send(c.port, "GET", "/findings/INCIDENT", null))));
        }
    }

    /** Validation is fail-closed at authoring time, so a bad spec never reaches the panel. */
    @Test
    void aMalformedSpecIsRejectedAtAuthoringTime(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            assertEquals(422, send(c.port, "POST", "/components/findings-spec",
                    "{\"name\":\"case\",\"objectType\":\"case\",\"sections\":[]}").statusCode(),
                    "an empty section list");
            assertEquals(422, send(c.port, "POST", "/components/findings-spec",
                    "{\"name\":\"case\",\"objectType\":\"case\",\"sections\":"
                            + "[{\"key\":\"a\",\"type\":\"select\"}]}").statusCode(),
                    "a select with no options");
            assertEquals(422, send(c.port, "POST", "/components/findings-spec",
                    "{\"name\":\"case\",\"objectType\":\"case\",\"sections\":"
                            + "[{\"key\":\"a\",\"teir\":\"required\"}]}").statusCode(),
                    "an unknown section key is rejected, not ignored");
            assertEquals(422, send(c.port, "POST", "/components/findings-spec",
                    "{\"name\":\"case\",\"objectType\":\"case\",\"sections\":"
                            + "[{\"key\":\"a\",\"dependsOn\":{\"key\":\"ghost\",\"equals\":1}}]}").statusCode(),
                    "a dependsOn naming no sibling");
        }
    }

    /**
     * A spec hand-edited into an unreadable state on disk degrades to the default rather than 500 — a
     * broken config file must not take the triage panel down.
     */
    @Test
    void anUnreadableSpecOnDiskFallsBackToTheDefault(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            Path specs = writeRoot.resolve("registry").resolve("findings-specs");
            Files.createDirectories(specs);
            // Valid TOON, invalid spec: a select with no options would have been a 422 through the route.
            Files.writeString(specs.resolve("case.toon"),
                    "name = case\nobjectType = case\nsections [ { key = a, type = select } ]\n",
                    StandardCharsets.UTF_8);

            HttpResponse<String> r = send(c.port, "GET", "/findings/case", null);
            assertEquals(200, r.statusCode());
            assertEquals(List.of("disposition", "impactAmount", "recordsAffected", "summary"), keys(json(r)));
        }
    }

    /**
     * The spec judges submitted <em>values</em>, not just the form (D6 residual): a direct PATCH can no
     * longer store a disposition the ladder does not offer, while the shared attributes bag keeps working
     * for every non-Findings key.
     */
    @Test
    void aSubmittedFindingsValueIsJudgedAgainstTheEffectiveSpec(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            var seed = c.svc.objects().open(com.gamma.ops.ObjectType.INCIDENT, "bad rows", "d", "HIGH",
                    null, null, null, "corr", java.util.Map.of());
            String path = "/objects/" + seed.id();

            assertEquals(422, send(c.port, "PATCH", path, "{\"attributes\":{\"disposition\":\"MAYBE\"}}").statusCode(),
                    "a value outside the ladder the form offers");
            assertEquals(200, send(c.port, "PATCH", path, "{\"attributes\":{\"disposition\":\"CONFIRMED\"}}").statusCode());
            assertEquals(200, send(c.port, "PATCH", path, "{\"attributes\":{\"tags\":\"urgent\"}}").statusCode(),
                    "an undeclared key is a non-Findings attribute, never a rejection");
        }
    }

    /** A {@code required} section is judged against the merged result, and only once the patch touches
     *  the form — an unrelated attribute write must not start failing on an incomplete triage. */
    @Test
    void aRequiredSectionIsEnforcedOnlyOnceThePatchTouchesTheForm(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            assertEquals(200, send(c.port, "POST", "/components/findings-spec",
                    """
                    {"name":"incident","objectType":"incident","sections":[
                      {"key":"outcome","label":"Outcome","type":"select","tier":"required","required":true,
                       "options":[{"value":"WIN"},{"value":"LOSS"}]},
                      {"key":"loss","label":"Loss","type":"number","tier":"optional","min":0,
                       "dependsOn":{"key":"outcome","equals":"LOSS"}}]}
                    """).statusCode());
            var seed = c.svc.objects().open(com.gamma.ops.ObjectType.INCIDENT, "bad rows", "d", "HIGH",
                    null, null, null, "corr", java.util.Map.of());
            String path = "/objects/" + seed.id();

            assertEquals(200, send(c.port, "PATCH", path, "{\"attributes\":{\"tags\":\"urgent\"}}").statusCode(),
                    "no declared key submitted — the form is not judged");
            assertEquals(422, send(c.port, "PATCH", path, "{\"attributes\":{\"loss\":\"5\"}}").statusCode(),
                    "touching the form with 'outcome' still unset");
            assertEquals(200, send(c.port, "PATCH", path, "{\"attributes\":{\"outcome\":\"LOSS\"}}").statusCode());
            assertEquals(422, send(c.port, "PATCH", path, "{\"attributes\":{\"loss\":\"-1\"}}").statusCode(),
                    "min is enforced on a number");
            assertEquals(422, send(c.port, "PATCH", path, "{\"attributes\":{\"loss\":\"lots\"}}").statusCode(),
                    "a number section rejects a non-number");
            assertEquals(200, send(c.port, "PATCH", path, "{\"attributes\":{\"loss\":\"5\"}}").statusCode(),
                    "'outcome' is now stored, so the required check passes on the merged bag");
        }
    }

    private static List<String> keys(JsonNode spec) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode s : spec.get("sections")) out.add(s.get("key").asText());
        return out;
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return V1Body.of(r.body());
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }
}
