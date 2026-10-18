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
                  backup: %s/backup
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
                    enabled: true
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
                d, d, d, d, d, d, d, d, acceptsUpload ? "  accepts_upload: true" : ""));
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
}
