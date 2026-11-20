package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.acquire.AcquisitionLedger;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.acquire.DbAcquisitionLedger;
import com.gamma.acquire.InMemoryAcquisitionLedger;
import com.gamma.acquire.LedgerEntry;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code BASIC-ELT-PROFILE-1} B3 part 2 over real HTTP: an Add data import refuses the same bytes by the engine's fingerprint
 * ledger ({@link ImportFingerprints}) - Add a file's 409 across a restart of a durable ledger; a waiting duplicate (watched
 * or Run now) set aside in {@code refused/} with its reason; <i>Replace everything</i> and <i>Replace the period</i> forget
 * the fingerprints of the rows they set aside (and a failed replace puts them back); a new import forgets what an earlier
 * Dataset of its id left; the start-up move of an older import onto the ledger, seeded once from its arrivals. The
 * same-bytes refusal of Add a file itself is {@code ControlApiOnboardFilesTest#theSameFileAddedAgainIsRefusedUntilEverythingIsReplaced}.
 * Synthetic fixtures only.
 */
class ControlApiImportFingerprintsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FileTime SETTLED = FileTime.fromMillis(System.currentTimeMillis() - 120_000L);
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @BeforeEach
    void freshLedger() {
        AcquisitionLedgers.use(new InMemoryAcquisitionLedger());   // the default Space's: no other test's fingerprints
    }

    @AfterEach
    void cleanUp() {
        AcquisitionLedgers.use(null);
        EditionFeatures.overrideForTest(null);
        Path data = Path.of("database").toAbsolutePath();
        for (String id : ids) {
            OnboardRoutes.deleteTree(data.resolve(id));
            OnboardRoutes.deleteTree(data.resolve("inbox").resolve(id));
        }
        OnboardRoutes.deleteTree(data.resolve(OnboardRoutes.DRAFTS));
        for (Path p : List.of(data.resolve("inbox"), data))
            try {
                Files.deleteIfExists(p);
            } catch (java.io.IOException notEmpty) {
                // another fixture's data lives there
            }
    }

    /** A host over {@code cfg}'s fixture Pipeline and {@code more} - as a restart registers what the config root holds. */
    private Ctx open(Path cfg, Path writeRoot, Path... more) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            List<Path> all = new ArrayList<>(List.of(pipe));
            all.addAll(List.of(more));
            CollectorService svc = new CollectorService(all, 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private static Path pipelineOf(Path root, String id) {
        return root.resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> add(Ctx c, String id, String name, String mode, String csv) throws Exception {
        return send(c, "POST", "/datasets/" + id + "/files?name=" + name + "&mode=" + mode, csv.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    private String newId(String base) {
        String id = base + "_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        return id;
    }

    private String imported(Ctx c, String base, String csv) throws Exception {
        String id = newId(base);
        commit(c, id, csv, 200);
        return id;
    }

    private HttpResponse<String> commit(Ctx c, String id, String csv, int expected) throws Exception {
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", csv.getBytes(StandardCharsets.UTF_8)), 200);
        HttpResponse<String> r = send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, r.statusCode(), r.body());
        return r;
    }

    private static String transfers(int rows) {
        return ControlApiOnboardFilesTest.transfers(rows);
    }

    /** The rows of {@code transfers(100)} on 10 - 12 September, in {@code currency}: one file, three days. */
    private static String threeDays(String currency) {
        StringBuilder sb = new StringBuilder();
        for (String line : transfers(100).split("\n")) {
            if (sb.isEmpty()) {
                sb.append(line).append('\n');
                continue;
            }
            if (line.contains(",2026-09-10 ") || line.contains(",2026-09-11 ") || line.contains(",2026-09-12 "))
                sb.append(line.replace(",EUR", "," + currency)).append('\n');
        }
        return sb.toString();
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static long rows(String id) throws Exception {
        return OnboardImport.countRows(Path.of("database", id, "database"));
    }

    private static Path dropSettled(String id, String name, String content) throws Exception {
        Path f = Path.of("database", "inbox", id).resolve(name);
        Files.writeString(f, content);
        Files.setLastModifiedTime(f, SETTLED);
        return f;
    }

    // ── a durable ledger: the refusal outlives a restart ─────────────────────────────────────────────────────────

    @Test
    void theRefusalOfTheSameBytesOutlivesARestartOfTheDurableLedger(@TempDir Path cfg, @TempDir Path root, @TempDir Path db)
            throws Exception {
        String url = "jdbc:duckdb:" + db.resolve("ledger.db").toString().replace('\\', '/');
        AcquisitionLedger ledger = DbAcquisitionLedger.open(url, null, null);
        AcquisitionLedgers.use(ledger);
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "durable", transfers(40));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
        } finally {
            ledger.close();
        }
        AcquisitionLedger reopened = DbAcquisitionLedger.open(url, null, null);   // what a restart opens
        AcquisitionLedgers.use(reopened);
        try (Ctx c = open(cfg, root, pipelineOf(root, id))) {
            HttpResponse<String> again = add(c, id, "copy.csv", "append", transfers(12));
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(error(again).contains("as 'october.csv'") && error(again).contains("replace everything"), again.body());
            assertEquals(52, rows(id), "nothing landed");
        } finally {
            reopened.close();
        }
    }

    @Test
    void theBytesAsSentAreTheFingerprintWhenANewColumnWasLeftOutOfTheLandedCopy(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "leftout", transfers(30));
            String wider = ControlApiArrivalNewColumnsTest.withColumn(transfers(8), "fee", i -> "1." + i);
            data(send(c, "POST", "/datasets/" + id + "/files?name=wider.csv&mode=append&newColumns=leave",
                    wider.getBytes(StandardCharsets.UTF_8)), 200);   // landed without its fee column: other bytes
            HttpResponse<String> again = send(c, "POST", "/datasets/" + id + "/files?name=wider_again.csv&mode=append&newColumns=leave",
                    wider.getBytes(StandardCharsets.UTF_8));
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(error(again).contains("as 'wider.csv'"), again.body());
            assertEquals(38, rows(id));
        }
    }

    // ── a waiting duplicate is set aside, with its reason ────────────────────────────────────────────────────────

    @Test
    void aWatchedDuplicateIsSetAsideInRefusedAndItsArrivalSaysWhy(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of());   // Basic
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "watched", transfers(40));
            data(send(c, "PUT", "/datasets/" + id + "/watch", "{\"watch\":true}".getBytes(StandardCharsets.UTF_8)), 200);
            dropSettled(id, "resent.csv", transfers(40));   // the first file's bytes under another name
            dropSettled(id, "fresh.csv", transfers(6));
            ControlApiImportPreRunStepsTest.poll(c.svc());
            ControlApiImportPreRunStepsTest.poll(c.svc());
            assertEquals(46, rows(id), "the new file taken, the duplicate not");
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "resent.csv")), "set aside, not skipped in the inbox");
            assertEquals(0, data(send(c, "GET", "/datasets/" + id + "/feed", null), 200).get("waiting").asInt(), "nothing left waiting");
            JsonNode refused = null;
            for (JsonNode a : data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals"))
                if ("resent.csv".equals(a.get("file").asText())) refused = a;
            assertNotNull(refused, "the duplicate is an arrival");
            assertEquals(DatasetArrivals.REFUSED, refused.get("status").asText(), refused.toString());
            assertTrue(refused.get("reason").asText().contains("already imported") && refused.get("reason").asText().contains("as 'transfers.csv'"),
                    refused.toString());
            assertEquals(OnboardImport.REFUSED_DIR + "/resent.csv", refused.get("setAside").asText(), refused.toString());
        }
    }

    @Test
    void twoWaitingFilesWithTheSameBytesAreTakenOnce(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "twins", transfers(40));
            dropSettled(id, "a.csv", transfers(7));
            dropSettled(id, "b.csv", transfers(7));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(7, run.get("rowsIn").asLong(), run.toString());
            assertEquals("b.csv", run.at("/refused/0/file").asText(), run.toString());
            assertTrue(run.at("/refused/0/reason").asText().contains("same content as 'a.csv'"), run.toString());
            assertEquals(47, rows(id));
        }
    }

    // ── forgetting what was set aside ────────────────────────────────────────────────────────────────────────────

    @Test
    void aFailedReplaceEverythingPutsTheFingerprintsBack(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "undo", transfers(30));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            // a run that does not happen (a pre-run step holds it): the replace loads nothing and is undone, the old rows and
            // their fingerprints back
            HttpResponse<String> failed;
            CollectorService.preRunStep("t.fp.hold", 200, (svc, p, scheduled) -> id.equals(p)
                    ? CollectorService.PreRun.SKIP : CollectorService.PreRun.GO);
            try {
                failed = add(c, id, "broken.csv", "replace", transfers(5));
            } finally {
                CollectorService.preRunStep("t.fp.hold", 200, (svc, p, scheduled) -> CollectorService.PreRun.GO);
            }
            assertEquals(422, failed.statusCode(), failed.body());
            assertTrue(error(failed).startsWith("nothing was imported from 'broken.csv'"), "it failed after its run: " + failed.body());
            assertEquals(42, rows(id), "the rows are back");
            assertEquals(409, add(c, id, "again.csv", "append", transfers(12)).statusCode(), "and so is October's fingerprint");
            assertEquals(409, add(c, id, "first.csv", "append", transfers(30)).statusCode(), "and the first file's");
            assertEquals(42, data(add(c, id, "broken_again.csv", "append", transfers(5)), 200).get("rowsBefore").asLong(),
                    "the held replace's own upload left no fingerprint behind: the same bytes are accepted");
            // a replace that succeeds (B13: the old rows replaced inside its run) forgets them: October may come back
            data(add(c, id, "november.csv", "replace", transfers(9)), 200);
            assertEquals(21, data(add(c, id, "october.csv", "append", transfers(12)), 200).get("rowsAfter").asLong());
        }
    }

    /** Verifier finding 1: a period run undone after the engine committed the upload takes its rows out, and its fingerprint. */
    @Test
    void anUndonePeriodRunForgetsTheUploadsFingerprint(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = newId("undone");
            commit(c, id, transfers(40), 200);
            ComponentStoreAccess.setEventDate(root, id, "channel");   // a text When column: the period step fails after the run
            String week = transfers(6).replace(",EUR\n", ",CHF\n");
            HttpResponse<String> failed = add(c, id, "week.csv", "period", week);
            assertNotEquals(200, failed.statusCode(), failed.body());
            assertEquals(40, rows(id), "the upload's rows are gone");
            assertEquals(46, data(add(c, id, "week_again.csv", "append", week), 200).get("rowsAfter").asLong(),
                    "so its bytes are not refused as already imported");
        }
    }

    @Test
    void replaceThePeriodForgetsTheArrivalsWhoseDaysItReplacedWhole(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = newId("period");
            commit(c, id, transfers(100), 200);
            ComponentStoreAccess.setEventDate(root, id, "booked_at");
            String jpy = threeDays("JPY");
            JsonNode appended = data(add(c, id, "three_days.csv", "append", jpy), 200);
            assertEquals(12, appended.get("rowsIn").asLong(), "rows read by the append (re-review LOW 3): " + appended);
            long held = rows(id);
            // a correction of the same three days: the three-day file's rows all go, the first file keeps its other days
            JsonNode s = data(add(c, id, "correction.csv", "period", threeDays("CHF")), 200);
            assertEquals(12, s.get("rowsIn").asLong(), "rows read by the period run (re-review LOW 3): " + s);
            assertTrue(s.at("/period/rowsReplaced").asLong() > 0, s.toString());
            long after = s.get("rowsAfter").asLong();
            assertEquals(held - 12, after, "the three days once, not three times: " + s);
            JsonNode back = data(add(c, id, "three_days_again.csv", "append", jpy), 200);
            assertEquals(12, back.get("rowsIn").asLong(), "rows read when it comes back: " + back);
            assertEquals(after + 12, back.get("rowsAfter").asLong(), "its rows were all replaced, so it may come back");
            HttpResponse<String> first = add(c, id, "first_again.csv", "append", transfers(100));
            assertEquals(409, first.statusCode(), "the first file keeps its other days, so its bytes are still refused: " + first.body());
        }
    }

    @Test
    void aNewImportForgetsTheFingerprintsAnEarlierDatasetOfItsIdLeft(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = newId("reborn");
            String csv = transfers(25);
            AcquisitionLedgers.shared().record(new LedgerEntry(id, "add_old_transfers.csv", "add_old_transfers.csv", 1, sha256(csv),
                    null, null, 1L, 1L, LedgerEntry.PROCESSED));
            commit(c, id, csv, 200);
            assertEquals(25, rows(id));
        }
    }

    // ── an import made before: moved onto the ledger at start, seeded once ───────────────────────────────────────

    @Test
    void anOlderImportIsMovedOntoTheLedgerAndSeededFromItsArrivalsOnce(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "older", transfers(40));
        }
        // as B3 part 1 left it: the Pipeline on the path default, the ledger knowing nothing (it lived in memory)
        Path pipeline = pipelineOf(root, id);
        String toon = Files.readString(pipeline);
        assertTrue(toon.contains("mode: content"), toon);
        Files.writeString(pipeline, toon.replace("mode: content", "mode: path"));
        // a drop the path check took: its processed marker is all that is left of it (no hash was ever kept)
        Path marker = Path.of("database", id, "markers", "drop.csv.processed");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "");
        AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        try (Ctx c = open(cfg, root, pipeline)) {
            assertTrue(Files.readString(pipeline).contains("mode: content"), "moved onto the ledger at start");
            assertEquals(2, AcquisitionLedgers.shared().entries(id).size(), "seeded from the arrivals sidecar and the marker");
            HttpResponse<String> again = add(c, id, "copy.csv", "append", transfers(40));
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(error(again).contains("as 'transfers.csv'"), again.body());
            // the drop sent again under its name: set aside as already imported, not read now that the marker no longer counts
            dropSettled(id, "drop.csv", transfers(3));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals("drop.csv", run.at("/refused/0/file").asText(), run.toString());
            assertTrue(run.at("/refused/0/reason").asText().contains("named 'drop.csv' was already imported"), run.toString());
            assertEquals(40, rows(id));
        }
        try (Ctx c = open(cfg, root, pipeline)) {
            assertEquals(2, AcquisitionLedgers.shared().entries(id).size(), "seeded once: an import with fingerprints is left alone");
        }
    }

    // ── a name taken with other bytes; the Pipeline's own settings; an import kept on path ──────────────────────

    /** Reviewer's probe (B3 part 2): a second file under a name the import took is set aside, never read over the first. */
    @Test
    void aNameTakenWithOtherBytesIsSetAsideNotReadOverTheFirst(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "renamed", transfers(40));
            assertTrue(Files.readString(pipelineOf(root, id)).contains("on_change: ignore"), "the Pipeline's own setting");
            dropSettled(id, "feed.csv", transfers(7));
            assertEquals(47, data(send(c, "POST", "/datasets/" + id + "/run", null), 200).get("rowsAfter").asLong());
            dropSettled(id, "feed.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(47, rows(id), "the first feed.csv keeps its 7 rows: " + run);
            assertEquals("feed.csv", run.at("/refused/0/file").asText(), run.toString());
            String reason = run.at("/refused/0/reason").asText();
            assertTrue(reason.contains("named 'feed.csv' was already imported") && reason.contains("rename it"), reason);
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "feed.csv")), "set aside, not lost");
            assertEquals(0, data(send(c, "GET", "/datasets/" + id + "/feed", null), 200).get("waiting").asInt(), "nor left waiting");
            JsonNode arrival = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals").get(0);
            assertEquals(DatasetArrivals.REFUSED, arrival.get("status").asText(), arrival.toString());
            assertEquals("feed.csv", arrival.get("file").asText(), arrival.toString());
        }
    }

    /** {@code on_change} is the Pipeline's own setting: set to {@code reprocess}, a reused name's new bytes are read. */
    @Test
    void onChangeReprocessSetInThePipelineReadsAReusedName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "reprocess", transfers(40));
        }
        Path pipeline = pipelineOf(root, id);
        Files.writeString(pipeline, Files.readString(pipeline).replace("on_change: ignore", "on_change: reprocess"));
        try (Ctx c = open(cfg, root, pipeline)) {
            dropSettled(id, "feed.csv", transfers(7));
            data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            dropSettled(id, "feed.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(5, run.get("rowsIn").asLong(), run.toString());
            assertFalse(run.has("refused"), "the person chose to read it: " + run);
        }
    }

    /** Re-review probe: feed.csv and later/feed.csv waiting in ONE run - the second is set aside, no row is lost. */
    @Test
    void twoWaitingFilesWithOneNameInOneRunAreNotWrittenOverEachOther(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "onerun", transfers(40));
            dropSettled(id, "feed.csv", transfers(7));
            Files.createDirectories(Path.of("database", "inbox", id, "later"));
            dropSettled(id, "later/feed.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(7, run.get("rowsIn").asLong(), run.toString());
            assertEquals(47, rows(id), "the first feed.csv keeps all 7 rows: " + run);
            assertEquals("feed.csv", run.at("/refused/0/file").asText(), run.toString());
            assertTrue(run.at("/refused/0/reason").asText().contains("'feed.csv' has the same file name"), run.toString());
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "feed.csv")), "set aside, not lost");
            JsonNode arrivals = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals");
            assertTrue(arrivals.toString().contains("\"status\":\"REFUSED\"") && arrivals.toString().contains("same file name"),
                    "the arrivals say why: " + arrivals);
        }
    }

    /** Re-review LOW: a marker written before a Replace everything names rows that were set aside, so it is not seeded. */
    @Test
    void markersFromBeforeAReplaceEverythingAreNotSeeded(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "replaced", transfers(40));
            dropSettled(id, "ride.csv", transfers(4));   // a drop the replace's own run takes along
            data(add(c, id, "fresh.csv", "replace", transfers(9)), 200);
        }
        Path pipeline = pipelineOf(root, id);
        Files.writeString(pipeline, Files.readString(pipeline).replace("mode: content", "mode: path"));
        Path markers = Files.createDirectories(Path.of("database", id, "markers"));
        Files.writeString(markers.resolve("old.csv.processed"), "");
        Files.setLastModifiedTime(markers.resolve("old.csv.processed"), SETTLED);   // before the replace
        Files.writeString(markers.resolve("new.csv.processed"), "");
        Files.setLastModifiedTime(markers.resolve("new.csv.processed"), FileTime.fromMillis(System.currentTimeMillis() + 60_000L));
        // written during the replace's run, so before its arrivals line is stamped: still that run's file, its rows held
        Files.writeString(markers.resolve("ride.csv.processed"), "");
        Files.setLastModifiedTime(markers.resolve("ride.csv.processed"), SETTLED);
        AcquisitionLedgers.use(new InMemoryAcquisitionLedger());
        try (Ctx c = open(cfg, root, pipeline)) {
            java.util.Set<String> seeded = new java.util.HashSet<>();
            for (LedgerEntry e : AcquisitionLedgers.shared().entries(id)) seeded.add(e.relativePath());
            assertTrue(seeded.contains("new.csv"), seeded.toString());
            assertFalse(seeded.contains("old.csv"), "its rows were set aside by the replace: " + seeded);
            assertTrue(seeded.contains("ride.csv"), "taken by the replace's own run: " + seeded);
            assertEquals(3, seeded.size(), "the replace's own upload, ride.csv and new.csv: " + seeded);
        }
    }

    /**
     * Re-review: the name is the engine's output name - the stem without folder or extension, case-folded - so
     * {@code Feed.CSV} after {@code feed.csv} (across runs) and {@code later/Feed.csv} beside {@code feed.csv} (one run)
     * are set aside: either would be written over {@code feed.csv}'s rows.
     */
    @Test
    void aNameIsTheEnginesOutputNameWhateverItsCaseOrExtension(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "cased", transfers(40));
            dropSettled(id, "feed.csv", transfers(7));
            Files.createDirectories(Path.of("database", "inbox", id, "later"));
            dropSettled(id, "later/Feed.csv", transfers(5));
            JsonNode one = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(7, one.get("rowsIn").asLong(), one.toString());
            assertEquals(47, rows(id), "one run: feed.csv keeps its rows: " + one);
            assertEquals("Feed.csv", one.at("/refused/0/file").asText(), one.toString());
            dropSettled(id, "FEED.CSV", transfers(3));
            JsonNode across = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(47, rows(id), "across runs: " + across);
            assertEquals("FEED.CSV", across.at("/refused/0/file").asText(), across.toString());
            assertTrue(across.at("/refused/0/reason").asText().contains("named 'feed.csv' was already imported"), across.toString());
        }
    }

    /**
     * Re-review: only a file every refusing step passed counts as taken. {@code later/feed.csv} is set aside for its name, so
     * {@code zother.csv} with its bytes is no twin of anything and is read: 7 + 5 rows, nothing lost, nothing doubled.
     */
    @Test
    void aFileSetAsideForItsNameMakesNoOtherFileItsTwin(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "twins_after", transfers(40));
            dropSettled(id, "feed.csv", transfers(7));
            Files.createDirectories(Path.of("database", "inbox", id, "later"));
            dropSettled(id, "later/feed.csv", transfers(5));
            dropSettled(id, "zother.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(12, run.get("rowsIn").asLong(), run.toString());
            assertEquals(52, rows(id), run.toString());
            assertEquals(1, run.get("refused").size(), "only later/feed.csv: " + run);
            assertEquals("feed.csv", run.at("/refused/0/file").asText(), run.toString());
            assertTrue(run.at("/refused/0/reason").asText().contains("'feed.csv' has the same file name")
                    && run.at("/refused/0/reason").asText().contains("'later/feed.csv' is not read over it"), run.toString());
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "feed.csv")));
        }
    }

    /**
     * Re-review: with {@code processing.output_per_member} on, the engine names each output by its stem and a hash of its
     * path, so {@code feed.csv} and {@code later/feed.csv} do not collide: both are read.
     */
    @Test
    void withOutputPerMemberTwoFoldersKeepTheirOwnName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "permember", transfers(40));
        }
        Path pipeline = pipelineOf(root, id);
        String toon = Files.readString(pipeline);
        assertTrue(toon.contains("processing:\n"), toon);
        Files.writeString(pipeline, toon.replace("processing:\n", "processing:\n  output_per_member: true\n"));
        try (Ctx c = open(cfg, root, pipeline)) {
            assertTrue(c.svc().configFor(id).orElseThrow().outputPerMember(), "the option is on");
            dropSettled(id, "feed.csv", transfers(7));
            Files.createDirectories(Path.of("database", "inbox", id, "later"));
            dropSettled(id, "later/feed.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(12, run.get("rowsIn").asLong(), run.toString());
            assertEquals(52, rows(id), run.toString());
            assertFalse(run.has("refused"), run.toString());
        }
    }

    /**
     * Re-review: a file the column check refuses claims no name, so the valid {@code later/feed.csv} beside a
     * wrong-columns {@code feed.csv} is taken (before, the name was claimed first and the valid file refused).
     */
    @Test
    void aFileTheColumnCheckRefusesClaimsNoName(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "ordered", transfers(40));
            dropSettled(id, "feed.csv", "colour,shape\nred,square\n");
            Files.createDirectories(Path.of("database", "inbox", id, "later"));
            dropSettled(id, "later/feed.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(5, run.get("rowsIn").asLong(), run.toString());
            assertEquals(45, rows(id));
            assertEquals(1, run.get("refused").size(), run.toString());
            assertTrue(run.at("/refused/0/reason").asText().contains("does not have the columns"), run.toString());
        }
    }

    /**
     * The engine writes one output per file name, so {@code later/feed.csv} would be written over {@code feed.csv}'s rows
     * (found building this lane: 112 rows became 106): a taken name is refused in any folder.
     */
    @Test
    void aTakenNameIsSetAsideInAnotherFolderToo(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "folders", transfers(40));
            dropSettled(id, "feed.csv", transfers(7));
            assertEquals(47, data(send(c, "POST", "/datasets/" + id + "/run", null), 200).get("rowsAfter").asLong());
            Files.createDirectories(Path.of("database", "inbox", id, "later"));
            dropSettled(id, "later/feed.csv", transfers(5));
            JsonNode run = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(47, rows(id), "the first feed.csv keeps its rows: " + run);
            assertTrue(run.at("/refused/0/reason").asText().contains("named 'feed.csv' was already imported"), run.toString());
        }
    }

    /** Operator 2026-11-20: an import its approval policy keeps on the path check still refuses the same bytes. */
    @Test
    void anImportKeptOnThePathCheckStillRefusesTheSameBytes(@TempDir Path cfg, @TempDir Path root) throws Exception {
        String id;
        try (Ctx c = open(cfg, root)) {
            id = imported(c, "kept", transfers(40));
        }
        Path pipeline = pipelineOf(root, id);
        Files.writeString(pipeline, Files.readString(pipeline).replace("mode: content", "mode: path"));
        Files.writeString(root.resolve(ApprovalPolicy.FILE), "approval:\n  pipeline:\n    required: true\n");
        try (Ctx c = open(cfg, root, pipeline)) {
            assertTrue(Files.readString(pipeline).contains("mode: path"), "the policy kept the Pipeline as it was");
            assertEquals(409, add(c, id, "first.csv", "append", transfers(40)).statusCode(), "the engine's fingerprint");
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            HttpResponse<String> again = add(c, id, "copy.csv", "append", transfers(12));
            assertEquals(409, again.statusCode(), again.body());
            assertTrue(error(again).contains("as 'october.csv'"), again.body());
            assertEquals(52, rows(id));
        }
    }

    /**
     * A waiting file carrying the bytes of an arrival a Replace everything run replaces is set aside, not read beside it: a
     * failed replacement is undone for the upload's own Consignment only, so such a file would stay loaded next to the
     * restored original (reviewer's probe: 54 rows instead of 42). Sent again after the replace it is accepted.
     */
    @Test
    void replaceEverythingSetsAsideAWaitingFileWithTheBytesItReplaces(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "rewaiting", transfers(30));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            dropSettled(id, "resent.csv", transfers(12));
            JsonNode s = data(add(c, id, "november.csv", "replace", transfers(9)), 200);
            assertEquals(9, s.get("rowsAfter").asLong(), s.toString());
            assertEquals("resent.csv", s.at("/refused/0/file").asText(), s.toString());
            String reason = s.at("/refused/0/reason").asText();
            assertTrue(reason.contains("this Replace everything run replaces that arrival") && reason.contains("can be sent again after the replace")
                    && !reason.contains("rename"), reason);
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "resent.csv")), "set aside, not lost");
            // after the replace the same bytes are new again: sent once more, they are read
            dropSettled(id, "resent_again.csv", transfers(12));
            JsonNode again = data(send(c, "POST", "/datasets/" + id + "/run", null), 200);
            assertEquals(12, again.get("rowsIn").asLong(), again.toString());
            assertEquals(21, rows(id));
        }
    }

    /**
     * Reviewer's probe, held run: the Replace everything fails and its old rows stand. ⚠ Only the {@code refused/} assertion
     * discriminates here - the run is held, so the waiting file is never read either way (42 rows with or without the rule);
     * {@link #aReplacementThatFailsDoesNotDoubleAWaitingResend} is the one where a failed replacement leaves the rows of a
     * waiting file the run did read.
     */
    @Test
    void aFailedReplaceEverythingDoesNotDoubleAWaitingResend(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "b13double", transfers(30));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            dropSettled(id, "resent.csv", transfers(12));
            HttpResponse<String> failed;
            CollectorService.preRunStep("t.fp.hold2", 200, (svc, p, scheduled) -> id.equals(p)
                    ? CollectorService.PreRun.SKIP : CollectorService.PreRun.GO);
            try {
                failed = add(c, id, "broken.csv", "replace", transfers(5));
            } finally {
                CollectorService.preRunStep("t.fp.hold2", 200, (svc, p, scheduled) -> CollectorService.PreRun.GO);
            }
            assertEquals(422, failed.statusCode(), failed.body());
            assertEquals(42, rows(id), "the old rows stand, and the waiting re-send was not read beside them");
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "resent.csv")), "it waits in refused/");
            JsonNode arrivals = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals");
            assertTrue(arrivals.toString().contains("this Replace everything run replaces that arrival"), arrivals.toString());
        }
    }

    /**
     * A Replace everything whose upload carries the bytes of a held arrival, with a waiting copy of them: the copy is the
     * upload's twin ("same content as the upload, which this run takes") - not "replaced, send it again after", which would be
     * false, since the replace loads those bytes.
     */
    @Test
    void aWaitingCopyOfTheUploadsOwnBytesIsItsTwinInAReplaceEverything(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "twinreplace", transfers(30));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            dropSettled(id, "copy.csv", transfers(12));
            JsonNode s = data(add(c, id, "october_again.csv", "replace", transfers(12)), 200);
            assertEquals(12, s.get("rowsAfter").asLong(), s.toString());
            assertEquals("copy.csv", s.at("/refused/0/file").asText(), s.toString());
            String reason = s.at("/refused/0/reason").asText();
            assertTrue(reason.contains("same content as 'october_again.csv', which this run takes") && !reason.contains("rename")
                    && !reason.contains("sent again"), reason);
        }
    }

    /**
     * The replacement itself fails (the old Parquet files are held open, so the journalled swap cannot move them): the upload's
     * Consignment is undone, a genuinely new waiting file the run read stays (42 + 3 = 45), and a waiting re-send of a held
     * arrival was never read (it would make 57) - it waits in {@code refused/}. Needs a file system that refuses to move an open
     * file (Windows); elsewhere the replacement succeeds and the test is skipped.
     */
    @Test
    void aReplacementThatFailsDoesNotDoubleAWaitingResend(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "b13lock", transfers(30));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            dropSettled(id, "resent.csv", transfers(12));                 // the bytes of a held arrival
            dropSettled(id, "fresh.csv", transfers(3).replace("T0000", "N0000"));   // genuinely new rows
            List<java.io.Closeable> held = new ArrayList<>();
            try (var files = Files.walk(Path.of("database", id, "database"))) {
                for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".parquet")).toList())
                    held.add(new java.io.FileInputStream(p.toFile()));
            }
            HttpResponse<String> failed;
            try {
                failed = add(c, id, "november.csv", "replace", transfers(9));
            } finally {
                for (java.io.Closeable h : held) h.close();
            }
            assertTrue(Files.exists(Path.of("database", id, OnboardImport.REFUSED_DIR, "resent.csv")), "the re-send waits in refused/");
            org.junit.jupiter.api.Assumptions.assumeTrue(failed.statusCode() != 200,
                    "this file system moved an open file: the replacement did not fail");
            assertEquals(45, rows(id), "the old rows and the new waiting file; the re-send was not read: " + failed.statusCode() + " " + failed.body());
        }
    }

    /**
     * Once a Replace everything is final (its replacement made so), a failure in what follows must not undo what is kept: the
     * upload's fingerprint stays, and the replaced rows' fingerprints are not recorded again.
     */
    @Test
    void aFailureAfterTheReplacementIsFinalUndoesNothingItKept(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, "afterfinal", transfers(30));
            data(add(c, id, "october.csv", "append", transfers(12)), 200);
            OnboardImport.followOnsForTest(List.of(new com.gamma.onboard.ImportFollowOn() {
                public String key() { throw new IllegalStateException("probe"); }
                public String does() { return "fails"; }
                public java.util.Set<String> needs() { return java.util.Set.of(); }
                public java.util.Map<String, Object> run(Context ctx) { throw new IllegalStateException("probe"); }
            }));
            HttpResponse<String> failed;
            try {
                failed = add(c, id, "november.csv", "replace", transfers(9));
            } finally {
                OnboardImport.followOnsForTest(null);
            }
            assertEquals(500, failed.statusCode(), "the follow-on's failure escaped: " + failed.body());
            assertEquals(9, rows(id), "the replacement is final");
            assertEquals(409, add(c, id, "november_again.csv", "append", transfers(9)).statusCode(),
                    "the upload's fingerprint was kept with its rows");
            assertEquals(21, data(add(c, id, "october_again.csv", "append", transfers(12)), 200).get("rowsAfter").asLong(),
                    "the replaced rows' fingerprints were not recorded again");
        }
    }

    /** The Dataset entry's When column, written as the Dataset editor does. */
    private static final class ComponentStoreAccess {
        static void setEventDate(Path root, String id, String column) throws Exception {
            com.gamma.pipeline.ComponentStore store = new com.gamma.pipeline.ComponentStore(root.resolve("registry"));
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>(store.get("dataset", id).orElseThrow().content());
            entry.remove("name");
            entry.put("eventDate", column);
            store.write("dataset", id, entry);
        }
    }
}
