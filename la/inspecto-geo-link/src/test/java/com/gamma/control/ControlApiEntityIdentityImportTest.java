package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-17 mapping-Dataset import cut — {@code POST /inv/entity-identities/import} over real HTTP with a REAL Subject on
 * every request (so {@code withCapability} is enforced): 401 · 403 · 503 · 422 · 404 (R3 view gate) · 403 (D-U7
 * four-eyes) · 201/200, provenance on every fact, idempotent re-import, a retraction that sticks, and the row cap.
 */
class ControlApiEntityIdentityImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IMPORT = "/inv/entity-identities/import";
    private static final String ANALYST = "Bearer analyst";
    private static final String VIEWER = "Bearer viewer";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case VIEWER -> Optional.of(new Subject("viewer-1", Set.of()));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void noSubjects() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").method("POST", BodyPublishers.ofString(body));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Ctx c, String path, String body) throws Exception {
        return send(c, path, body, ANALYST);
    }

    private HttpResponse<String> groups(Ctx c) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/inv/entity-identities"))
                .header("Authorization", ANALYST).GET().build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private static void status(int expected, HttpResponse<String> r, String why) {
        assertEquals(expected, r.statusCode(), why + ": " + r.body());
    }

    /** A SIM register: msisdn ↔ imsi, one duplicated pair (spelled two ways), one NULL, one empty-after-digits. */
    private static void seedRegister(Ctx c, String extraRows, Map<String, Object> datasetExtra) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("sims_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES "
                        + "('+44 7700 900001','234-10-1'),"
                        + "('0044 7700 900001','23410 1'),"    // the same pair after the sealed normalisers
                        + "('+447700900002','234102'),"
                        + "('+447700900003',NULL),"
                        + "('+447700900004','--')"            // empty after `digits`
                        + extraRows
                        + ") AS t(msisdn,imsi)",
                "2026-07-08T00:00:00Z"));
        Map<String, Object> ds = new java.util.LinkedHashMap<>(Map.of("view", "sims_view"));
        ds.putAll(datasetExtra);
        new ComponentStore(c.root.resolve("registry")).write("dataset", "sims", ds);
    }

    private static final Map<String, Object> CLASSIFIED = Map.of("columns", List.of(
            Map.of("name", "msisdn", "classification", "MSISDN"), Map.of("name", "imsi", "classification", "IMSI")));

    private static String body(String extra) {
        return "{\"dataset\":\"sims\",\"aCol\":\"msisdn\",\"bCol\":\"imsi\",\"reason\":\"SIM register 2026-09\"" + extra + "}";
    }

    private static void unmasked(Ctx c) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\n");
    }

    private static List<JsonNode> facts(Ctx c) throws Exception {
        Path dir = c.root().resolve("audit/entity-facts");
        List<JsonNode> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().matches("\\d{12}\\.json")).sorted().toList())
                out.add(JSON.readTree(Files.readString(p)));
        }
        return out;
    }

    @Test
    void importIs503WithoutAWriteRootAndNeedsCanManageIncidents(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, null)) {
            status(503, post(c, IMPORT, body("")), "no write root");
        }
        try (Ctx c = open(cfg, root)) {
            seedRegister(c, "", CLASSIFIED);
            // literal paths: check-authgate-coverage reads path literals only
            status(403, send(c, "/inv/entity-identities/import", body(""), VIEWER), "import needs the capability");
            status(401, send(c, "/inv/entity-identities/import", body(""), null), "no credential");
            assertEquals(0, facts(c).size(), "a refused caller wrote nothing");
        }
    }

    @Test
    void importValidatesEveryFieldAgainstTheRealRelationAndWritesNothingOnRefusal(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedRegister(c, "", Map.of());   // no classification: types must be stated
            status(422, post(c, IMPORT, "{\"dataset\":\"sims\",\"aCol\":\"msisdn\",\"bCol\":\"imsi\",\"aType\":\"msisdn\","
                    + "\"bType\":\"imsi\"}"), "missing reason");
            status(422, post(c, IMPORT, "{\"aCol\":\"msisdn\",\"bCol\":\"imsi\",\"reason\":\"r\"}"), "missing dataset");
            status(422, post(c, IMPORT, body(",\"aCol\":\"msisdn; DROP\",\"aType\":\"msisdn\",\"bType\":\"imsi\"")),
                    "unsafe identifier");
            HttpResponse<String> noCol = post(c, IMPORT, body(",\"bCol\":\"iccid\",\"aType\":\"msisdn\",\"bType\":\"imsi\""));
            status(422, noCol, "a column the relation has not got");
            assertTrue(noCol.body().contains("iccid"), noCol.body());
            status(422, post(c, IMPORT, body(",\"bCol\":\"msisdn\",\"aType\":\"msisdn\",\"bType\":\"msisdn\"")), "same column twice");
            status(422, post(c, IMPORT, body("")), "untyped columns and no stated type");
            status(422, post(c, IMPORT, body(",\"aType\":\"msisdn\",\"bType\":\"vehicle\"")), "type not in force");
            status(422, post(c, IMPORT, body(",\"aType\":\"msisdn\",\"bType\":\"imsi\",\"limit\":0")), "limit < 1");
            status(404, post(c, IMPORT, body(",\"dataset\":\"nope\",\"aType\":\"msisdn\",\"bType\":\"imsi\"")), "unknown Dataset");
            // A classified column's type wins; stating a different one is a contradiction, not an override.
            seedRegister(c, "", CLASSIFIED);
            status(422, post(c, IMPORT, body(",\"bType\":\"handset\"")), "a stated type contradicting the classification");
            assertEquals(0, facts(c).size(), "no refusal wrote a fact");
        }
    }

    /** R3: a Dataset the caller cannot view is the SAME 404 as an absent one, and nothing is read or written. */
    @Test
    void aDatasetTheCallerCannotViewIsA404AndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            seedRegister(c, "", Map.of("columns", CLASSIFIED.get("columns"), "owner", "someone-else", "shares", List.of()));
            HttpResponse<String> r = post(c, IMPORT, body(""));
            status(404, r, "shared-away reads as absent");
            assertTrue(r.body().contains("no dataset 'sims'"), r.body());
            assertEquals(0, facts(c).size());
        }
    }

    @Test
    void importNormalisesWithTheSealedRuleAndRecordsProvenanceOnEveryFact(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            unmasked(c);
            seedRegister(c, ",('+447700900002','234103')", CLASSIFIED);   // 900002 has two IMSIs → one group of 3
            JsonNode out = data(post(c, IMPORT, body("")), 201);
            assertEquals(3, out.get("imported").asInt(), out.toString());
            // plan §5.10: the NULL row IS read and counted - rowsRead and skipped account for every row
            assertEquals(6, out.get("rowsRead").asInt(), "the NULL row is read too: " + out);
            assertEquals(1, out.at("/skipped/duplicate").asInt(), "two spellings of one pair: " + out);
            assertEquals(2, out.at("/skipped/empty").asInt(), "the NULL and '--' (empty under digits): " + out);
            assertEquals(0, out.at("/skipped/alreadyAsserted").asInt());
            assertFalse(out.get("truncated").asBoolean());
            String fp = out.get("fingerprint").asText();
            assertTrue(fp.matches("[0-9a-f]{64}"), fp);
            assertEquals("dataset:sims@" + fp, out.get("via").asText());
            assertEquals("msisdn", out.at("/types/a").asText());
            assertEquals("imsi", out.at("/types/b").asText());
            assertEquals(1, out.get("fromSeq").asLong());
            assertEquals(3, out.get("atSeq").asLong());

            List<JsonNode> facts = facts(c);
            assertEquals(3, facts.size());
            JsonNode f = facts.get(0);
            assertEquals("identity.asserted", f.get("kind").asText());
            assertEquals("msisdn:+447700900001", f.get("a").asText(), "the e164 rule sealed in the fact");
            assertEquals("imsi:234101", f.get("b").asText(), "the digits rule sealed in the fact");
            assertEquals("dataset:sims@" + fp, f.get("via").asText());
            assertEquals("sims", f.at("/import/dataset").asText());
            assertEquals("msisdn", f.at("/import/aCol").asText());
            assertEquals("imsi", f.at("/import/bCol").asText());
            assertEquals(fp, f.at("/import/fingerprint").asText());
            assertEquals("SIM register 2026-09", f.get("reason").asText());
            assertEquals("analyst-1", f.get("actor").asText());

            JsonNode g = data(groups(c), 200).get("groups");
            assertEquals(2, g.size(), g.toString());
            List<Integer> sizes = new ArrayList<>();
            g.forEach(x -> sizes.add(x.get("members").size()));
            assertEquals(Set.of(2, 3), Set.copyOf(sizes), "900001 pairs with one IMSI, 900002 with two: " + g);
        }
    }

    /** Re-importing the same Dataset state adds nothing; a retracted pair stays retracted; only a NEW pair is added. */
    @Test
    void reImportIsIdempotentAndARetractionSticks(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            unmasked(c);
            seedRegister(c, "", CLASSIFIED);
            JsonNode first = data(post(c, IMPORT, body("")), 201);
            assertEquals(2, first.get("imported").asInt(), first.toString());
            JsonNode again = data(post(c, IMPORT, body("")), 200);
            assertEquals(0, again.get("imported").asInt(), again.toString());
            assertEquals(2, again.at("/skipped/alreadyAsserted").asInt());
            assertEquals(first.get("fingerprint").asText(), again.get("fingerprint").asText(), "same state, same fingerprint");
            assertEquals(2, facts(c).size(), "no duplicate facts");

            data(post(c, "/inv/entity-identities/1/retract", "{\"reason\":\"ported number\"}"), 200);   // seq 3
            JsonNode afterRetract = data(post(c, IMPORT, body("")), 200);
            assertEquals(0, afterRetract.get("imported").asInt(), "a re-import never undoes a human retraction: " + afterRetract);
            assertEquals(3, facts(c).size());

            seedRegister(c, ",('+447700900009','234109')", CLASSIFIED);
            JsonNode changed = data(post(c, IMPORT, body("")), 201);
            assertEquals(1, changed.get("imported").asInt(), changed.toString());
            assertNotEquals(first.get("fingerprint").asText(), changed.get("fingerprint").asText());
            assertEquals(4, facts(c).size());
            assertEquals("msisdn:+447700900009", facts(c).get(3).get("a").asText());
        }
    }

    @Test
    void theImportIsBoundedByTheRowCapAndSaysSo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            unmasked(c);
            seedRegister(c, "", CLASSIFIED);
            JsonNode out = data(post(c, IMPORT, body(",\"limit\":2")), 201);
            assertTrue(out.get("truncated").asBoolean(), out.toString());
            assertEquals(2, out.get("rowsRead").asInt());
            assertEquals(2, out.at("/fences/limit").asInt());
            assertTrue(out.at("/fences/timeoutMs").asInt() > 0, out.toString());
            assertEquals(2, facts(c).size(), "the two rows read, deterministically ordered, are the ones imported");
        }
    }

    /** D-U7: an import reads a Dataset with no Investigation to hold a request, so above the threshold it is REFUSED. */
    @Test
    void aReadAboveTheFourEyesThresholdIsRefusedAndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            Files.writeString(c.root().resolve("link-analysis.toon"), "masking_mode: none\nfour_eyes_budget_above: 100\n");
            seedRegister(c, "", CLASSIFIED);
            HttpResponse<String> r = post(c, IMPORT, body(",\"limit\":500"));
            status(403, r, "above four_eyes_budget_above");
            assertTrue(r.body().contains("four-eyes"), r.body());
            assertEquals(0, facts(c).size());
            data(post(c, IMPORT, body(",\"limit\":100")), 201);
        }
    }
}
