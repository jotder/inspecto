package com.gamma.regreporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.access.ComponentAccess;
import com.gamma.access.Roles;
import com.gamma.control.ApproverRoster;
import com.gamma.control.ControlApi;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.ops.ObjectService;
import com.gamma.ops.ObjectServiceAccess;
import com.gamma.ops.link.LinkRelationship;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.workflow.ObjectType;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Report Template's {@code rows} source over real HTTP with an armed Authenticator: the rows of a Dataset behind the
 * member Incidents' Alert Rule keys reach the filing (only the matched keys, in a stable order), a required row leaf
 * left blank refuses the draft (422), an absent rows Dataset is 404, a non-revealer never reads the rows back, and the
 * four-eyes path delivers the rows as rendered.
 */
class ControlApiRegulatoryReportRowsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHOR = "Bearer author", CHECKER = "Bearer checker";
    private final HttpClient client = HttpClient.newHttpClient();

    @TempDir
    Path tmp;

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }

        ObjectService objects() {
            return ((ObjectServiceAccess) svc.objects().orElseThrow()).service();
        }
    }

    /** AUTHOR = operations (canWorkIncidents, no reveal); CHECKER = admin (approver, reveals). */
    @BeforeEach
    void arm() {
        System.setProperty("assist.safety.roots", tmp.toString());
        Authenticators.forTest(ex -> {
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            String[] who = switch (auth) {
                case AUTHOR -> new String[] {"author-1", "operations"};
                case CHECKER -> new String[] {"checker-1", "admin"};
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
        System.clearProperty("assist.safety.roots");
    }

    private Ctx open() throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve("pipes"));
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        Path root = Files.createDirectories(tmp.resolve("config"));
        System.setProperty("assist.write.root", root.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            Files.writeString(root.resolve(ApproverRoster.FILE), dev.toonformat.jtoon.JToon.encode(
                    Map.of("users", List.of("author-1", "checker-1"))));
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), root);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int status) throws Exception {
        assertEquals(status, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** The {@code lines} Dataset (a view): two lines of K1, one of K2, one of K3 with no run id. */
    private static void seedDataset(Ctx c) throws Exception {
        new ViewStore(c.root.resolve("views")).write(new ViewDefinition("lines_view", "flow-x", List.of(),
                "SELECT * FROM (VALUES ('K1', 'L2', 'R-B', 'run-7'), ('K1', 'L1', 'R-A', 'run-7'),"
                        + " ('K2', 'L3', 'R-C', 'run-8'), ('K3', 'L4', 'R-D', '')) AS t(group_key, line, rate_id, run_id)",
                "2026-11-01T00:00:00Z"));
        new ComponentStore(c.root.resolve("registry")).write("dataset", "lines", Map.of("view", "lines_view"));
    }

    private static void seedTemplate(Ctx c, String dataset) throws Exception {
        Path dir = Files.createDirectories(c.root.resolve(ReportTemplate.SPACE_DIR));
        Files.writeString(dir.resolve("line-pack.toon"), """
                id: line-pack
                title: Line pack
                format: json
                delivery:
                  kind: file-drop
                  dir: regulatory-submissions/line-pack
                rows:
                  dataset: %s
                  matchOn: group_key
                fields[4]{name,source,required,maxLength}:
                  caseTitle,subject.title,true,400
                  line/id,rows.line,true,64
                  line/rateId,rows.rate_id,true,64
                  line/runId,rows.run_id,true,64
                """.formatted(dataset));
    }

    /** A Case holding two Incidents keyed {@code group_key=<key>} and one Incident with no key. */
    private static String seedCase(Ctx c, String key) {
        ObjectService o = c.objects();
        var objects = c.svc.objects().orElseThrow();
        String kase = objects.open(ObjectType.CASE, "Dispute " + key, "d", "error", "t", Map.of());
        String i1 = objects.open(ObjectType.INCIDENT, "Line a", "d", "error", "t", Map.of("key.group_key", key, "key.line", "a"));
        String i2 = objects.open(ObjectType.INCIDENT, "Line b", "d", "error", "t", Map.of("key.group_key", key, "key.line", "b"));
        String i3 = objects.open(ObjectType.INCIDENT, "Window closing", "d", "error", "t", Map.of());
        for (String i : List.of(i1, i2, i3)) o.link(kase, i, LinkRelationship.CONTAINS, "t");
        return kase;
    }

    private static String body(String kase) {
        return "{\"template\":\"line-pack\",\"caseId\":\"" + kase + "\",\"reason\":\"dispute\"}";
    }

    @Test
    void theRowsBehindTheMemberIncidentsKeysAreFiledAndDeliveredThroughFourEyes() throws Exception {
        try (Ctx c = open()) {
            seedDataset(c);
            seedTemplate(c, "lines");
            String kase = seedCase(c, "K1");
            JsonNode drafted = data(send(c, "POST", "/regulatory-reports", body(kase), AUTHOR), 200);
            String id = drafted.get("id").asText();
            assertFalse(drafted.has("content"), "a non-revealer never reads Dataset rows back: " + drafted);
            assertTrue(drafted.get("keyMasked").asBoolean());

            JsonNode read = data(send(c, "GET", "/regulatory-reports/" + id, null, CHECKER), 200);
            JsonNode lines = JSON.readTree(read.get("content").asText()).get("line");
            assertEquals(2, lines.size(), "only the K1 rows, never K2: " + lines);
            assertEquals("L1", lines.get(0).get("id").asText(), "a stable order (ORDER BY ALL)");
            assertEquals("R-A", lines.get(0).get("rateId").asText());
            assertEquals("run-7", lines.get(1).get("runId").asText());
            assertFalse(read.get("sourceChanged").asBoolean(), read.toString());

            assertEquals("pending", data(send(c, "POST", "/regulatory-reports/" + id + "/request-approval", "{}", AUTHOR), 200)
                    .get("status").asText());
            assertEquals(403, send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", AUTHOR).statusCode(),
                    "the maker cannot approve their own filing");
            JsonNode done = data(send(c, "POST", "/regulatory-reports/" + id + "/approve", "{}", CHECKER), 200);
            assertEquals("submitted", done.get("status").asText(), done.toString());
            String file = Files.readString(c.root.resolve("regulatory-submissions/line-pack").resolve(id + ".json"));
            assertTrue(file.contains("R-B") && !file.contains("R-C"), file);
        }
    }

    @Test
    void aRowChangedAfterTheDraftIsShownAsASourceChange() throws Exception {
        try (Ctx c = open()) {
            seedDataset(c);
            seedTemplate(c, "lines");
            String id = data(send(c, "POST", "/regulatory-reports", body(seedCase(c, "K1")), AUTHOR), 200).get("id").asText();
            new ViewStore(c.root.resolve("views")).write(new ViewDefinition("lines_view", "flow-x", List.of(),
                    "SELECT * FROM (VALUES ('K1', 'L1', 'R-A', 'run-9')) AS t(group_key, line, rate_id, run_id)",
                    "2026-11-01T00:00:00Z"));
            assertTrue(data(send(c, "GET", "/regulatory-reports/" + id, null, CHECKER), 200).get("sourceChanged").asBoolean());
        }
    }

    /** The withholding follows the record, not the template as edited later: dropping {@code rows} releases nothing. */
    @Test
    void aTemplateEditedAfterTheDraftDoesNotReleaseTheRows() throws Exception {
        try (Ctx c = open()) {
            seedDataset(c);
            seedTemplate(c, "lines");
            String id = data(send(c, "POST", "/regulatory-reports", body(seedCase(c, "K1")), AUTHOR), 200).get("id").asText();
            Files.writeString(c.root.resolve(ReportTemplate.SPACE_DIR).resolve("line-pack.toon"), """
                    id: line-pack
                    title: Line pack
                    format: json
                    delivery:
                      kind: file-drop
                      dir: regulatory-submissions/line-pack
                    fields[1]{name,source,required,maxLength}:
                      caseTitle,subject.title,true,400
                    """);
            JsonNode read = data(send(c, "GET", "/regulatory-reports/" + id, null, AUTHOR), 200);
            assertFalse(read.has("content"), "still withheld: " + read);
            JsonNode listed = data(send(c, "GET", "/regulatory-reports", null, AUTHOR), 200);
            assertFalse(listed.toString().contains("Dispute K1"), "the list withholds the subject title too: " + listed);
        }
    }

    @Test
    void aRequiredRowLeafLeftBlankRefusesTheDraft() throws Exception {
        try (Ctx c = open()) {
            seedDataset(c);
            seedTemplate(c, "lines");
            HttpResponse<String> r = send(c, "POST", "/regulatory-reports", body(seedCase(c, "K3")), AUTHOR);
            assertEquals(422, r.statusCode(), r.body());
            assertTrue(r.body().contains("line/runId") && r.body().contains("rows item 1"), r.body());
            HttpResponse<String> none = send(c, "POST", "/regulatory-reports", body(seedCase(c, "K-UNKNOWN")), AUTHOR);
            assertEquals(422, none.statusCode(), "no row behind the keys is an empty required group: " + none.body());
            assertTrue(none.body().contains("no rows"), none.body());
        }
    }

    @Test
    void anAbsentRowsDatasetIsNotFound() throws Exception {
        try (Ctx c = open()) {
            seedDataset(c);
            seedTemplate(c, "no_such_dataset");
            HttpResponse<String> r = send(c, "POST", "/regulatory-reports", body(seedCase(c, "K1")), AUTHOR);
            assertEquals(404, r.statusCode(), r.body());
            assertTrue(r.body().contains("no_such_dataset"), r.body());
        }
    }

    @Test
    void aRowsSourceNeedsAWellFormedRowsBlock() {
        String head = """
                id: t
                title: T
                format: json
                delivery:
                  kind: file-drop
                  dir: d
                """;
        ReportTemplate.Invalid noBlock = assertThrows(ReportTemplate.Invalid.class, () -> ReportTemplate.parse(head + """
                fields[1]{name,source,required,maxLength}:
                  l/id,rows.line,true,0
                """, "t", "space"));
        assertTrue(noBlock.problems().toString().contains("needs a 'rows' block"), noBlock.problems().toString());
        ReportTemplate.Invalid bad = assertThrows(ReportTemplate.Invalid.class, () -> ReportTemplate.parse(head + """
                rows:
                  dataset: "x; DROP"
                  matchOn: k
                  extra: 1
                fields[1]{name,source,required,maxLength}:
                  l/id,rows.line,true,0
                """, "t", "space"));
        assertTrue(bad.problems().toString().contains("'rows.dataset'"), bad.problems().toString());
        assertTrue(bad.problems().toString().contains("unknown key 'rows.extra'"), bad.problems().toString());
    }
}
