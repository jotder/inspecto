package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            assertEquals(List.of("disposition", "recordsAffected", "summary"),
                    keys(spec));
            assertEquals(7, spec.get("sections").get(0).get("options").size());
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
            assertEquals(List.of("disposition", "recordsAffected", "summary"),
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
            assertEquals(List.of("disposition", "recordsAffected", "summary"), keys(json(r)));
        }
    }

    /**
     * The spec judges submitted <em>values</em>, not just the form (D6 residual). Since D3 = (a) (operator
     * 2026-09-25) the values it judges are the ones the Findings panel actually writes — the
     * {@code attributes.findings} JSON blob — so a direct PATCH can no longer store a disposition the ladder
     * does not offer, while the shared attributes bag keeps working for every non-Findings key.
     */
    @Test
    void aSubmittedFindingsValueIsJudgedAgainstTheEffectiveSpec(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, null)) {
            var seed = TestOpsEngine.of(c.svc).open(com.gamma.objects.ObjectType.CASE, "bad rows", "d", "HIGH",
                    null, null, null, "corr", Map.of());
            String path = "/objects/" + seed.id();

            HttpResponse<String> bad = send(c.port, "PATCH", path, findings(Map.of("disposition", "MAYBE")));
            assertEquals(422, bad.statusCode(), "a value outside the ladder the form offers");
            assertTrue(bad.body().contains("disposition"), bad.body());
            assertEquals(200, send(c.port, "PATCH", path, findings(Map.of("disposition", "CONFIRMED"))).statusCode());
            assertEquals(200, send(c.port, "PATCH", path, attrs(Map.of("tags", "urgent"))).statusCode(),
                    "an undeclared key is a non-Findings attribute, never a rejection");
            assertEquals(200, send(c.port, "PATCH", path, attrs(Map.of("summary", "MAYBE"))).statusCode(),
                    "a TOP-LEVEL key is not where Findings live (D3): it is an ordinary attribute");
            // ... except the two with their own validated writes (WS-10): a top-level `disposition` is refused
            assertEquals(422, send(c.port, "PATCH", path, attrs(Map.of("disposition", "MAYBE"))).statusCode());
        }
    }

    /**
     * 🔴 S0 of the authoring-UI design (§2.4): the exact body the Findings panel sends — the blob plus the
     * flat {@code impactAmount}/{@code recordsAffected} copies as {@code ''} — against a spec holding an
     * authored {@code required} section. Before D3 the flat copies made the patch "touch" the form, the
     * required section was then looked up in the TOP-LEVEL bag where it never lives, and a correctly filled
     * form was refused 422.
     */
    @Test
    void theFindingsPanelsOwnSaveIsAcceptedWhenARequiredFieldIsFilled(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            assertEquals(200, send(c.port, "POST", "/components/findings-spec", ROOT_CAUSE_SPEC).statusCode());
            var seed = TestOpsEngine.of(c.svc).open(com.gamma.objects.ObjectType.CASE, "fraud ring", "d", "HIGH",
                    null, null, null, "corr", Map.of());
            String path = "/objects/" + seed.id();

            HttpResponse<String> filled = send(c.port, "PATCH", path, panelSave("CARD_NOT_PRESENT"));
            assertEquals(200, filled.statusCode(), filled.body());

            HttpResponse<String> empty = send(c.port, "PATCH", path, panelSave(""));
            assertEquals(422, empty.statusCode(), "the required field left blank in the blob");
            assertTrue(empty.body().contains("rootCause"), empty.body());

            HttpResponse<String> offLadder = send(c.port, "PATCH", path, panelSave("ALIENS"));
            assertEquals(422, offLadder.statusCode(), "a blob value is type-checked, not just its presence");
            assertTrue(offLadder.body().contains("rootCause"), offLadder.body());
        }
    }

    /** A {@code required} section is judged against the blob as it will be stored, a section hidden by its
     *  {@code dependsOn} is skipped, and a patch that carries no blob never judges the form. */
    @Test
    void aRequiredSectionIsEnforcedOnlyOnceThePatchCarriesTheForm(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            assertEquals(200, send(c.port, "POST", "/components/findings-spec",
                    """
                    {"name":"incident","objectType":"incident","sections":[
                      {"key":"outcome","label":"Outcome","type":"select","tier":"required","required":true,
                       "options":[{"value":"WIN"},{"value":"LOSS"}]},
                      {"key":"loss","label":"Loss","type":"number","tier":"optional","min":0,"required":true,
                       "dependsOn":{"key":"outcome","equals":"LOSS"}}]}
                    """).statusCode());
            var seed = TestOpsEngine.of(c.svc).open(com.gamma.objects.ObjectType.INCIDENT, "bad rows", "d", "HIGH",
                    null, null, null, "corr", Map.of());
            String path = "/objects/" + seed.id();

            assertEquals(200, send(c.port, "PATCH", path, attrs(Map.of("tags", "urgent"))).statusCode(),
                    "no blob submitted — the form is not judged");
            assertEquals(422, send(c.port, "PATCH", path, findings(Map.of("loss", "5"))).statusCode(),
                    "the form with 'outcome' still unset");
            assertEquals(200, send(c.port, "PATCH", path, findings(Map.of("outcome", "WIN"))).statusCode(),
                    "'loss' is required but hidden while outcome is WIN");
            assertEquals(422, send(c.port, "PATCH", path, findings(Map.of("outcome", "LOSS"))).statusCode(),
                    "'loss' is now shown, and required");
            assertEquals(422, send(c.port, "PATCH", path, findings(Map.of("outcome", "LOSS", "loss", "-1"))).statusCode(),
                    "min is enforced on a number");
            assertEquals(422, send(c.port, "PATCH", path, findings(Map.of("outcome", "LOSS", "loss", "lots"))).statusCode(),
                    "a number section rejects a non-number");
            assertEquals(200, send(c.port, "PATCH", path, findings(Map.of("outcome", "LOSS", "loss", "5"))).statusCode());
        }
    }

    /**
     * D7: removing a choice keeps the values Cases already stored. The panel re-sends the whole blob on every
     * save, so a stored value the current spec no longer offers must not be re-judged while it is unchanged —
     * otherwise editing any OTHER field on that Case would be refused.
     */
    @Test
    void anUnchangedStoredValueIsNotReJudgedAfterTheSpecChanges(@TempDir Path dir) throws Exception {
        Path writeRoot = dir.resolve("cfg");
        try (Ctx c = open(dir, writeRoot)) {
            assertEquals(200, send(c.port, "POST", "/components/findings-spec", ROOT_CAUSE_SPEC).statusCode());
            var seed = TestOpsEngine.of(c.svc).open(com.gamma.objects.ObjectType.CASE, "fraud ring", "d", "HIGH",
                    null, null, null, "corr", Map.of());
            String path = "/objects/" + seed.id();
            assertEquals(200, send(c.port, "PATCH", path, findings(Map.of("rootCause", "ACCOUNT_TAKEOVER"))).statusCode());

            // The desk removes the ACCOUNT_TAKEOVER choice.
            String narrowed = ROOT_CAUSE_SPEC.replace(
                    ",{\"value\":\"ACCOUNT_TAKEOVER\",\"label\":\"Account takeover\"}", "");
            assertNotEquals(ROOT_CAUSE_SPEC, narrowed);
            assertEquals(200, send(c.port, "PUT", "/components/findings-spec/case", narrowed).statusCode());

            assertEquals(200, send(c.port, "PATCH", path,
                    findings(Map.of("rootCause", "ACCOUNT_TAKEOVER", "note", "x"))).statusCode(),
                    "the stored, now-unoffered value rides along unchanged");
            assertEquals(422, send(c.port, "PATCH", path, findings(Map.of("rootCause", "SKIMMING"))).statusCode(),
                    "a CHANGED value is still judged");
        }
    }

    private static final String ROOT_CAUSE_SPEC = """
            {"name":"case","objectType":"case","sections":[
              {"key":"rootCause","label":"Root cause category","type":"select","tier":"required","required":true,
               "options":[{"value":"CARD_NOT_PRESENT","label":"Card not present"},{"value":"ACCOUNT_TAKEOVER","label":"Account takeover"}]},
              {"key":"impactAmount","label":"Impact amount","type":"string","tier":"required","required":false},
              {"key":"recordsAffected","label":"Records affected","type":"string","tier":"required","required":false},
              {"key":"note","label":"Note","type":"multiline","tier":"optional"}]}
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A PATCH body carrying a plain attributes bag. */
    private static String attrs(Map<String, String> attributes) throws Exception {
        return JSON.writeValueAsString(Map.of("attributes", attributes));
    }

    /** A PATCH body carrying only the Findings blob, serialised the way the panel does. */
    private static String findings(Map<String, String> blob) throws Exception {
        return attrs(Map.of("findings", JSON.writeValueAsString(blob)));
    }

    /** The Findings panel's own save body ({@code postmortem-panel.component.ts saveFindings}). */
    private static String panelSave(String rootCause) throws Exception {
        Map<String, String> blob = new LinkedHashMap<>();
        blob.put("rootCause", rootCause);
        blob.put("impactAmount", "");
        blob.put("recordsAffected", "");
        Map<String, String> bag = new LinkedHashMap<>();
        bag.put("findings", JSON.writeValueAsString(blob));
        bag.put("impactAmount", "");
        bag.put("recordsAffected", "");
        bag.put("assignees", "");
        bag.put("targetDate", "");
        return attrs(bag);
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
