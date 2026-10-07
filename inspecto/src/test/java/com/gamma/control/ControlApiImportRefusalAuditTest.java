package com.gamma.control;

import com.gamma.spi.auth.Authenticators;
import com.gamma.spi.auth.Subject;
import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.SpaceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round 5: the IMPORT doors write Pipeline configs through {@code ImportJournal}, not the write routes — each import
 * that drops {@code refusal_scan} from a Pipeline emits exactly one {@code pipeline.refusal.changed} AUDIT event.
 */
class ControlApiImportRefusalAuditTest {

    private static final String BUILDER = "Bearer builder";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port, Path config) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }
    }

    @BeforeEach
    void arm() {
        Authenticators.forTest(ex -> BUILDER.equals(ex.getRequestHeaders().getFirst("Authorization"))
                ? Optional.of(new Subject("builder-1", Set.of("canAuthorWorkbench"))) : Optional.empty());
    }

    @AfterEach
    void disarm() { Authenticators.forTest(null); }

    /** Space {@code alpha}: {@code test_etl} with the card scan ON. */
    private Ctx open(Path root) throws Exception {
        Path base = root.resolve("alpha");
        Path config = base.resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(base.resolve("data").resolve("etl"), PipelineConfigBatchTest.miniSchema()).write();
        Map<String, Object> p = ConfigCodec.toMap(Files.readString(tmp));
        @SuppressWarnings("unchecked") Map<String, Object> proc = (Map<String, Object>) p.get("processing");
        proc.put("refusal", "restricted_quarantine");
        proc.put("refusal_scan", "card_number");
        Files.writeString(config.resolve("etl_pipeline.toon"), ConfigCodec.toToon(p));
        Files.delete(tmp);
        Files.writeString(base.resolve("space.toon"), "display_name: \"Alpha\"\ndescription: \"x\"\ncreated_at: \"2026-06-23\"\n");
        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port(), config);
    }

    private HttpResponse<String> post(Ctx c, String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/spaces/alpha" + path))
                .header("Authorization", BUILDER).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] withoutScan(byte[] pipelineToon) {
        Map<String, Object> p = ConfigCodec.toMap(new String(pipelineToon, StandardCharsets.UTF_8));
        ((Map<?, ?>) p.get("processing")).remove("refusal_scan");
        return ConfigCodec.toToon(p).getBytes(StandardCharsets.UTF_8);
    }

    private static List<Event> audited(ThrowingRunnable r) throws Exception {
        List<Event> seen = new ArrayList<>();
        BiConsumer<EventLog, Event> tap = (log, e) -> { if (e.toString().contains("pipeline.refusal.changed")) seen.add(e); };
        EventLog.addTap(tap);
        try { r.run(); } finally { EventLog.removeTap(tap); }
        return seen;
    }

    interface ThrowingRunnable { void run() throws Exception; }

    @Test
    void theDataSourceImportAuditsADroppedScan(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] off = withoutScan(Files.readAllBytes(c.config.resolve("etl_pipeline.toon")));
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("bundle.toon", "kind: datasource\n".getBytes(StandardCharsets.UTF_8));
            entries.put("etl_pipeline.toon", off);
            List<Event> events = audited(() -> {
                HttpResponse<String> r = post(c, "/import?on_conflict=overwrite", zip(entries));
                assertEquals(200, r.statusCode(), r.body());
            });
            assertEquals(1, events.size(), "exactly one event for the one changed Pipeline: " + events);
            assertTrue(events.get(0).toString().contains("card_number"), events.toString());
            assertFalse(Files.readString(c.config.resolve("etl_pipeline.toon")).contains("refusal_scan"), "the import landed");
        }
    }

    @Test
    void thePipelineBundleImportAuditsADroppedScan(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<byte[]> ex = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                            + "/api/v1/spaces/alpha/pipelines/test_etl/bundle")).header("Authorization", BUILDER).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, ex.statusCode());
            LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(ex.body()))) {
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry())
                    if (!e.isDirectory()) entries.put(e.getName(), zis.readAllBytes());
            }
            Map<String, Object> manifest = ConfigCodec.toMap(new String(entries.get("manifest.toon"), StandardCharsets.UTF_8));
            String pipelineEntry = String.valueOf(manifest.get("pipeline_file"));
            entries.put(pipelineEntry, withoutScan(entries.get(pipelineEntry)));
            List<Event> events = audited(() -> {
                HttpResponse<String> r = post(c, "/pipelines/import?name=test_etl&conflict=overwrite", zip(entries));
                assertEquals(200, r.statusCode(), r.body());
            });
            assertEquals(1, events.size(), "exactly one event for the one changed Pipeline: " + events);
            assertTrue(events.get(0).toString().contains("card_number"), events.toString());
        }
    }
}
