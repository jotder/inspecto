package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.consignment.ConsignmentOutput;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /runs/{id}/reprocess} refused over real HTTP (board {@code REPROCESS-REFUSAL-500-1}, operator ruling
 * 2026-11-20; {@code BASIC-ELT-PROFILE-1} B4): a Consignment whose rows a later arrival replaced - <i>Replace the
 * period</i> rewrote its file without the days the arrival covers, or <i>Replace everything</i> set it aside - is marked
 * {@code REPLACED} in the Consignment output registry, so a reprocess is answered {@code 409} with a reason that names no
 * server path, and the replaced rows stay replaced. The rewritten file stays readable. The route's other gates (400, 404)
 * and the happy path of a Consignment nothing replaced. The compacted-away case is
 * {@code ControlApiDatasetCompactionTest#aReprocessOfAMergedArrivalIsRefusedNotDoubled}.
 */
class ControlApiReprocessRefusalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private final List<String> ids = new ArrayList<>();

    private record Ctx(CollectorService svc, ControlApi api, int port, Path root) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    @AfterEach
    void cleanUp() {
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

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), writeRoot);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? (method.equals("GET") ? b.GET() : b.method(method, BodyPublishers.noBody()))
                : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> json(Ctx c, String method, String path, String body) throws Exception {
        return send(c, method, path, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return V1Body.of(r.body());
    }

    private static String error(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body()).path("error").path("message").asText();
    }

    /** {@code rows} transfers booked on 2026-09-{@code day}, without the header line when {@code header} is false. */
    private static String transfers(int day, int rows, boolean header) {
        String all = ControlApiDatasetCompactionTest.transfersOn(String.format("2026-09-%02d", day), rows);
        return header ? all : all.substring(all.indexOf('\n') + 1);
    }

    private String imported(Ctx c, String content) throws Exception {
        String id = "reprocess_" + UUID.randomUUID().toString().substring(0, 8);
        ids.add(id);
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", content.getBytes(StandardCharsets.UTF_8)), 200);
        data(json(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}"), 200);
        return id;
    }

    private JsonNode add(Ctx c, String id, String name, String mode, String content) throws Exception {
        return data(send(c, "POST", "/datasets/" + id + "/files?name=" + name + (mode == null ? "" : "&mode=" + mode),
                content.getBytes(StandardCharsets.UTF_8)), 200);
    }

    private List<String> commits(Ctx c, String id) throws Exception {
        List<String> out = new ArrayList<>();
        data(json(c, "GET", "/runs/" + id + "/commits", null), 200).forEach(b -> out.add(b.asText()));
        return out;
    }

    /** The Dataset's rows as a read sees them ({@code GET /datasets/{id}/rows}: through the registry's readable files). */
    private int rowsRead(Ctx c, String id) throws Exception {
        return data(json(c, "GET", "/datasets/" + id + "/rows?limit=10000", null), 200).get("rows").size();
    }

    private static List<ConsignmentOutput.State> states(String consignment) {
        return ConsignmentOutputStores.shared().outputs(consignment).stream().map(ConsignmentOutput::state).toList();
    }

    /** A refused reprocess: 409, the reason without a server path, nothing moved. */
    private void assertRefused(Ctx c, String id, String batch, String says, int rows) throws Exception {
        HttpResponse<String> r = json(c, "POST", "/runs/" + id + "/reprocess", "{\"batchId\":\"" + batch + "\"}");
        assertEquals(409, r.statusCode(), r.body());
        assertTrue(error(r).contains(says) && error(r).contains(batch), r.body());
        String escaped = Path.of("database").toAbsolutePath().toString().replace("\\", "\\\\");
        assertFalse(r.body().contains(".parquet") || r.body().contains(escaped) || r.body().contains(c.root.getFileName().toString()),
                "no server path: " + r.body());
        assertEquals(rows, rowsRead(c, id), "no replaced row came back");
    }

    @Test
    void anArrivalReplaceThePeriodRewroteIsRefusedAndItsKeptRowsStayReadable(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, transfers(1, 5, true) + transfers(2, 5, false));   // the 1st and the 2nd
            String first = commits(c, id).get(0);
            JsonNode s = add(c, id, "first_again.csv", "period", transfers(1, 3, true));   // corrects the 1st
            assertEquals(5, s.at("/period/rowsReplaced").asLong(), s.toString());
            assertEquals(8, rowsRead(c, id), "the first file, rewritten, still serves the 2nd: it is REPLACED, not unreadable");
            assertTrue(states(first).stream().allMatch(st -> st == ConsignmentOutput.State.REPLACED), states(first).toString());

            assertRefused(c, id, first, "replaced by a later arrival", 8);
        }
    }

    @Test
    void anArrivalReplaceEverythingSetAsideIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, transfers(1, 5, true));
            String first = commits(c, id).get(0);
            add(c, id, "fresh.csv", null, transfers(2, 3, true));   // no mode: Replace everything
            assertEquals(3, rowsRead(c, id));
            assertTrue(states(first).stream().allMatch(st -> st == ConsignmentOutput.State.REPLACED), states(first).toString());

            assertRefused(c, id, first, "replaced by a later arrival", 3);
        }
    }

    /**
     * A <i>Replace the period</i> the process died in before the import was decided (its journal left behind) is finished
     * by the Dataset's next run ({@code ImportPreRunSteps.runAll} heals first): the originals go, the replaced arrival is
     * marked, so its reprocess is refused too.
     */
    @Test
    void theNextRunFinishesAReplaceACrashInterrupted(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, transfers(1, 5, true) + transfers(2, 5, false));
            String first = commits(c, id).get(0);
            add(c, id, "first_again.csv", "append", transfers(1, 3, true));
            Path dataRoot = Path.of("database").toAbsolutePath(), db = dataRoot.resolve(id).resolve("database");
            Path firstOut = Path.of(ConsignmentOutputStores.shared().outputs(first).get(0).path()).toAbsolutePath().normalize();
            Path upload = com.gamma.job.ArrivalCompactor.parquetFiles(db).stream().filter(p -> !p.equals(firstOut)).findFirst().orElseThrow();
            // the replace ran, then the process died: no drop, no restore
            assertEquals(5, new com.gamma.job.ArrivalReplacer(dataRoot, id, "crashed1")
                    .replacePeriod(List.of(upload), List.of(upload), "booked_at").rowsRemoved());
            Path journal = dataRoot.resolve(id).resolve(com.gamma.job.ArrivalReplacer.JOURNAL);
            assertTrue(Files.exists(journal));
            // no follow_on step (its compaction heals too): only the heal before the run can finish it
            Path pipeline = c.root().resolve(OnboardImport.CONFIG_DIR).resolve(id + "_pipeline.toon");
            List<String> lines = new ArrayList<>();
            for (String l : Files.readAllLines(pipeline))
                lines.add(l.trim().startsWith("import_steps") ? l.replace(",follow_on", "").replace("[4]", "[3]") : l);
            Files.write(pipeline, lines);
            assertFalse(Files.readString(pipeline).contains("follow_on"), Files.readString(pipeline));

            data(json(c, "POST", "/datasets/" + id + "/run", null), 200);   // Run now
            assertFalse(Files.exists(journal), "the run finished the replace first");
            assertFalse(Files.exists(dataRoot.resolve(id).resolve("database.period-crashed1")), "the originals are gone");
            assertTrue(states(first).stream().allMatch(st -> st == ConsignmentOutput.State.REPLACED), states(first).toString());
            assertRefused(c, id, first, "replaced by a later arrival", 8);
        }
    }

    @Test
    void theRoutesOtherGatesAndAnArrivalNothingReplacedStillReprocesses(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String id = imported(c, transfers(1, 5, true));
            String first = commits(c, id).get(0);
            add(c, id, "second.csv", "append", transfers(2, 4, true));
            assertEquals(400, json(c, "POST", "/runs/" + id + "/reprocess", "{}").statusCode());
            assertEquals(404, json(c, "POST", "/runs/no_such_pipeline/reprocess", "{\"batchId\":\"" + first + "\"}").statusCode());
            assertTrue(states(first).stream().allMatch(st -> st == ConsignmentOutput.State.LIVE), states(first).toString());

            HttpResponse<String> r = json(c, "POST", "/runs/" + id + "/reprocess", "{\"batchId\":\"" + first + "\"}");
            assertEquals("reprocessed", data(r, 200).get("status").asText(), r.body());
            assertEquals(9, rowsRead(c, id), "the arrival's rows read again, not doubled");
        }
    }
}
