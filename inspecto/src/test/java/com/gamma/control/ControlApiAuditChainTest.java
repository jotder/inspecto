package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.AuditChain;
import com.gamma.event.Event;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-HTTP proof of the tamper-evident audit trail's routes (ASSURE-AUDIT-CHAIN-1) with an ARMED Authenticator,
 * so the {@code canAdminister} gate is actually exercised: {@code GET /audit/verify}, {@code GET /audit/anchors}
 * and {@code POST /audit/anchors}. The audit rows the requests themselves produce (the refusals below are
 * ACCESS_DENIED rows) are the chain under test.
 */
class ControlApiAuditChainTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer admin" -> Optional.of(new Subject("admin-1", Set.of("canAdminister")));
            case "Bearer viewer" -> Optional.of(new Subject("viewer-1", Set.of()));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir, boolean writeRoot) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        if (writeRoot) System.setProperty("assist.write.root", Files.createDirectories(dir.resolve("wr")).toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        if (auth != null) b.header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    @Test
    void theChainRoutesAreAnAdministratorsAndRefuseEveryoneElse(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            for (String[] r : List.of(new String[] {"GET", "/audit/verify"}, new String[] {"GET", "/audit/anchors"},
                    new String[] {"POST", "/audit/anchors"})) {
                assertEquals(401, send(c, r[0], r[1], null).statusCode(), r[0] + " " + r[1] + " unauthenticated");
                HttpResponse<String> viewer = send(c, r[0], r[1], "Bearer viewer");
                assertEquals(403, viewer.statusCode(), r[0] + " " + r[1] + " without canAdminister: " + viewer.body());
                assertEquals(200, send(c, r[0], r[1], "Bearer admin").statusCode(), r[0] + " " + r[1] + " as admin");
            }
        }
    }

    @Test
    void anAdministratorVerifiesAnchorsAndExportsTheAnchors(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            for (int i = 0; i < 3; i++) send(c, "GET", "/audit/verify", "Bearer viewer");   // three refusals = rows

            JsonNode v = V1Body.of(send(c, "GET", "/audit/verify", "Bearer admin").body());
            assertTrue(v.get("ok").asBoolean(), v.toString());
            assertTrue(v.get("checked").asLong() >= 3, v.toString());
            assertTrue(v.get("fromGenesis").asBoolean(), v.toString());
            assertEquals("checked", v.get("anchors").asText());

            HttpResponse<String> made = send(c, "POST", "/audit/anchors", "Bearer admin");
            assertEquals(200, made.statusCode(), made.body());
            JsonNode anchor = V1Body.of(made.body());
            assertTrue(anchor.get("created").asBoolean(), anchor.toString());
            assertEquals("on-demand", anchor.get("anchor").get("kind").asText());
            assertEquals("valid", anchor.get("anchor").get("integrity").asText());
            long lastSeq = anchor.get("anchor").get("lastSeq").asLong();
            assertTrue(lastSeq >= 3);

            JsonNode list = V1Body.of(send(c, "GET", "/audit/anchors", "Bearer admin").body());
            assertEquals(1, list.get("total").asInt(), list.toString());
            assertFalse(list.get("truncated").asBoolean());
            assertEquals(lastSeq, list.get("anchors").get(0).get("lastSeq").asLong());
            assertEquals(64, list.get("anchors").get(0).get("mac").asText().length());

            JsonNode again = V1Body.of(send(c, "GET", "/audit/verify", "Bearer admin").body());
            assertTrue(again.get("ok").asBoolean(), again.toString());
            assertEquals(1, again.get("anchorsChecked").asInt(), again.toString());
        }
    }

    /** A record forged straight into the store (not through the EventLog) — verify names it over HTTP. */
    @Test
    void aForgedRecordInTheStoreIsNamedByTheVerifyRoute(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            send(c, "GET", "/audit/verify", "Bearer viewer");
            send(c, "GET", "/audit/verify", "Bearer viewer");
            Event first = c.svc().events().chainPage(1, 1).get(0);
            c.svc().events().append(new Event("forged-1", first.ts(), first.level(), first.type(), first.source(),
                    first.pipeline(), first.correlationId(), "mallory was never here", first.attributes(),
                    first.payload()));
            assertEquals(1, AuditChain.seq(first));

            JsonNode v = V1Body.of(send(c, "GET", "/audit/verify", "Bearer admin").body());
            assertFalse(v.get("ok").asBoolean(), v.toString());
            assertEquals(1, v.get("firstBad").get("seq").asLong(), v.toString());
            assertEquals("duplicate", v.get("firstBad").get("reason").asText(), v.toString());
            // …and an anchor will not be written over it
            assertEquals(409, send(c, "POST", "/audit/anchors", "Bearer admin").statusCode());
        }
    }

    @Test
    void aSeqRangeThatIsNotOneIsA400(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            for (String q : List.of("?from=0", "?from=abc", "?to=-3", "?from=5&to=2"))
                assertEquals(400, send(c, "GET", "/audit/verify" + q, "Bearer admin").statusCode(), q);
            assertEquals(200, send(c, "GET", "/audit/verify?from=1&to=2", "Bearer admin").statusCode());
        }
    }

    /** No write root = no Space key: the chain still verifies, but there are no anchors to read or write. */
    @Test
    void withoutAWriteRootAnchorsAre503AndVerifySaysSo(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            assertEquals(503, send(c, "GET", "/audit/anchors", "Bearer admin").statusCode());
            assertEquals(503, send(c, "POST", "/audit/anchors", "Bearer admin").statusCode());
            HttpResponse<String> v = send(c, "GET", "/audit/verify", "Bearer admin");
            assertEquals(200, v.statusCode(), v.body());
            assertEquals("unavailable", V1Body.of(v.body()).get("anchors").asText());
        }
    }
}
