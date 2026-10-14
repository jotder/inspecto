package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.la.core.CollectorCoveragePort;
import com.gamma.la.core.CollectorCoveragePorts;
import com.gamma.la.storage.IndexManifest;
import com.gamma.la.storage.IndexMapping;
import com.gamma.la.storage.IndexStore;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
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
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-DATA-TRUST-1 S9 - the Dossier data-trust footer over REAL HTTP with an ARMED Authenticator. The footer (data-as-of, index
 * version, days missing, Files waiting) is NOT sealed: the manifest root, the bundle seal and both verifications must be
 * identical with it and across two exports at different data states, and an edited footer must not read as tampered evidence.
 */
class ControlApiDossierDataFooterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CREATE = "{\"purpose\":\"test\",\"id\":\"inv-a\",\"dataset\":\"calls_ds\","
            + "\"sourceCol\":\"caller\",\"targetCol\":\"callee\",\"linkKindCol\":\"channel\",\"timeCol\":\"ts\"}";
    private static final String INV = "/inv/investigations/inv-a";
    private static final String OWNER = "Bearer owner", OTHER = "Bearer other";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void reset() {
        Authenticators.forTest(null);
        CollectorCoveragePorts.forTest(null);
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case OTHER -> Optional.of(new Subject("analyst-2", Set.of("canManageIncidents")));
            default -> Optional.empty();
        });
    }

    /** The view's DATE column {@code ev} is the Dataset's event date; its maximum is the data-as-of. */
    private static void view(Path writeRoot, String lastDay) throws Exception {
        new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                "SELECT caller, callee, channel, ts, ev FROM (VALUES "
                        + "('alice','bob','voice',TIMESTAMP '2026-09-01 10:00:00',DATE '2026-09-01'),"
                        + "('alice','carol','sms',TIMESTAMP '2026-09-03 10:00:00',DATE '" + lastDay + "'),"
                        + "('bob','dave','voice',TIMESTAMP '2026-09-03 11:00:00',DATE '2026-09-03')) AS t(caller,callee,channel,ts,ev)",
                "2026-10-03T00:00:00Z"));
    }

    private Ctx open(Path configDir, Path writeRoot, boolean eventDate) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            view(writeRoot, "2026-09-03");
            new ComponentStore(writeRoot.resolve("registry")).write("dataset", "calls_ds",
                    eventDate ? Map.of("view", "calls_view", "eventDate", "ev") : Map.of("view", "calls_view"));
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static void publishIndex(Path writeRoot, int version) throws Exception {
        IndexMapping mp = new IndexMapping("caller", "callee", null, null, null, null, List.of());
        IndexManifest m = new IndexManifest(version, "2026-09-05T10:00:0" + version + "Z", IndexManifest.Builder.FULL, "1.4", "hash_mod", 16, 1,
                mp, mp.hash(), "calls_ds", "sql", "fp", null, Map.of(), 0, List.of(), null, List.of());
        IndexStore store = new IndexStore(writeRoot.resolve("la-index"), "calls_ds", mp.hash());
        Path staged = store.stage();
        m.write(staged);
        store.publish(staged);
    }

    private static void waitingFile() {
        CollectorCoveragePorts.forTest(new CollectorCoveragePort() {
            @Override
            public Optional<List<Delivery>> deliveries(com.gamma.spi.http.ApiContext api, Map<String, Object> dataset) {
                return Optional.empty();
            }

            @Override
            public Optional<List<FileEntry>> files(com.gamma.spi.http.ApiContext api, Map<String, Object> dataset) {
                return Optional.of(List.of(new FileEntry("a.csv", "2026-09-03", 3, 0, "2026-09-04T00:00:00Z", "SUCCESS", "c1", "p1", "b1",
                        List.of("/srv/calls/day=2026-09-03/a.parquet"))));
            }
        });
    }

    private HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode ok(Ctx c, String method, String path, String body) throws Exception {
        HttpResponse<String> r = send(c, method, path, body, OWNER);
        assertEquals(200, r.statusCode(), method + " " + path + " → " + r.body());
        return JSON.readTree(r.body()).get("data");
    }

    private void investigation(Ctx c) throws Exception {
        ok(c, "POST", "/inv/investigations", CREATE);
        ok(c, "POST", INV + "/ops", "{\"op\":\"window\",\"window\":{\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-04T00:00:00Z\"}}");
        ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
        ok(c, "POST", INV + "/ops", "{\"op\":\"expand\"}");
    }

    @Test
    void theFooterReportsTheDataStateAndTheRootIsIdenticalAcrossTwoDataStates(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        waitingFile();
        try (Ctx c = open(cfg, root, true)) {
            investigation(c);
            publishIndex(root, 1);
            JsonNode first = ok(c, "GET", INV + "/dossier", null);
            JsonNode f1 = first.get("dataFooter");
            assertFalse(f1.get("sealed").asBoolean());
            assertTrue(f1.get("note").asText().contains("NOT part of the custody hash"));
            assertEquals("2026-09-04T00:00:00Z", f1.get("asOf").asText(), "a DATE counts through its end");
            assertEquals(1, f1.get("indexVersion").asInt());
            assertTrue(f1.has("indexStale") && !f1.get("indexStale").isNull());
            assertEquals(1, f1.get("filesWaiting").asInt());
            assertEquals(3, f1.get("expectedDays").asInt());
            assertEquals("[\"2026-09-02\"]", f1.get("missingDays").toString());
            assertTrue(f1.get("notAssessed").isEmpty(), f1.toString());
            String text = f1.toString();
            assertFalse(text.contains("alice") || text.contains("bob") || text.contains("a.csv"), "no entity key, no File name in the footer");

            // The data moves: a later event date and a newer index version.
            view(root, "2026-09-09");
            publishIndex(root, 2);
            JsonNode second = ok(c, "GET", INV + "/dossier", null);
            JsonNode f2 = second.get("dataFooter");
            assertEquals("2026-09-10T00:00:00Z", f2.get("asOf").asText());
            assertEquals(2, f2.get("indexVersion").asInt());
            assertNotEquals(f1.toString(), f2.toString(), "the footer moved with the data");
            assertEquals(first.get("manifest").toString(), second.get("manifest").toString(), "root and every artefact hash identical");
            assertEquals(first.get("manifest").get("root").asText(), second.get("manifest").get("root").asText());
            assertEquals(first.get("integrity").toString(), second.get("integrity").toString());
        }
    }

    @Test
    void replayAndDossierVerifyIgnoreTheFooter(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, true)) {
            investigation(c);
            ObjectNode dossier = (ObjectNode) ok(c, "GET", INV + "/dossier", null);
            // The whole footer-bearing Dossier is the body; then a Dossier whose footer was rewritten: the same verdict.
            JsonNode clean = ok(c, "POST", INV + "/dossier/verify", dossier.toString());
            assertTrue(clean.get("verified").asBoolean(), clean.toString());
            dossier.putObject("dataFooter").put("asOf", "2099-01-01T00:00:00Z").putArray("missingDays");
            JsonNode edited = ok(c, "POST", INV + "/dossier/verify", dossier.toString());
            assertTrue(edited.get("verified").asBoolean(), "an edited footer is not tampered evidence");
            assertEquals(clean.get("currentRoot").asText(), edited.get("currentRoot").asText());
            // Control: a changed MANIFEST still fails, so the pass above is not vacuous.
            ((ObjectNode) dossier.get("manifest")).put("root", "0".repeat(64));
            assertFalse(ok(c, "POST", INV + "/dossier/verify", dossier.toString()).get("verified").asBoolean());
        }
    }

    @Test
    void theBundleSealIsIdenticalAcrossDataStatesAndAnEditedFooterIsNotTamperedEvidence(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, true)) {
            investigation(c);
            publishIndex(root, 1);
            JsonNode b1 = ok(c, "GET", INV + "/dossier/bundle", null);
            view(root, "2026-09-09");
            publishIndex(root, 2);
            JsonNode b2 = ok(c, "GET", INV + "/dossier/bundle", null);
            assertNotEquals(b1.get("dataFooter").toString(), b2.get("dataFooter").toString());
            assertEquals(b1.get("seal").get("value").asText(), b2.get("seal").get("value").asText(), "the seal does not move with the data");
            assertFalse(b1.get("dossier").has("dataFooter"), "the footer sits beside the Dossier, never inside what is sealed");
            assertTrue(ok(c, "POST", INV + "/dossier/bundle/verify", b1.toString()).get("verified").asBoolean());

            ObjectNode tampered = (ObjectNode) b1.deepCopy();
            ((ObjectNode) tampered.get("dataFooter")).put("asOf", "2099-01-01T00:00:00Z").putArray("missingDays");
            JsonNode v = ok(c, "POST", INV + "/dossier/bundle/verify", tampered.toString());
            assertTrue(v.get("verified").asBoolean(), "the documented price of leaving it out of the seal: " + v);
            assertFalse(tampered.get("dataFooter").get("sealed").asBoolean(), "and the footer says it is state at export time, not sealed");

            // Control: editing what IS sealed still fails.
            ((ObjectNode) tampered).put("at", 0);
            assertFalse(ok(c, "POST", INV + "/dossier/bundle/verify", tampered.toString()).get("verified").asBoolean());
        }
    }

    @Test
    void htmlRendersTheFooterAfterAndApartFromTheManifest(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, true)) {
            investigation(c);
            HttpResponse<String> r = send(c, "GET", INV + "/dossier?format=html", null, OWNER);
            assertEquals(200, r.statusCode());
            String html = r.body();
            int manifest = html.indexOf("<h2>Manifest</h2>");
            int footer = html.indexOf("<h2>Data state at export time, not part of the custody hash</h2>");
            assertTrue(manifest > 0 && footer > manifest, "the footer is its own block after the Manifest block");
            assertTrue(html.indexOf("2026-09-04T00:00:00Z", footer) > footer);
        }
    }

    @Test
    void aDatasetWithNoEventDateOrWindowIsNotAssessedNeverNoGaps(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, false)) {
            ok(c, "POST", "/inv/investigations", CREATE);
            ok(c, "POST", INV + "/ops", "{\"op\":\"seed\",\"ids\":[\"alice\"]}");
            JsonNode f = ok(c, "GET", INV + "/dossier", null).get("dataFooter");
            assertTrue(f.get("asOf").isNull() && f.get("missingDays").isNull() && f.get("indexVersion").isNull());
            String na = f.get("notAssessed").toString();
            assertTrue(na.contains("asOf") && na.contains("days") && na.contains("index") && na.contains("files"), na);
        }
    }

    @Test
    void aNonOwnerGetsTheDossiersOwn404NotAFooter(@TempDir Path cfg, @TempDir Path root) throws Exception {
        subjects();
        try (Ctx c = open(cfg, root, true)) {
            investigation(c);
            assertEquals(404, send(c, "GET", INV + "/dossier", null, OTHER).statusCode());
            assertEquals(404, send(c, "GET", INV + "/dossier/bundle", null, OTHER).statusCode());
        }
    }
}
