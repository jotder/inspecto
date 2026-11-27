package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.service.CollectorService;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AGENT-KIT-1 widening plan W6 group A over real HTTP (D-AK10, signed 2026-11-25: the authoring previews for every agent):
 * a person's agent and a machine agent each call the four previews and get the route's own 200, and a before-and-after
 * read of the Space shows nothing persisted; an agent's body over 256 KiB is 413 while the person's own token is not
 * capped; a draft naming a config file is refused before the file is read (the person's own token still reads it); and
 * the schema preview's EXPR rule reaches no file for anyone (its DuckDB is sealed).
 */
class ControlApiAgentPreviewsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** AGENT = dev-1 through agent client claude-code; DEV = the same person, own token; BOT = a machine agent. */
    private static final String AGENT = "Bearer agent", DEV = "Bearer dev", BOT = "Bearer bot", ADMIN = "Bearer admin";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    static Path auditDir;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path dir, String pipeline) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void arm() {
        System.setProperty("jobs.audit.dir", auditDir.resolve("jobs_audit").toString());
        Authenticators.forTest(ex -> {
            String h = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String role = switch (h) {
                case AGENT, DEV -> "pipeline-developer";
                case BOT, ADMIN -> "admin";
                default -> null;
            };
            if (role == null) return Optional.empty();
            Roles.Def def = Roles.effective(ex).get(role);
            ComponentAccess.heldRoles(ex, Set.of(role));
            return Optional.of(switch (h) {
                case BOT -> new Subject("client:ops-bot", def.capabilities(), null, Map.of(), null, null, "svc-ops", "ops-bot");
                case ADMIN -> new Subject("admin-1", def.capabilities());
                default -> new Subject("dev-1", def.capabilities(), def.dataScopes(), Map.of(), null, null, null,
                        AGENT.equals(h) ? "claude-code" : null);
            });
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
        LookupRoutes.clearWarm();
    }

    /** A Space whose one Pipeline has a keyed lookup over {@code refdata/dial.csv}, and admits the machine client. */
    private Ctx open(Path dir) throws Exception {
        Path ref = Files.createDirectories(dir.resolve("refdata")).resolve("dial.csv");
        Files.writeString(ref, "dial_digit_key,provider_desc\n249,Sudan\n24991,Operator A\n");
        Path toon = TestConfigs.csv(Files.createDirectories(dir.resolve("cfg")), PipelineConfigBatchTest.miniSchema()).write();
        Files.writeString(toon, Files.readString(toon).replace("active: true", "active: false") + """
                output_store: enriched_calls
                steps[1]:
                  - join:
                      reference: %s
                      key: dial_digit_key
                      match: longest_prefix
                      on: SERVED_MSISDN
                      columns: provider_desc
                """.formatted(ref.toString().replace('\\', '/')));
        Path root = Files.createDirectories(dir.resolve("root"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("admin-1", "dev-1"))));
            Roles.write(root, Map.of(), List.of(), List.of("ops-bot"));   // the Space admits the machine client
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), dir, svc.loadedPipelines().get(0).identity().pipelineName());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(Ctx c, String path, String body, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    /** {@link #post} with no declared length (chunked). */
    private HttpResponse<String> chunked(Ctx c, String path, String body, String auth) throws Exception {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth)
                .POST(BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(bytes))).build(),
                BodyHandlers.ofString());
    }

    /**
     * A raw request that declares {@code length} body bytes but sends only the JDK server's 64 KiB drain (which it reads
     * before it flushes an answer to an unread body); the status line of the answer.
     */
    private static String declaredOnly(Ctx c, String path, int length, String auth) throws Exception {
        try (java.net.Socket s = new java.net.Socket("localhost", c.port)) {
            s.setSoTimeout(10_000);
            s.getOutputStream().write(("POST /api/v1" + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + auth
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + length + "\r\n\r\n" + " ".repeat(65_536))
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(),
                    java.nio.charset.StandardCharsets.US_ASCII)).readLine();
        }
    }

    private HttpResponse<String> get(Ctx c, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", ADMIN).GET().build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** Every file under the Space's directory (config, write root, Reference data) with its size and modification time. */
    private static Map<String, String> files(Path dir) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                out.put(dir.relativize(p).toString(), Files.size(p) + "@" + Files.getLastModifiedTime(p).toMillis());
        }
        return out;
    }

    /**
     * The before-and-after read: the Space's Pending Changes and Pipelines, as an administrator reads them, then every file
     * under the Space (last, because the Pipelines read itself opens the Pipeline's commit log).
     */
    private String state(Ctx c) throws Exception {
        String reads = data(get(c, "/pending-changes")) + " | " + data(get(c, "/pipelines")).size();
        return reads + " | " + files(c.dir);
    }

    private static final String PARSING = """
            {"config":{"name":"p","dirs":{"poll":"in","database":"out"},"processing":{"threads":1%s},
               "parsing":{"frontend":"delimited","delimited":{"delimiter":",","has_header":true}}},
             "sample_text":"parcel_id,pieces,weight_kg\\nP-1,2,12.5\\nP-2,1,7.25\\n"}""";

    private static final String ROWS = """
            [{"parcel_id":"P-1","pieces":"2","weight_kg":"12.5","shipped_on":"2026-03-01"},
             {"parcel_id":"P-2","pieces":"1","weight_kg":"7.25","shipped_on":"2026-03-02"},
             {"parcel_id":"P-3","pieces":"3","weight_kg":"n/a","shipped_on":"2026-03-03"},
             {"parcel_id":"P-4","pieces":"x","weight_kg":"4.0","shipped_on":"2026-03-04"},
             {"parcel_id":"P-5","pieces":"1","weight_kg":"3.1","shipped_on":"03/05/2026"}]""";

    /** The developer golden task dev-12's sample, as parsed rows. */
    private static final String CLEAN_ROWS = """
            [{"parcel_id":"P-1","pieces":"2","weight_kg":"12.5","shipped_on":"2026-03-01"},
             {"parcel_id":"P-2","pieces":"1","weight_kg":"7.25","shipped_on":"2026-03-02"},
             {"parcel_id":"P-3","pieces":"3","weight_kg":"4.0","shipped_on":"2026-03-03"}]""";

    /** The developer golden task dev-13's draft over its sample: P-3, P-4 and P-5 fail a cast. */
    private static final String SCHEMA = """
            {"config":{"raw":{"fields":[{"name":"parcel_id","type":"VARCHAR"},{"name":"pieces","type":"BIGINT"},
               {"name":"weight_kg","type":"DOUBLE"},{"name":"shipped_on","type":"DATE"}]}},
             "sampleRows":%s}""".formatted(ROWS);

    @Test
    void aPersonsAgentAndAMachineGetEveryPreviewsOwnAnswerAndNothingIsPersisted(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String lookup = "{\"pipeline\":\"" + c.pipeline + "\",\"step\":\"join\",\"value\":\"249915555\"}";
            for (String who : List.of(AGENT, BOT)) {
                String before = state(c);

                JsonNode parsed = data(post(c, "/config/preview/parsing", PARSING.formatted(""), who));
                assertEquals(2, parsed.get("rowCount").asInt(), parsed.toString());

                JsonNode suggested = data(post(c, "/config/suggest/schema", "{\"sampleRows\":" + CLEAN_ROWS + "}", who));
                List<String> types = new java.util.ArrayList<>();
                suggested.get("fields").forEach(f -> types.add(f.get("name").asText() + ":" + f.get("type").asText()));
                assertEquals(List.of("parcel_id:VARCHAR", "pieces:BIGINT", "weight_kg:DOUBLE", "shipped_on:DATE"), types,
                        "dev-12's ground truth");

                JsonNode cast = data(post(c, "/config/preview/schema", SCHEMA, who));
                assertEquals(2, cast.get("okCount").asInt(), cast.toString());
                assertEquals(3, cast.get("rejectedCount").asInt(), "dev-13's ground truth: " + cast);

                JsonNode mapping = data(post(c, "/components/mapping/validate",
                        "{\"rules\":[{\"targetColumn\":\"kg\",\"sourceExpression\":\"weight_kg\",\"transformType\":\"DIRECT\"}]}", who));
                assertTrue(mapping.get("clean").asBoolean(), mapping.toString());

                // not a group-A preview but a data route (AGENT-LOOKUP-PREVIEW-1): this path Reference answers raw to a
                // person's agent and is refused to a machine (D-AK25; ControlApiAgentLookupTest proves the rest)
                assertEquals(AGENT.equals(who) ? 200 : 403, post(c, "/lookups/test", lookup, who).statusCode(),
                        who + ": the lookup test over a path Reference");

                assertEquals(before, state(c), who + ": nothing persisted");
            }
            JsonNode hit = data(post(c, "/lookups/test", lookup, DEV));   // the person's own token still answers
            assertTrue(hit.get("hit").asBoolean(), hit.toString());
            assertEquals("24991", hit.get("matchedKey").asText());
        }
    }

    @Test
    void anAgentsBodyOverTheCapIs413WhileThePersonsOwnTokenIsNotCapped(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            String big = "{\"rules\":[],\"pad\":\"" + "x".repeat(AgentGate.PREVIEW_MAX_BODY_BYTES) + "\"}";
            // ⚠ Chunked, so the server reads up to the cap + 1 and the few bytes left fit its drain: a declared 256 KiB body
            // refused unread races the client, which can lose the 413 to a reset (ControlApiDeliveryStatusTest, 2026-09-26).
            for (String route : AgentGate.AUTHORING_PREVIEWS) {
                String path = route.substring("POST ".length());
                assertEquals(413, chunked(c, path, big, AGENT).statusCode(), route);
                assertEquals(413, chunked(c, path, big, BOT).statusCode(), route);
            }
            // a declared length over the cap is refused before the body is read: the socket sends a quarter of it
            assertTrue(declaredOnly(c, "/config/preview/schema", AgentGate.PREVIEW_MAX_BODY_BYTES + 1, AGENT)
                    .startsWith("HTTP/1.1 413"));
            assertEquals(200, post(c, "/components/mapping/validate", big, DEV).statusCode(), "the cap is the agent's");
            // just under the cap still answers
            String fits = "{\"rules\":[],\"pad\":\"" + "x".repeat(AgentGate.PREVIEW_MAX_BODY_BYTES - 100) + "\"}";
            assertEquals(200, post(c, "/components/mapping/validate", fits, AGENT).statusCode());
        }
    }

    @Test
    void anAgentsDraftNamingAFileIsRefusedBeforeTheFileIsRead(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path schema = Files.writeString(dir.resolve("w6_schema.toon"), PipelineConfigBatchTest.miniSchema());
            String named = PARSING.formatted(",\"schema_file\":\"" + schema.toString().replace('\\', '/') + "\"");
            String missing = PARSING.formatted(",\"schema_file\":\"" + dir.resolve("absent.toon").toString().replace('\\', '/') + "\"");

            // the person's own token reads the file and parses with it; a missing one is "not found"
            HttpResponse<String> person = post(c, "/config/preview/parsing", named, DEV);
            assertEquals(200, person.statusCode(), "the probe reaches the read: " + person.body());
            HttpResponse<String> absent = post(c, "/config/preview/parsing", missing, DEV);
            assertTrue(absent.statusCode() == 422 && absent.body().contains("not found"), absent.body());

            for (String who : List.of(AGENT, BOT)) {
                for (String draft : List.of(named, missing)) {
                    HttpResponse<String> r = post(c, "/config/preview/parsing", draft, who);
                    assertEquals(422, r.statusCode(), r.body());
                    assertTrue(r.body().contains("names a file"), r.body());
                    assertFalse(r.body().contains("not found"), "refused before any read or probe: " + r.body());
                }
            }
        }
    }

    @Test
    void theSchemaPreviewsExprRuleReachesNoFileForAnyCaller(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            Path secret = Files.writeString(dir.resolve("secret.txt"), "W6-SENTINEL-not-for-a-preview");
            String body = """
                    {"config":{"raw":{"fields":[{"name":"id","type":"VARCHAR"}]},
                      "mapping":{"rules":[{"targetColumn":"leak",
                        "sourceExpression":"(SELECT content FROM read_text('%s'))","transformType":"EXPR"}]}},
                     "sampleRows":[{"id":"1"}]}""".formatted(secret.toString().replace('\\', '/'));
            for (String who : List.of(DEV, AGENT)) {
                HttpResponse<String> r = post(c, "/config/preview/schema", body, who);
                assertFalse(r.body().contains("W6-SENTINEL"), who + " read the file: " + r.body());
                assertEquals(422, r.statusCode(), r.body());
            }
        }
    }
}
