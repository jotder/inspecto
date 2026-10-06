package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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
 * D-P8 (operator 2026-10-06): audit rows are masked ON READ for a caller without the unmask capability, raw for a
 * holder, and the stored chain stays raw so {@code /audit/verify} still passes.
 */
class ControlApiAuditReadMaskingTest {

    private static final String RAW = "447700900123";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case "Bearer revealer" -> Optional.of(new Subject("admin-1",
                    Set.of("canAdminister", AuditReadMasking.UNMASK_CAPABILITY)));
            case "Bearer admin" -> Optional.of(new Subject("admin-2", Set.of("canAdminister")));
            default -> Optional.empty();
        });
    }

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private Ctx open(Path dir, boolean classify) throws Exception {
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path wr = Files.createDirectories(dir.resolve("wr"));
        new ComponentStore(wr.resolve("registry")).write("dataset", "calls", Map.of("columns", List.of(
                Map.of("name", "msisdn", "classification", classify ? "MSISDN" : "NONE"),
                Map.of("name", "region"))));
        System.setProperty("assist.write.root", wr.toString());
        try {
            CollectorService svc = new CollectorService(List.of(toon), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> get(Ctx c, String path, String auth) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", auth).GET().build(), BodyHandlers.ofString());
    }

    private static void emit() {
        EventLog.current().emit(Event.builder(EventType.AUDIT).source("test").message("lookup of " + RAW)
                .actor("admin-1").action("subscriber.lookup").attr("msisdn", RAW).attr("region", "north"));
    }

    private static JsonNode row(JsonNode rows) {
        for (JsonNode r : rows) if ("subscriber.lookup".equals(r.path("attributes").path("action").asText())) return r;
        return fail("the emitted audit row is not served: " + rows);
    }

    @Test
    void aRevealerReadsRawAnotherCallerReadsMaskedAndTheChainStillVerifies(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, true)) {
            emit();
            JsonNode raw = row(V1Body.of(get(c, "/audit/search?type=AUDIT&limit=500", "Bearer revealer").body()));
            assertEquals(RAW, raw.get("attributes").get("msisdn").asText());
            assertTrue(raw.get("message").asText().contains(RAW));

            JsonNode masked = row(V1Body.of(get(c, "/audit/search?type=AUDIT&limit=500", "Bearer admin").body()));
            String token = masked.get("attributes").get("msisdn").asText();
            assertTrue(token.startsWith("masked:"), masked.toString());
            assertEquals("north", masked.get("attributes").get("region").asText(), "an unclassified value stays");
            assertFalse(masked.toString().contains(RAW), "the raw value leaks: " + masked);
            assertTrue(masked.get("message").asText().contains(token));

            String csv = get(c, "/audit/export?type=AUDIT&format=csv", "Bearer admin").body();
            assertFalse(csv.contains(RAW), "the CSV export leaks the raw value");
            assertTrue(get(c, "/audit/export?type=AUDIT&format=csv", "Bearer revealer").body().contains(RAW));

            // stored rows stay raw: the chain is over raw content and still verifies
            JsonNode v = V1Body.of(get(c, "/audit/verify", "Bearer admin").body());
            assertTrue(v.get("ok").asBoolean(), v.toString());
        }
    }

    /** The negative probe: with the column unclassified, the same caller reads the same row RAW. */
    @Test
    void anUnclassifiedColumnIsServedRawToTheSameCaller(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir, false)) {
            emit();
            JsonNode r = row(V1Body.of(get(c, "/audit/search?type=AUDIT&limit=500", "Bearer admin").body()));
            assertEquals(RAW, r.get("attributes").get("msisdn").asText());
        }
    }

    @Test
    void anUntraceableLineageMasksEveryNonPlatformAttributeAndOtherTypesPassThrough() {
        Event audit = Event.builder(EventType.AUDIT).message("x").action("a.b").attr("note", "secret-1").build();
        Event masked = AuditReadMasking.mask(audit, Set.of("*"), v -> "masked:t");
        assertEquals("masked:t", masked.attributes().get("note"));
        assertEquals("a.b", masked.attributes().get("action"), "a platform audit key is not a data value");
        Event log = Event.builder(EventType.LOG).attr("msisdn", RAW).build();
        assertSame(log, AuditReadMasking.mask(log, Set.of("msisdn"), v -> "masked:t"));
    }
}
