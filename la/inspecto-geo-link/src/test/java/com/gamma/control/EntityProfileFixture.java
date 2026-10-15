package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewDefinition;
import com.gamma.pipeline.ViewStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The Entity profile routes' shared fixture (la-entity-profile-plan §4.5): a real {@link ControlApi} with an ARMED Authenticator
 * (with no Subject a gate test would pass against an ungated route), one view-backed Dataset {@code calls_ds} owned by
 * {@code analyst-1} whose {@code a_msisdn} / {@code b_msisdn} columns are classified MSISDN (masked, e164) and {@code imei} IMEI
 * (masked, digits), with {@code eventDate: ts}, and a saved Link Analysis view linking {@code a_msisdn} to {@code b_msisdn}.
 * Phone-shaped ids so a leak scan cannot match ordinary words.
 */
final class EntityProfileFixture {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String OWNER = "Bearer owner", STRANGER = "Bearer stranger";
    static final String A = "+249912000001", B = "+249912000002", C = "+249912000003", D = "+249912000004";
    static final String I1 = "350000000000011", I2 = "350000000000022", I3 = "350000000000033";
    /** Every raw identifier of the fixture, with and without the plus. */
    static final List<String> RAW = List.of(A, B, C, D, A.substring(1), B.substring(1), C.substring(1), D.substring(1), I1, I2, I3);
    static final String ROWS = String.join(",",
            "('" + A + "','" + B + "','" + I1 + "','sms',TIMESTAMP '2026-10-01 10:00:00')",
            "('" + A + "','" + C + "','" + I1 + "','call',TIMESTAMP '2026-10-01 11:00:00')",
            "('" + A + "','" + D + "','" + I1 + "','call',TIMESTAMP '2026-10-03 09:00:00')",
            "('" + B + "','" + C + "','" + I2 + "','call',TIMESTAMP '2026-10-03 10:00:00')",
            "('-99','" + A + "','" + I3 + "','call',TIMESTAMP '2026-10-03 11:00:00')",
            "('unknown','" + B + "','" + I3 + "','sms',TIMESTAMP '2026-10-03 12:00:00')");

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private EntityProfileFixture() {}

    record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case OWNER -> Optional.of(new Subject("analyst-1", Set.of()));
            case STRANGER -> Optional.of(new Subject("analyst-3", Set.of()));
            default -> Optional.empty();
        });
    }

    static Map<String, Object> callsDataset() {
        return Map.of("view", "calls_view", "owner", "analyst-1", "shares", List.of(), "eventDate", "ts",
                "columns", List.of(Map.of("name", "a_msisdn", "classification", "MSISDN"),
                        Map.of("name", "b_msisdn", "classification", "MSISDN"),
                        Map.of("name", "imei", "classification", "IMEI")));
    }

    /** Opens the API; {@code writeRoot} null = no write root (the 503 gate). */
    static Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            if (writeRoot != null) {
                new ViewStore(writeRoot.resolve("views")).write(new ViewDefinition("calls_view", "flow-x", List.of(),
                        "SELECT a_msisdn, b_msisdn, imei, kind, ts FROM (VALUES " + ROWS + ") AS t(a_msisdn,b_msisdn,imei,kind,ts)",
                        "2026-10-15T00:00:00Z"));
                ComponentStore reg = new ComponentStore(writeRoot.resolve("registry"));
                reg.write("dataset", "calls_ds", callsDataset());
                reg.write("link-analysis-view", "calls_graph", Map.of("name", "Calls", "sourceId", "entity-projection",
                        "query", Map.of("projection", Map.of("datasetId", "calls_ds", "sourceCol", "a_msisdn", "targetCol", "b_msisdn",
                                "linkKindCol", "kind"))));
            }
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    static void settings(Ctx c, String toon) throws Exception {
        Files.writeString(c.root().resolve("link-analysis.toon"), toon);
    }

    static HttpResponse<String> send(Ctx c, String method, String path, String body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port() + "/api/v1" + path))
                .header("Content-Type", "application/json").header("Authorization", auth);
        b = "GET".equals(method) ? b.GET() : b.method(method, BodyPublishers.ofString(body == null ? "" : body));
        return CLIENT.send(b.build(), BodyHandlers.ofString());
    }

    static HttpResponse<String> get(Ctx c, String path, String auth) throws Exception {
        return send(c, "GET", path, null, auth);
    }

    static JsonNode data(HttpResponse<String> r) throws Exception {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /**
     * A 200 answer whose payload names none of the fixture's raw identifiers anywhere. ⚠ The payload, not the body: the envelope's
     * {@code links.self} echoes the caller's own request path, which carries the key the caller typed.
     */
    static JsonNode noRaw(HttpResponse<String> r) throws Exception {
        JsonNode d = data(r);
        String payload = d.toString();
        for (String raw : RAW) assertFalse(payload.contains(raw), "leaked the raw id " + raw + ": " + payload);
        return d;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
