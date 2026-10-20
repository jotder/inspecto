package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.event.EventLog;
import com.gamma.pipeline.ComponentStore;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-ANALYST-UPLOAD-1 (D4, i2-gap 2) - {@code POST /inv/uploads/{collector}} over real HTTP with a REAL Subject on every
 * request (so {@code withCapability} is enforced): 401 / 403 capability, 503 no write root, 422 name, 403 path-shaped name,
 * 404 unknown or non-upload Collector, 422 pattern / content, 413 size cap (declared, chunked) and daily quota, 403 four-eyes
 * row budget, 409 duplicate then {@code replace=true}, 429 rate class, the audit event, atomic landing (a reader never sees
 * a partial file), and the end-to-end: upload, ingest through the Collector's own Pipeline, identity import of the Dataset.
 */
class ControlApiAnalystUploadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UPLOADER = "Bearer uploader";
    private static final String ANALYST = "Bearer analyst";      // canManageIncidents but NOT canUploadData
    private static final String UPLOADER2 = "Bearer uploader2";  // canUploadData, a different analyst
    private static final String ADMIN = "Bearer admin";          // canUploadData + canAdminister
    private static final String VIEWER = "Bearer viewer";
    private static final String CSV = "MSISDN,IMSI,EVENT_DATE\n+447700900001,234101,2026-10-01\n+447700900002,234102,2026-10-01\n";
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root, Path inbox) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private static void subjects() {
        Authenticators.forTest(ex -> switch (String.valueOf(ex.getRequestHeaders().getFirst("Authorization"))) {
            case UPLOADER -> Optional.of(new Subject("uploader-1", Set.of("canUploadData", "canManageIncidents")));
            case ANALYST -> Optional.of(new Subject("analyst-1", Set.of("canManageIncidents")));
            case UPLOADER2 -> Optional.of(new Subject("uploader-2", Set.of("canUploadData")));
            case ADMIN -> Optional.of(new Subject("admin-1", Set.of("canUploadData", "canAdminister")));
            case VIEWER -> Optional.of(new Subject("viewer-1", Set.of()));
            default -> Optional.empty();
        });
    }

    @BeforeEach
    void setUp() {
        subjects();
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterEach
    void tearDown() {
        Authenticators.forTest(null);
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
        System.clearProperty("control.rateLimit.linkAnalysis.refillPerSecond");
    }

    /** The upload-enabled Pipeline UP_FEED (Collector accepts_upload, pattern UPL_*.csv) beside the plain MINI_ETL (does not). */
    private Ctx open(Path cfg, Path writeRoot, boolean acceptsUpload) throws Exception {
        return open(cfg, writeRoot, acceptsUpload, null);
    }

    /** {@code store} (a name under the data root, {@code ./database}) is where UP_FEED writes; null keeps it beside the inbox. */
    /** True: content-based (checksum) duplicate mode, no marker, no backup dir - the ingested file stays in the inbox. */
    private boolean checksumMode;

    private Ctx open(Path cfg, Path writeRoot, boolean acceptsUpload, String store) throws Exception {
        Path mini = PipelineConfigBatchTest.writePipeline(cfg, "");
        Path up = cfg.resolve("up");
        Files.createDirectories(up);
        Files.writeString(up.resolve("up_schema.toon"), """
                partitionKey: EVENT_DATE
                raw:
                  name: up
                  format: CSV
                  fields[3]{name,selector,type}:
                    MSISDN,"0",VARCHAR
                    IMSI,"1",VARCHAR
                    EVENT_DATE,"2",DATE
                mapping:
                  canonicalName: up
                  rawName: up
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    MSISDN,MSISDN,DIRECT
                    IMSI,IMSI,DIRECT
                    EVENT_DATE,EVENT_DATE,DIRECT
                """);
        String d = up.toString().replace("\\", "/");
        Path pipe = up.resolve("up_pipeline.toon");
        Files.writeString(pipe, """
                name: UP_FEED
                active: true
                dirs:
                  poll: %s/inbox
                  database: %s
                  %s
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  markers: %s/markers
                  status_dir: %s/status
                  log_dir: %s/logs
                output:
                  format: PARQUET
                  compression: snappy
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  duplicate_check:
                    enabled: %s
                    marker_extension: .processed
                  schema_file: "%s/up_schema.toon"
                  csv_settings:
                    delimiter: ","
                    has_header: true
                    date_formats[1]: "%%Y-%%m-%%d"
                collector:
                  discovery: poll
                  connector: local
                  include[1]: "glob:**/UPL_*.csv"
                %s
                """.formatted(d, store == null ? d + "/db" : Path.of("database").resolve(store).toAbsolutePath().toString().replace("\\", "/"),
                checksumMode ? "" : "backup: " + d + "/backup", d, d, d, d, d, d, true, d,
                (acceptsUpload ? "  accepts_upload: true\n" : "") + (checksumMode ? "  duplicate:\n    mode: checksum\n" : "")));
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(mini, pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot, Path.of(d, "inbox"));
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> up(Ctx c, String collector, String query, byte[] body, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1/inv/uploads/" + collector + query))
                .header("Content-Type", "text/csv").POST(BodyPublishers.ofByteArray(body));
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> up(Ctx c, String name, byte[] body) throws Exception {
        return up(c, "up_feed", "?name=" + name, body, UPLOADER);
    }

    private static void status(int expected, HttpResponse<String> r, String why) {
        assertEquals(expected, r.statusCode(), why + ": " + r.body());
    }

    private static String errorCode(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("errorCode").asText();
    }

    /** The Space admin states limits. The route needs no Subject in this fixture: lift the authenticator for the call. */
    private void limits(Ctx c, String body) throws Exception {
        Authenticators.forTest(null);
        try {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/settings/link-analysis/limits")).header("Content-Type", "application/json")
                    .PUT(BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), r.body());
        } finally {
            subjects();
        }
    }

    private static List<String> inboxFiles(Ctx c) throws Exception {
        if (!Files.isDirectory(c.inbox())) return List.of();
        try (Stream<Path> s = Files.list(c.inbox())) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private HttpResponse<String> call(Ctx c, String method, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path))
                .method(method, BodyPublishers.noBody());
        if (auth != null) b.header("Authorization", auth);
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode uploadsOf(Ctx c, String auth) throws Exception {
        HttpResponse<String> r = call(c, "GET", "/inv/uploads/up_feed", auth);
        status(200, r, "status read");
        return JSON.readTree(r.body()).get("data").get("uploads");
    }

    @Test
    void uploadEnabledCollectorsListIsGatedAndOnlyNamesOptedInCollectors(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(401, call(c, "GET", "/inv/uploads", null), "no credential");
            status(403, call(c, "GET", "/inv/uploads", VIEWER), "no capability");
            status(403, call(c, "GET", "/inv/uploads", ANALYST), "canManageIncidents is not canUploadData");
            HttpResponse<String> r = call(c, "GET", "/inv/uploads", UPLOADER);
            status(200, r, "armed");
            JsonNode rows = JSON.readTree(r.body()).get("data").get("collectors");
            assertEquals(1, rows.size(), r.body());
            assertEquals("up_feed", rows.get(0).get("collector").asText());
            assertTrue(rows.get(0).get("includes").toString().contains("UPL_*.csv"));
            assertTrue(rows.get(0).get("maxBytes").asLong() > 0);
            assertFalse(r.body().contains(c.inbox().toString().replace("\\", "\\\\")), "no filesystem path leaks");
        }
        try (Ctx c = open(cfg, root, false)) {
            assertEquals(0, JSON.readTree(call(c, "GET", "/inv/uploads", UPLOADER).body()).get("data").get("collectors").size());
        }
    }

    @Test
    void statusReadGatesAndShowsOnlyTheCallersOwnUploadsUnlessAdministrator(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(401, call(c, "GET", "/inv/uploads/up_feed", null), "no credential");
            status(403, call(c, "GET", "/inv/uploads/up_feed", VIEWER), "no capability");
            status(403, call(c, "GET", "/inv/uploads/up_feed", ANALYST), "canManageIncidents is not canUploadData");
            status(404, call(c, "GET", "/inv/uploads/mini_etl", UPLOADER), "a Collector that did not opt in");
            status(404, call(c, "GET", "/inv/uploads/NOPE", UPLOADER), "unknown Collector");
            assertEquals(0, uploadsOf(c, UPLOADER).size(), "nothing uploaded yet");

            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            status(201, up(c, "UPL_1.csv", b), "landed");
            JsonNode mine = uploadsOf(c, UPLOADER);
            assertEquals(1, mine.size());
            assertEquals("UPL_1.csv", mine.get(0).get("file").asText());
            assertEquals("pending", mine.get(0).get("state").asText(), "not picked up yet");
            assertEquals(b.length, mine.get(0).get("bytes").asInt());
            assertEquals(sha256(b), mine.get(0).get("sha256").asText());
            assertEquals(3, mine.get(0).get("lines").asInt());
            assertEquals(0, uploadsOf(c, UPLOADER2).size(), "another analyst does not see it");
            assertEquals(1, uploadsOf(c, ADMIN).size(), "canAdminister sees every upload");
            assertFalse(call(c, "GET", "/inv/uploads/up_feed", UPLOADER).body().contains("7700900001"), "metadata only, never a row");
        }
    }

    @Test
    void statusReadIs503WithoutAWriteRoot(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null, true)) {
            status(503, call(c, "GET", "/inv/uploads/up_feed", UPLOADER), "write root unset");
            status(503, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER), "write root unset");
        }
    }

    @Test
    void deleteWithdrawsAPendingUploadForItsUploaderOrAnAdministratorAndIsAudited(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            status(201, up(c, "UPL_1.csv", b), "landed");
            status(401, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", null), "no credential");
            status(403, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", VIEWER), "no capability");
            status(403, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", ANALYST), "canManageIncidents is not canUploadData");
            HttpResponse<String> other = call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER2);
            status(404, other, "another analyst's upload answers like an unknown one (no existence oracle)");
            assertEquals(JSON.readTree(call(c, "DELETE", "/inv/uploads/up_feed/UPL_9.csv", UPLOADER2).body()).path("error").path("message").asText(),
                    JSON.readTree(other.body()).path("error").path("message").asText().replace("UPL_1", "UPL_9"), "same words as an unknown file");
            assertEquals(List.of("UPL_1.csv"), inboxFiles(c), "a refused delete removed nothing");
            status(404, call(c, "DELETE", "/inv/uploads/NOPE/UPL_1.csv", UPLOADER), "unknown Collector");
            status(404, call(c, "DELETE", "/inv/uploads/mini_etl/UPL_1.csv", UPLOADER), "a Collector that did not opt in");
            status(404, call(c, "DELETE", "/inv/uploads/up_feed/UPL_9.csv", UPLOADER), "no record of that file");
            status(404, call(c, "DELETE", "/inv/uploads/up_feed/..%2FUPL_1.csv", UPLOADER), "a path-shaped name is never a record");

            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                HttpResponse<String> ok = call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER);
                status(200, ok, "the uploader deletes their own pending file");
                assertTrue(JSON.readTree(ok.body()).get("data").get("deleted").asBoolean());
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals(List.of(), inboxFiles(c), "the file left the inbox");
            assertEquals(0, uploadsOf(c, UPLOADER).size(), "and its record");
            List<Event> mine = seen.stream().filter(e -> EventType.LA_UPLOAD.equals(e.type())).toList();
            assertEquals(1, mine.size(), mine.toString());
            assertTrue(mine.get(0).toString().contains("la.upload.delete"), mine.toString());
            assertEquals("deleted", mine.get(0).attributes().get("outcome"));
            assertEquals(sha256(b), mine.get(0).attributes().get("sha256"));
            status(404, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER), "already gone");

            status(201, up(c, "UPL_2.csv", b), "landed again");
            status(200, call(c, "DELETE", "/inv/uploads/up_feed/UPL_2.csv", ADMIN), "canAdminister may delete another's upload");
            assertEquals(List.of(), inboxFiles(c));
        }
    }

    @Test
    void anUploadTheCollectorHasIngestedIsIngestedNotPendingAndCannotBeDeleted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "upl_del_" + System.nanoTime();
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, true, store)) {
            status(201, up(c, "UPL_20261001.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            c.svc().runPipeline("up_feed");
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertEquals("ingested", row.get("state").asText(), row.toString());
            HttpResponse<String> del = call(c, "DELETE", "/inv/uploads/up_feed/UPL_20261001.csv", UPLOADER);
            status(409, del, "the Collector already picked it up");
            assertTrue(del.body().contains("stay in the Dataset"), del.body());
            assertEquals(1, uploadsOf(c, UPLOADER).size(), "the record stays");
        } finally {
            try (Stream<Path> w = Files.exists(Path.of("database").resolve(store)) ? Files.walk(Path.of("database").resolve(store)) : Stream.<Path>empty()) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    /** Review fix 1: replace=true is the uploader's (or an administrator's) act; it can never take over another's upload. */
    @Test
    void anotherAnalystCannotTakeOverAWaitingUploadWithReplace(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] mine = CSV.getBytes(StandardCharsets.UTF_8);
            byte[] theirs = (CSV + "+447700900009,234109,2026-10-01\n").getBytes(StandardCharsets.UTF_8);
            status(201, up(c, "UPL_1.csv", mine), "A lands");
            HttpResponse<String> takeover = up(c, "up_feed", "?name=UPL_1.csv&replace=true", theirs, UPLOADER2);
            status(403, takeover, "B may not replace A's waiting file");
            assertEquals(CSV, Files.readString(c.inbox().resolve("UPL_1.csv")), "A's bytes are untouched");
            assertEquals(1, uploadsOf(c, UPLOADER).size(), "A still owns it");
            assertEquals(0, uploadsOf(c, UPLOADER2).size());
            status(404, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER2), "and B still cannot delete it");

            status(201, up(c, "up_feed", "?name=UPL_1.csv&replace=true", theirs, ADMIN), "an administrator may replace");
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertEquals("uploader-1", row.get("uploader").asText(), "the record keeps the ORIGINAL uploader on replace");
            assertTrue(row.get("replaced").asBoolean());
            status(201, up(c, "up_feed", "?name=UPL_1.csv&replace=true", mine, UPLOADER), "the owner may replace their own");
        }
    }

    /** Review fix 3a/3b + 4: the name check is its own gate; another spelling of a stored name is not that record. */
    @Test
    void deleteMatchesTheStoredNameExactlyAndRefusesAnUnsafeNameAtItsOwnGate(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(201, up(c, "UPL_2.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            try {
                status(404, call(c, "DELETE", "/inv/uploads/up_feed/upl_2.CSV", UPLOADER), "another spelling is not the record");
                status(404, call(c, "DELETE", "/inv/uploads/up_feed/a%20b.csv", UPLOADER), "outside the name charset");
                status(404, call(c, "DELETE", "/inv/uploads/up_feed/.UPL_2.csv", UPLOADER), "a leading dot");
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            assertEquals(List.of("UPL_2.csv"), inboxFiles(c), "nothing was deleted");
            List<String> gates = seen.stream().filter(e -> EventType.LA_UPLOAD.equals(e.type())).map(e -> e.attributes().get("gate")).toList();
            assertEquals(List.of("record", "name", "name"), gates, "the SAFE_NAME gate refused the last two: " + seen);
        }
    }

    /** Review fix 2b: while a run of the Pipeline holds its claim, the file may be being read - delete refuses (409, audited). */
    @Test
    void deleteRefusesWhileARunOfThePipelineIsInFlight(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(201, up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            HttpResponse<String>[] during = new HttpResponse[1];
            c.svc().whileIdle("up_feed", () -> {      // the test holds the run claim, as an in-flight ingest would
                during[0] = call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER);
                return Boolean.TRUE;
            });
            status(409, during[0], "a run is in flight");
            assertTrue(during[0].body().contains("being ingested"), during[0].body());
            assertEquals(List.of("UPL_1.csv"), inboxFiles(c), "nothing was deleted mid-ingest");
            status(200, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER), "free again: withdrawn");
            try (Stream<Path> s = Files.list(c.inbox())) {
                assertEquals(0, s.count(), "no claimed temp file left behind");
            }
        }
    }

    /** Review fix 2a: a checksum-mode Collector writes no marker and (no backup dir) leaves the file in the inbox. */
    @Test
    void aChecksumModeCollectorsIngestedFileReadsIngestedAndCannotBeDeleted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "upl_cs_" + System.nanoTime();
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        checksumMode = true;
        try (Ctx c = open(cfg, root, true, store)) {
            status(201, up(c, "UPL_20261002.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            assertEquals("pending", uploadsOf(c, UPLOADER).get(0).get("state").asText());
            c.svc().runPipeline("up_feed");
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertEquals("ingested", row.get("state").asText(), "fingerprint ledger says ingested: " + row + " inbox " + inboxFiles(c)
                    + " ledger(test thread) " + com.gamma.acquire.AcquisitionLedgers.shared().find("up_feed", "UPL_20261002.csv")
                    + " store " + Files.exists(Path.of("database").resolve(store)));
            status(409, call(c, "DELETE", "/inv/uploads/up_feed/UPL_20261002.csv", UPLOADER), "picked up: refused");
        } finally {
            checksumMode = false;
            try (Stream<Path> w = Files.exists(Path.of("database").resolve(store)) ? Files.walk(Path.of("database").resolve(store)) : Stream.<Path>empty()) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    @Test
    void theStatusReadIsRateClassed429(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "1");
        System.setProperty("control.rateLimit.linkAnalysis.refillPerSecond", "0.001");
        try (Ctx c = open(cfg, root, true)) {
            assertTrue(call(c, "GET", "/inv/uploads/up_feed", UPLOADER).statusCode() != 429);
            status(429, call(c, "GET", "/inv/uploads/up_feed", UPLOADER), "the one-token bucket is spent");
        }
    }

    @Test
    void capabilityGatesAre401And403AndNothingLands(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            status(401, up(c, "up_feed", "?name=UPL_1.csv", b, null), "no credential");
            status(403, up(c, "up_feed", "?name=UPL_1.csv", b, VIEWER), "no capability at all");
            status(403, up(c, "up_feed", "?name=UPL_1.csv", b, ANALYST), "canManageIncidents is not canUploadData (D-UP3)");
            assertEquals(List.of(), inboxFiles(c), "a refused caller landed nothing");
        }
    }

    @Test
    void noWriteRootIs503(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null, true)) {
            status(503, up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8)), "write root unset");
            assertEquals(List.of(), inboxFiles(c));
        }
    }

    @Test
    void nameIsRequiredAndAPathShapedNameIsAPathJail403(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            status(422, up(c, "up_feed", "", b, UPLOADER), "name missing");
            for (String bad : List.of("..%2FUPL_1.csv", "a%2Fb.csv", "..%5CUPL_1.csv", "C%3AUPL_1.csv", "%2e%2e%2fUPL_1.csv", "UPL..1.csv")) {
                HttpResponse<String> r = up(c, "up_feed", "?name=" + bad, b, UPLOADER);
                status(403, r, "path-shaped name " + bad);
                assertEquals("PATH_JAIL_VIOLATION", errorCode(r));
            }
            assertEquals(List.of(), inboxFiles(c));
            assertFalse(Files.exists(c.inbox().resolveSibling("UPL_1.csv")), "nothing escaped the inbox");
        }
    }

    @Test
    void unknownAndNonUploadCollectorsAreOneAnd404(@TempDir Path cfg, @TempDir Path root) throws Exception {
        byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
        try (Ctx c = open(cfg, root, true)) {
            status(404, up(c, "NOPE", "?name=UPL_1.csv", b, UPLOADER), "unknown Collector");
            status(404, up(c, "mini_etl", "?name=UPL_1.csv", b, UPLOADER), "a Collector that did not opt in");
        }
        try (Ctx c = open(cfg, root, false)) {
            status(404, up(c, "up_feed", "?name=UPL_1.csv", b, UPLOADER), "accepts_upload absent is no upload");
            assertEquals(List.of(), inboxFiles(c));
        }
    }

    @Test
    void nameMustBeSafeCsvAndMatchTheCollectorsPattern422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            status(422, up(c, "UPL%201.csv", b), "a space is outside the charset");
            status(422, up(c, "UPL_1.txt", b), "not .csv");
            status(422, up(c, "UPL_1.xlsx", b), "XLSX is not accepted (D-UP5)");
            status(422, up(c, ".UPL_1.csv", b), "a leading dot");
            HttpResponse<String> other = up(c, "OTHER.csv", b);
            status(422, other, "does not match include glob:**/UPL_*.csv, so it would sit unprocessed");
            assertTrue(other.body().contains("UPL_*.csv"), other.body());
            assertEquals(List.of(), inboxFiles(c));
        }
    }

    @Test
    void contentThatIsNotACsvIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(422, up(c, "UPL_1.csv", new byte[0]), "empty");
            byte[] zip = new byte[]{'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0};
            HttpResponse<String> x = up(c, "UPL_2.csv", zip);
            status(422, x, "an XLSX (zip) container");
            assertTrue(x.body().contains("export the sheet as CSV"), x.body());
            status(422, up(c, "UPL_3.csv", new byte[]{'a', ',', 'b', '\n', 1, 0, 2}), "NUL byte");
            status(422, up(c, "UPL_4.csv", new byte[]{'a', ',', (byte) 0xC3, (byte) 0x28, '\n'}), "not UTF-8");
            assertEquals(List.of(), inboxFiles(c));
        }
    }

    @Test
    void sizeCapIs413OnTheDeclaredLengthAndOnAChunkedBody(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            limits(c, "{\"upload.max_bytes\":1024}");
            byte[] big = ("MSISDN,IMSI,EVENT_DATE\n" + "+447700900001,234101,2026-10-01\n".repeat(60)).getBytes(StandardCharsets.UTF_8);
            assertTrue(big.length > 1024);
            HttpResponse<String> declared = up(c, "UPL_1.csv", big);
            status(413, declared, "declared Content-Length over the cap");
            assertEquals("PAYLOAD_TOO_LARGE", errorCode(declared));
            HttpResponse<String> chunked = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/inv/uploads/up_feed?name=UPL_2.csv")).header("Authorization", UPLOADER)
                    .POST(BodyPublishers.fromPublisher(BodyPublishers.ofByteArray(big))).build(), BodyHandlers.ofString());
            status(413, chunked, "no declared length: refused after at most cap+1 bytes");
            assertEquals(List.of(), inboxFiles(c), "a refused upload landed nothing");
            status(201, up(c, "UPL_3.csv", CSV.getBytes(StandardCharsets.UTF_8)), "a file under the cap lands");
        }
    }

    @Test
    void dailyQuotaIs413AndOnlyLandedBytesCount(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            limits(c, "{\"upload.daily_bytes\":150}");
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);   // ~110 bytes
            assertTrue(b.length > 75 && b.length < 150);
            status(201, up(c, "UPL_1.csv", b), "first file is inside the quota");
            HttpResponse<String> second = up(c, "UPL_2.csv", b);
            status(413, second, "the second would pass upload.daily_bytes");
            assertTrue(second.body().contains("upload.daily_bytes"), second.body());
            assertEquals(List.of("UPL_1.csv"), inboxFiles(c), "the refused file landed nothing");
        }
    }

    @Test
    void theFourEyesRowBudgetRefusesALargeUploadAndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\nfour_eyes_budget_above: 2\n");
            status(201, up(c, "UPL_1.csv", "MSISDN,IMSI,EVENT_DATE\n+447700900001,234101,2026-10-01\n".getBytes(StandardCharsets.UTF_8)),
                    "two lines are within the budget");
            HttpResponse<String> r = up(c, "UPL_2.csv", CSV.repeat(2).getBytes(StandardCharsets.UTF_8));
            status(403, r, "more rows than four_eyes_budget_above");
            assertTrue(r.body().contains("four-eyes"), r.body());
            assertEquals(List.of("UPL_1.csv"), inboxFiles(c));
        }
    }

    @Test
    void aSecondUploadOfTheSameNameIs409UntilReplaceIsTrue(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] first = CSV.getBytes(StandardCharsets.UTF_8);
            byte[] second = (CSV + "+447700900003,234103,2026-10-01\n").getBytes(StandardCharsets.UTF_8);
            JsonNode landed = JSON.readTree(up(c, "UPL_1.csv", first).body()).get("data");
            assertFalse(landed.get("replaced").asBoolean());
            HttpResponse<String> dup = up(c, "UPL_1.csv", second);
            status(409, dup, "same name, no replace");
            assertTrue(dup.body().contains("replace=true"), dup.body());
            assertEquals(CSV, Files.readString(c.inbox().resolve("UPL_1.csv")), "the original is untouched");
            HttpResponse<String> r = up(c, "up_feed", "?name=UPL_1.csv&replace=true", second, UPLOADER);
            status(201, r, "replace=true");
            assertTrue(JSON.readTree(r.body()).get("data").get("replaced").asBoolean());
            assertEquals(new String(second, StandardCharsets.UTF_8), Files.readString(c.inbox().resolve("UPL_1.csv")));
        }
    }

    @Test
    void theLinkAnalysisRateClassThrottlesUploads429(@TempDir Path cfg, @TempDir Path root) throws Exception {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "1");
        System.setProperty("control.rateLimit.linkAnalysis.refillPerSecond", "0.001");
        try (Ctx c = open(cfg, root, true)) {
            HttpResponse<String> first = up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8));
            assertTrue(first.statusCode() != 429, first.body());
            HttpResponse<String> second = up(c, "UPL_2.csv", CSV.getBytes(StandardCharsets.UTF_8));
            status(429, second, "the one-token bucket is spent");
            assertEquals("RATE_LIMITED", errorCode(second));
            assertFalse(inboxFiles(c).contains("UPL_2.csv"));
        }
    }

    @Test
    void aLandedUploadReturnsTheShaAndEmitsOneAuditEventPerAttemptWithoutContent(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            List<Event> seen = new CopyOnWriteArrayList<>();
            Consumer<Event> sub = seen::add;
            EventLog.current().addSubscriber(sub);
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            try {
                HttpResponse<String> ok = up(c, "UPL_1.csv", b);
                status(201, ok, "landed");
                JsonNode data = JSON.readTree(ok.body()).get("data");
                assertEquals("up_feed", data.get("collector").asText());
                assertEquals("UPL_1.csv", data.get("file").asText());
                assertEquals(b.length, data.get("bytes").asInt());
                assertEquals(sha256(b), data.get("sha256").asText());
                assertEquals(CSV, Files.readString(c.inbox().resolve("UPL_1.csv")), "the bytes landed verbatim");
                status(409, up(c, "UPL_1.csv", b), "duplicate: refused and audited");
            } finally {
                EventLog.current().removeSubscriber(sub);
            }
            List<Event> mine = seen.stream().filter(e -> EventType.LA_UPLOAD.equals(e.type())).toList();
            assertEquals(2, mine.size(), "one per attempt: " + mine);
            assertEquals("landed", mine.get(0).attributes().get("outcome"));
            assertEquals(sha256(b), mine.get(0).attributes().get("sha256"));
            assertEquals("refused", mine.get(1).attributes().get("outcome"));
            assertEquals("conflict", mine.get(1).attributes().get("gate"));
            assertFalse(mine.toString().contains("7700900001"), "metadata only, never a row: " + mine);
        }
    }

    /** A reader polling the inbox while a large file lands never sees a partial file under its final name. */
    @Test
    void landingIsAtomicAndLeavesNoTempFileBehind(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            StringBuilder sb = new StringBuilder("MSISDN,IMSI,EVENT_DATE\n");
            String row = "+447700900001,234101,2026-10-01\n";
            while (sb.length() < 8 * 1024 * 1024) sb.append(row);
            byte[] big = sb.toString().getBytes(StandardCharsets.UTF_8);
            AtomicBoolean done = new AtomicBoolean();
            List<String> partial = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                while (!done.get()) {
                    try {
                        Path f = c.inbox().resolve("UPL_BIG.csv");
                        if (Files.exists(f) && Files.size(f) != big.length) partial.add("size " + Files.size(f));
                    } catch (Exception ignored) { /* mid-move: the next poll sees it whole or absent */ }
                }
            });
            reader.start();
            try {
                status(201, up(c, "UPL_BIG.csv", big), "8 MB lands");
            } finally {
                done.set(true);
                reader.join();
            }
            assertEquals(List.of(), partial, "no partial file was ever visible under the final name");
            assertEquals(List.of("UPL_BIG.csv"), inboxFiles(c), "no staging temp file left behind");
            status(422, up(c, "UPL_BAD.csv", new byte[]{1, 0, 2}), "a refusal");
            assertEquals(List.of("UPL_BIG.csv"), inboxFiles(c), "a refusal leaves no temp file either");
        }
    }

    /** Upload, then the Collector's own Pipeline ingests it, then the existing identity import reads the Dataset it filled. */
    @Test
    void uploadThenIngestThenIdentityImportUsesTheExistingChain(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "upl_e2e_" + System.nanoTime();
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, true, store)) {
            Files.writeString(root.resolve("link-analysis.toon"), "masking_mode: none\n");
            status(201, up(c, "UPL_20261001.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            c.svc().runPipeline("up_feed");
            assertEquals(List.of(), inboxFiles(c), "the Collector consumed the upload");
            new ComponentStore(root.resolve("registry")).write("dataset", "ups", Map.of(
                    "physicalRef", store,
                    "owner", "uploader-1", "shares", List.of()));
            HttpResponse<String> imp = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + c.port
                    + "/api/v1/inv/entity-identities/import")).header("Authorization", UPLOADER)
                    .header("Content-Type", "application/json").POST(BodyPublishers.ofString(
                            "{\"dataset\":\"ups\",\"aCol\":\"MSISDN\",\"bCol\":\"IMSI\",\"aType\":\"msisdn\",\"bType\":\"imsi\","
                                    + "\"reason\":\"analyst upload 2026-10\"}")).build(), BodyHandlers.ofString());
            status(201, imp, "identity import over the uploaded-and-ingested Dataset");
            JsonNode d = JSON.readTree(imp.body()).get("data");
            assertEquals(2, d.path("imported").asInt(), imp.body());
        } finally {
            try (Stream<Path> w = Files.exists(Path.of("database").resolve(store)) ? Files.walk(Path.of("database").resolve(store)) : Stream.<Path>empty()) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
            if (!hadDatabase) Path.of("database").toFile().delete();
        }
    }

    private static void wipe(String store, boolean hadDatabase) throws Exception {
        try (Stream<Path> w = Files.exists(Path.of("database").resolve(store)) ? Files.walk(Path.of("database").resolve(store)) : Stream.<Path>empty()) {
            w.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        if (!hadDatabase) Path.of("database").toFile().delete();
    }

    /** LA-CASE-HISTORY-UPLOAD-FOLLOWUPS-1 (2): a picked-up file answers 409 picked-up from the filesystem alone, never taking (or being refused) the run claim. */
    @Test
    void deletingAPickedUpUploadNeverNeedsTheRunClaim(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "upl_pk_" + System.nanoTime();
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        try (Ctx c = open(cfg, root, true, store)) {
            status(201, up(c, "UPL_20261003.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            c.svc().runPipeline("up_feed");
            HttpResponse<String>[] during = new HttpResponse[1];
            c.svc().whileIdle("up_feed", () -> {
                during[0] = call(c, "DELETE", "/inv/uploads/up_feed/UPL_20261003.csv", UPLOADER);
                return Boolean.TRUE;
            });
            status(409, during[0], "picked up");
            assertTrue(during[0].body().contains("stay in the Dataset"), "answered as picked-up, not busy: " + during[0].body());
        } finally {
            wipe(store, hadDatabase);
        }
    }

    /** (3): the picked-up state compares the ledger's recorded file mtime, so a host clock behind the file's mtime does not read pending. */
    @Test
    void aChecksumIngestedFileWhoseMtimeIsAheadOfTheWallClockStillReadsIngested(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String store = "upl_ck_" + System.nanoTime();
        boolean hadDatabase = Files.isDirectory(Path.of("database"));
        checksumMode = true;
        try (Ctx c = open(cfg, root, true, store)) {
            status(201, up(c, "UPL_20261004.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            Files.setLastModifiedTime(c.inbox().resolve("UPL_20261004.csv"),
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(3600)));
            c.svc().runPipeline("up_feed");
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertEquals("ingested", row.get("state").asText(), row + " inbox " + inboxFiles(c));
            status(409, call(c, "DELETE", "/inv/uploads/up_feed/UPL_20261004.csv", UPLOADER), "picked up: refused");
        } finally {
            checksumMode = false;
            wipe(store, hadDatabase);
        }
    }

    /** (4): a stranded .withdraw-*.tmp is swept at the next withdraw; only that name shape, never a folder or a link's target. */
    @Test
    void aWithdrawSweepsStrandedClaimFilesButNothingElse(@TempDir Path cfg, @TempDir Path root, @TempDir Path outside) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(201, up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            Path stranded = c.inbox().resolve(".UPL_0.csv.withdraw-123456.tmp");
            Files.writeString(stranded, "x");
            Path unrelated = c.inbox().resolve(".keep.tmp");
            Files.writeString(unrelated, "x");
            Path folder = c.inbox().resolve(".UPL_D.csv.withdraw-5.tmp");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("inner.txt"), "x");
            Files.writeString(outside.resolve("precious.txt"), "x");
            Path emptyFolder = c.inbox().resolve(".UPL_E.csv.withdraw-8.tmp");
            Files.createDirectories(emptyFolder);
            Path otherCollectors = c.inbox().resolve(".OTHER_1.csv.withdraw-9.tmp");   // not a name THIS Collector's withdraw could have made
            Files.writeString(otherCollectors, "x");
            Path link = c.inbox().resolve(".UPL_L.csv.withdraw-6.tmp");
            boolean linked = false;
            try {
                Files.createSymbolicLink(link, outside);
                linked = true;
            } catch (Exception | Error noSymlink) {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                linked = p.waitFor() == 0;
            }
            status(200, call(c, "DELETE", "/inv/uploads/up_feed/UPL_1.csv", UPLOADER), "withdrawn");
            assertFalse(Files.exists(stranded), "the stranded claim file is swept");
            assertTrue(Files.exists(unrelated), "other names are left alone");
            assertTrue(Files.exists(folder.resolve("inner.txt")), "a folder of that name is not swept into");
            assertTrue(Files.exists(outside.resolve("precious.txt")), "a link is never followed out of the inbox");
            assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link itself is not deleted");
            assertTrue(Files.isDirectory(emptyFolder), "an EMPTY folder of that name survives (only regular files are swept)");
            assertTrue(Files.exists(otherCollectors), "a claim-shaped name this Collector's pattern would not accept is another Pipeline's: left alone");
            assertTrue(linked, "the link fixture was created");
        }
    }

    /** (5): the reject ledger is read up to a bound; past it the count is the bound and the read says so. */
    @Test
    void aHugeRejectLedgerIsCountedUpToABoundAndFlaggedCapped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(201, up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            Path errors = c.inbox().getParent().resolve("errors");
            Files.createDirectories(errors);
            int cap = com.gamma.geolink.HostUploadLandingPort.REJECT_COUNT_CAP;
            StringBuilder sb = new StringBuilder("line,reason\n");
            for (int i = 0; i < cap + 50; i++) sb.append(i).append(",bad\n");
            Files.writeString(errors.resolve("UPL_1_errors.csv"), sb.toString());
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertEquals(cap, row.get("rejectedRows").asLong(), row.toString());
            assertTrue(row.get("rejectedRowsCapped").asBoolean(), row.toString());
            Files.writeString(errors.resolve("UPL_1_errors.csv"), "line,reason\n1,bad\n2,bad\n");
            row = uploadsOf(c, UPLOADER).get(0);
            assertEquals(2, row.get("rejectedRows").asLong());
            assertFalse(row.has("rejectedRowsCapped"), "an exact count carries no flag");
        }
    }

    /** Follow-up: exactly CAP rows then a blank line is an exact count, not capped. */
    @Test
    void aBlankLineAfterTheLastCountedRowDoesNotFlagCapped(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(201, up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            Path errors = c.inbox().getParent().resolve("errors");
            Files.createDirectories(errors);
            int cap = com.gamma.geolink.HostUploadLandingPort.REJECT_COUNT_CAP;
            StringBuilder sb = new StringBuilder("line,reason\n");
            for (int i = 0; i < cap; i++) sb.append(i).append(",bad\n");
            Files.writeString(errors.resolve("UPL_1_errors.csv"), sb + "\n\n");
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertEquals(cap, row.get("rejectedRows").asLong(), row.toString());
            assertFalse(row.has("rejectedRowsCapped"), row.toString());
        }
    }

    /** Follow-up: the BYTES read are bounded too - one enormous line cannot be pulled whole into memory. */
    @Test
    void aSingleHugeLineInTheRejectLedgerIsReadOnlyUpToTheByteBound(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            status(201, up(c, "UPL_1.csv", CSV.getBytes(StandardCharsets.UTF_8)), "landed");
            Path errors = c.inbox().getParent().resolve("errors");
            Files.createDirectories(errors);
            int bound = com.gamma.geolink.HostUploadLandingPort.REJECT_READ_CAP_BYTES;
            Files.writeString(errors.resolve("UPL_1_errors.csv"), "line,reason\n" + "x".repeat(bound + 1024));
            JsonNode row = uploadsOf(c, UPLOADER).get(0);
            assertTrue(row.get("rejectedRowsCapped").asBoolean(), row.toString());
        }
    }

    /** (6): replace=true is a write into the inbox, so it takes the run claim like delete: 409 while a run holds it. */
    @Test
    void replaceTrueRefusesWhileARunOfThePipelineIsInFlight(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root, true)) {
            byte[] b = CSV.getBytes(StandardCharsets.UTF_8);
            byte[] b2 = (CSV + "+447700900009,234109,2026-10-01\n").getBytes(StandardCharsets.UTF_8);
            status(201, up(c, "UPL_1.csv", b), "landed");
            HttpResponse<String>[] during = new HttpResponse[1];
            c.svc().whileIdle("up_feed", () -> {
                during[0] = up(c, "up_feed", "?name=UPL_1.csv&replace=true", b2, UPLOADER);
                return Boolean.TRUE;
            });
            status(409, during[0], "a run is in flight");
            assertEquals(CSV, Files.readString(c.inbox().resolve("UPL_1.csv")), "the file was not replaced mid-ingest");
            status(201, up(c, "up_feed", "?name=UPL_1.csv&replace=true", b2, UPLOADER), "free again: replaced");
        }
    }
}
