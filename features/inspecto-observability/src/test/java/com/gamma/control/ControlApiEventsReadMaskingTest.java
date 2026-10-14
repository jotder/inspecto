package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OBJECT-READ-MASKING-RESIDUALS-1: a stored {@code OBJECT_OPENED} row may hold the object's title RAW (the log is append-only),
 * so {@code /events/search} and {@code /events/export} (JSON and CSV) mask it on read; with no object module here the object
 * cannot be resolved, which is the fail-closed path (title and correlation id dropped). {@code ?q=} / {@code ?correlationId=}
 * match the MASKED event for a non-revealer, so neither probes a raw key. The resolved path is
 * {@code AuditReadMaskingOpenedEventTest} (module inspecto).
 */
class ControlApiEventsReadMaskingTest {

    private static final String RAW = "99970001401";
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void disarm() {
        Authenticators.forTest(null);
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path dir) throws Exception {
        Authenticators.forTest(ex -> {
            String a = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            return a.startsWith("Bearer ") ? Optional.of(new Subject(a.substring(7),
                    "revealer".equals(a.substring(7)) ? Set.of("canRevealLinkEntities") : Set.of())) : Optional.empty();
        });
        Path toon = TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema()).write();
        Path wr = Files.createDirectories(dir.resolve("wr"));
        new ComponentStore(wr.resolve("registry")).write("dataset", "fraud_ds", Map.of("columns", List.of(
                Map.of("name", "msisdn", "classification", "MSISDN"))));
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

    private String get(Ctx c, String user, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .header("Authorization", "Bearer " + user).GET().build(), BodyHandlers.ofString()).body();
    }

    private static void legacyOpened() {
        EventLog.current().emit(Event.builder(EventType.OBJECT_OPENED).level(EventLevel.INFO).source("ObjectService")
                .correlationId("case-" + RAW).message("INCIDENT opened: breached msisdn=" + RAW + " [obj-1]").attr("objectId", "obj-1"));
    }

    @Test
    void anUnresolvableOpenedEventLosesItsTitleAndCorrelationOnEveryReadSurface(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            legacyOpened();
            for (String path : List.of("/events/search?type=OBJECT_OPENED&limit=50", "/events/export?type=OBJECT_OPENED&format=csv",
                    "/events/export?type=OBJECT_OPENED")) {
                String viewer = get(c, "viewer", path);
                assertFalse(viewer.contains(RAW), path + ": " + viewer);
                assertTrue(viewer.contains("INCIDENT opened [obj-1]"), path + ": " + viewer);
                assertTrue(get(c, "revealer", path).contains(RAW), path);
            }
        }
    }

    @Test
    void qAndCorrelationIdMatchTheMaskedEventForANonRevealer(@TempDir Path dir) throws Exception {
        try (Ctx c = open(dir)) {
            legacyOpened();
            for (String filter : List.of("q=" + RAW, "correlationId=case-" + RAW))
                for (String path : List.of("/events/search?", "/events/export?")) {
                    assertFalse(get(c, "viewer", path + filter).contains("obj-1"), "viewer " + path + filter);
                    assertTrue(get(c, "revealer", path + filter).contains("obj-1"), "revealer " + path + filter);
                }
            assertTrue(get(c, "viewer", "/events/search?q=opened").contains("obj-1"), "a text that is not the key still filters");
            assertTrue(get(c, "viewer", "/events/search?type=OBJECT_OPENED&limit=1&offset=0").contains("obj-1"));
        }
    }
}
