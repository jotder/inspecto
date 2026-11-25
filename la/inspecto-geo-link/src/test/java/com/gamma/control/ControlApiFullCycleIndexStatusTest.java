package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.LaCorpus;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code LA-FULL-CYCLE-TEST-1} phase 4 (finding 21) over real HTTP: a healthy fresh import leaves the link index current, and
 * the one staleness answer ({@code IndexStaleness}, read by the Data page, the Investigation header and the Expand) says so.
 *
 * <p><b>The cause found.</b> The test drive saw {@code relation_sql_changed} after the details file and suspected that import.
 * It was not the details import (a Dataset with no link mapping is SKIPPED by the index follow-on): the index was stale the
 * moment it was published, in every Space but the default one. The build renders the Dataset's relation again on the index
 * build worker, and that worker carried no Space: {@code ConsignmentSelector} found no Consignment registry for it and read
 * the store as a glob, while every reader in the Space reads a pinned file list. The manifest therefore recorded the hash of
 * another relation. Single-Space tests could not see it, because there the worker's fallback Space IS the Space. Fixed in
 * {@code IndexBuildService}: a build runs under the Space that submitted it.
 *
 * <p>The full-cycle fixture ({@code inspecto/src/test/resources/onboard/full-cycle/}) is imported into a Space of its own,
 * as a person who creates a Space does; the showcase Space is laid down as {@code tools/seed-la-showcase.mjs --scaffold}
 * lays it, and its own {@code la.index.build} Job builds the index on the Pipeline's commit.
 */
class ControlApiFullCycleIndexStatusTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SHOWCASE = Path.of("..", "..", "spaces", "_templates", "la-showcase").toAbsolutePath().normalize();
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
        System.clearProperty("service.poll.seconds");
    }

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            spaces.close();
        }
    }

    private static Ctx boot(Path spacesRoot) throws Exception {
        SpaceManager spaces = SpaceManager.discover(spacesRoot);
        ControlApi api = new ControlApi(spaces, 0);
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? b.GET() : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return EdgeWireTestRows.decoded(JSON.readTree(r.body()).get("data"));
    }

    private JsonNode post(Ctx c, String path, String body, int expected) throws Exception {
        return data(send(c, "POST", path, body.getBytes(StandardCharsets.UTF_8)), expected);
    }

    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("..", "..", "inspecto", "src", "test", "resources", "onboard", "full-cycle", name));
    }

    private JsonNode commit(Ctx c, String space, String file, String kind, String id) throws Exception {
        JsonNode d = data(send(c, "POST", "/spaces/" + space + "/onboard/files?name=" + file + "&kind=" + kind, fixture(file)), 200);
        return post(c, "/spaces/" + space + "/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"" + id + "\"}", 200);
    }

    /** The Dataset's one listed index, or null when none is listed (yet). */
    private JsonNode indexOf(Ctx c, String space, String dataset) throws Exception {
        for (JsonNode ix : data(send(c, "GET", "/spaces/" + space + "/inv/index", null), 200).get("indexes"))
            if (dataset.equals(ix.get("dataset").asText())) return ix;
        return null;
    }

    private static void assertCurrent(JsonNode ix) {
        assertNotNull(ix, "the index is listed");
        assertFalse(ix.get("stale").asBoolean(), "a fresh index: " + ix.get("reasons") + " " + ix.get("reason"));
        assertEquals(0, ix.get("reasons").size(), ix.toString());
        assertTrue(ix.get("reason").isNull(), ix.toString());
        assertEquals("known", ix.get("fingerprint").asText(), "currency is claimed only over files that could be compared: " + ix);
        assertEquals("none", ix.at("/plan/recommended").asText(), "nothing to rebuild: " + ix.get("plan"));
    }

    /** The expand an Investigation header and Expand read from: served by the index, and the index is current. */
    private void assertExpandReadsTheCurrentIndex(Ctx c, String space, String inv, String dataset, String from, String to,
                                                  String start) throws Exception {
        String base = "/spaces/" + space + "/inv/investigations";
        post(c, base, "{\"id\":\"" + inv + "\",\"purpose\":\"phase 4\",\"dataset\":\"" + dataset + "\",\"sourceCol\":\"" + from
                + "\",\"targetCol\":\"" + to + "\"}", 200);
        post(c, base + "/" + inv + "/ops", "{\"op\":\"seed\",\"ids\":[\"" + start + "\"]}", 200);
        JsonNode step = post(c, base + "/" + inv + "/ops", "{\"op\":\"expand\",\"maxFanOut\":5}", 200);
        assertTrue(step.at("/read/fallback").isMissingNode(), "not answered from the Dataset: " + step.get("read"));
        assertFalse(step.at("/read/index/stale").asBoolean(true), "the Expand reads a current index: " + step.get("read"));
    }

    // ── the full cycle: a Space of its own, the events file, then the details file ─────────────────────────────────────

    @Test
    void aFreshImportInASpaceOfItsOwnLeavesTheLinkIndexCurrentAfterBothFiles(@TempDir Path root) throws Exception {
        try (Ctx c = boot(root)) {
            post(c, "/spaces", "{\"id\":\"bank\"}", 200);
            JsonNode s = commit(c, "bank", "transfers.csv", "stream", "transfers");
            assertEquals("BUILT", s.at("/index/result").asText(), s.get("index").toString());
            assertCurrent(indexOf(c, "bank", "transfers"));    // red before the fix: relation_sql_changed at once

            JsonNode details = commit(c, "bank", "accounts.csv", "reference", "accounts");
            assertEquals("SKIPPED", details.at("/index/result").asText(), "details about entities draw no links: " + details.get("index"));
            assertCurrent(indexOf(c, "bank", "transfers"));    // the details file changes nothing about the events' index
            assertNull(indexOf(c, "bank", "accounts"), "a Reference has no link index");

            assertExpandReadsTheCurrentIndex(c, "bank", "mule-hub", "transfers", "from_account", "to_account", "MULE-HUB-01");
        }
    }

    /**
     * The same answer when the import finishes in the background (above {@code import.inline_max_bytes}: a 202 followed at
     * {@code /onboard/runs}). That pool carried no Space either, so its build read the store as a glob too (found by the
     * lane's verification); it now runs under the request's Space, as the index build worker does.
     */
    @Test
    void anImportThatFinishesInTheBackgroundLeavesTheLinkIndexCurrentToo(@TempDir Path root) throws Exception {
        try (Ctx c = boot(root)) {
            post(c, "/spaces", "{\"id\":\"bank\"}", 200);
            data(send(c, "PUT", "/spaces/bank/settings/link-analysis/limits",
                    "{\"import.inline_max_bytes\":0}".getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode d = data(send(c, "POST", "/spaces/bank/onboard/files?name=transfers.csv&kind=stream", fixture("transfers.csv")), 200);
            String runId = post(c, "/spaces/bank/onboard/files/" + d.get("draftId").asText() + "/commit", "{\"name\":\"transfers\"}", 202)
                    .get("runId").asText();
            JsonNode run = null;
            for (int i = 0; i < 1_500; i++) {
                run = data(send(c, "GET", "/spaces/bank/onboard/runs/" + runId, null), 200);
                if (!"running".equals(run.get("state").asText())) break;
                Thread.sleep(100);
            }
            assertEquals("succeeded", run.get("state").asText(), run.toString());
            assertEquals("BUILT", run.at("/summary/index/result").asText(), run.toString());
            assertCurrent(indexOf(c, "bank", "transfers"));    // red before: relation_sql_changed, as an inline import was
        }
    }

    /**
     * The Rebuild a caller holding {@code canBuildLinkIndex} is offered: the out-of-date index's own mapping, in the mode the
     * server advises ({@code plan.recommended}), on the existing {@code POST /inv/index/builds}. The words for an out-of-date
     * index are plain ones, one sentence per cause, the same on {@code GET /inv/index} and on the Expand's refusal.
     */
    @Test
    void anOutOfDateIndexSaysWhyInPlainWordsAndTheAdvisedRebuildMakesItCurrent(@TempDir Path root) throws Exception {
        try (Ctx c = boot(root)) {
            post(c, "/spaces", "{\"id\":\"bank\"}", 200);
            commit(c, "bank", "transfers.csv", "stream", "transfers");
            assertCurrent(indexOf(c, "bank", "transfers"));

            // the Dataset's definition changes: a calculated column (what Settings > Dataset writes)
            Path config = c.spaces.space(SpaceId.of("bank")).orElseThrow().root().config();
            ComponentStore registry = new ComponentStore(config.resolve("registry"));
            Map<String, Object> entry = new LinkedHashMap<>(registry.get("dataset", "transfers").orElseThrow().content());
            entry.remove("name");
            // over a text column: the fixture's amount is text since operator ruling 4 (2026-11-19, one value is 'abc')
            entry.put("calculated", List.of(Map.of("name", "at_atm", "expr", "channel = 'atm'")));
            registry.write("dataset", "transfers", entry);

            JsonNode stale = indexOf(c, "bank", "transfers");
            assertTrue(stale.get("stale").asBoolean(), stale.toString());
            assertEquals("the Dataset's definition changed after the index was built", stale.get("reason").asText(), "plain words");
            assertEquals("full", stale.at("/plan/recommended").asText(), stale.get("plan").toString());

            // the Expand says the same thing, in the same words
            String base = "/spaces/bank/inv/investigations";
            post(c, base, "{\"id\":\"why\",\"purpose\":\"phase 4\",\"dataset\":\"transfers\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\"}", 200);
            post(c, base + "/why/ops", "{\"op\":\"seed\",\"ids\":[\"MULE-HUB-01\"]}", 200);
            JsonNode step = post(c, base + "/why/ops", "{\"op\":\"expand\",\"maxFanOut\":5}", 200);
            assertEquals("index_stale_refused", step.at("/read/fallback/reason").asText(), step.get("read").toString());
            assertEquals(stale.get("reason").asText(), step.at("/read/fallback/details").asText(), "one answer, one wording");

            // Rebuild: the index's own mapping and the advised mode
            JsonNode m = stale.get("mapping");
            StringBuilder body = new StringBuilder("{\"dataset\":\"transfers\",\"sourceCol\":\"" + m.get("sourceCol").asText()
                    + "\",\"targetCol\":\"" + m.get("targetCol").asText() + "\"");
            for (String k : List.of("kindCol", "timeCol", "timeColZone", "weightCol"))
                if (!m.get(k).isNull()) body.append(",\"").append(k).append("\":\"").append(m.get(k).asText()).append('"');
            body.append(",\"attrCols\":").append(m.get("attrCols")).append(",\"mode\":\"").append(stale.at("/plan/recommended").asText()).append("\"}");
            String build = post(c, "/spaces/bank/inv/index/builds", body.toString(), 202).get("buildId").asText();
            String status = "";
            for (int i = 0; i < 3_000 && !status.equals("COMPLETED"); i++) {
                status = data(send(c, "GET", "/spaces/bank/inv/index/builds/" + build, null), 200).get("status").asText();
                assertFalse(status.equals("FAILED") || status.equals("CANCELLED"), status);
                Thread.sleep(20);
            }
            assertEquals("COMPLETED", status);
            JsonNode rebuilt = indexOf(c, "bank", "transfers");
            assertCurrent(rebuilt);
            assertEquals(stale.get("version").asLong() + 1, rebuilt.get("version").asLong());
        }
    }

    // ── the showcase: laid down as the seed's --scaffold does, indexed by its own Job ──────────────────────────────────

    @Test
    void theShowcaseSpaceOpensOnACurrentLinkIndexBuiltByItsOwnJob(@TempDir Path tmp) throws Exception {
        Path spacesRoot = tmp.resolve("spaces");
        scaffoldShowcase(spacesRoot.resolve("la-showcase"));
        System.setProperty("service.poll.seconds", "1");
        try (Ctx c = boot(spacesRoot)) {
            c.spaces.startAll();                                                       // the poll loop and the Job layer, as a server
            Path inbox = spacesRoot.resolve("la-showcase").resolve("data").resolve("inbox").resolve("mule_transfers");
            JsonNode ix = null;
            for (int i = 0; i < 600; i++) {                                            // the three landed days: ingest, commit, Job
                ix = indexOf(c, "la-showcase", "mule_transfers_dataset");
                if (ix != null && csvs(inbox) == 0 && ix.at("/plan/added").asInt(-1) == 0 && ix.at("/plan/removed").asInt(-1) == 0
                        && ix.at("/plan/changed").asInt(-1) == 0 && jobIdle(c)) break;
                Thread.sleep(200);
            }
            assertNotNull(ix, "the la_index_build Job built the showcase's index");
            assertEquals(0, csvs(inbox), "every landed day was ingested");
            assertCurrent(ix);                                                         // red before the fix: relation_sql_changed
            // the seeded case's own read (tools/seed-la-showcase.mjs: mule-hub-case)
            assertExpandReadsTheCurrentIndex(c, "la-showcase", "mule-hub-case", "mule_transfers_dataset", "PAYER_ACCOUNT",
                    "PAYEE_ACCOUNT", "MULE-HUB-01");
        }
    }

    private boolean jobIdle(Ctx c) {
        var jobs = c.spaces.space(SpaceId.of("la-showcase")).orElseThrow().service().jobService().orElseThrow();
        List<com.gamma.job.JobRun> runs = jobs.runsFor("la_index_build");
        return !runs.isEmpty() && runs.stream().allMatch(r -> r.endTime() != null && !r.endTime().isBlank());
    }

    private static long csvs(Path dir) throws Exception {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".csv")).count();
        }
    }

    /** {@code tools/seed-la-showcase.mjs --scaffold}: config and data copied, the index Job's owner stamped as its last editor. */
    private static void scaffoldShowcase(Path space) throws Exception {
        for (String sub : List.of("config", "data")) {
            Path from = sub.equals("data") ? LaCorpus.data(SHOWCASE) : SHOWCASE.resolve(sub);
            try (Stream<Path> w = Files.walk(from)) {
                for (Path p : w.toList()) {
                    Path to = space.resolve(sub).resolve(from.relativize(p).toString());
                    if (Files.isDirectory(p)) Files.createDirectories(to);
                    else Files.copy(p, to);
                }
            }
        }
        for (String sub : List.of("audit", "duckdb")) Files.createDirectories(space.resolve(sub));
        Path job = space.resolve("config").resolve("jobs").resolve("la_index_build_job.toon");
        Files.writeString(job, Files.readString(job).stripTrailing() + "\n  updatedBy: admin\n");
        Files.writeString(space.resolve("space.toon"), "display_name: Link Analysis showcase\ndescription: Scaffolded from the la-showcase Space Template\n");
    }
}
