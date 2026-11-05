package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.ComponentStore;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Link Analysis half of Add data ({@code LA-ONBOARD-1} phase 2, OB-2, OB-8, F-9, DT-16) over real HTTP, with the Link
 * Analysis modules on the classpath, no sign-in (Basic): one confirm turns a transfers file into a Dataset whose link index
 * is built INSIDE the user's run (not by a signal-driven Job), {@code /inv/projection} draws it with no saved view, and the
 * Entity profile assesses the account links from the Dataset's own {@code defaultLinkMapping} - while a Dataset without one
 * stays "not assessed". Synthetic fixture.
 */
class ControlApiOnboardLinkAnalysisTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private String id;

    @BeforeEach
    void setUp() {
        System.setProperty("control.rateLimit.linkAnalysis.capacity", "10000");
        id = "onboard_la_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("control.rateLimit.linkAnalysis.capacity");
        for (Path p : List.of(Path.of("database", id), Path.of("database", "inbox", id), Path.of("database", "_onboard")))
            if (Files.exists(p))
                try (Stream<Path> w = Files.walk(p)) {
                    for (Path x : w.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
                }
    }

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
        }
    }

    private Ctx open(Path cfg, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(cfg, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(Ctx c, String method, String path, byte[] body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + c.port + "/api/v1" + path));
        b = body == null ? b.GET() : b.method(method, BodyPublishers.ofByteArray(body));
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static JsonNode data(HttpResponse<String> r, int expected) throws Exception {
        assertEquals(expected, r.statusCode(), r.body());
        return JSON.readTree(r.body()).get("data");
    }

    /** Transfers between 40 accounts, with one hub (ACC-0001) that receives every tenth transfer. */
    private static String transfers(int rows) {
        StringBuilder sb = new StringBuilder("transfer_id,from_account,to_account,channel,amount,booked_at\n");
        String[] channels = {"mobile", "branch", "online"};
        for (int i = 1; i <= rows; i++) {
            int from = (i * 7) % 40 + 2, to = i % 10 == 0 ? 1 : (i * 13 + 5) % 40 + 2;
            if (to == from) to = to % 40 + 2;
            sb.append(String.format("T%05d,ACC-%04d,ACC-%04d,%s,%d.50,2026-09-%02d 10:%02d:00\n", i, from, to, channels[i % 3],
                    10 + (i * 37) % 900, 1 + i % 28, i % 60));
        }
        return sb.toString();
    }

    @Test
    void anImportIsIndexedInTheRunDrawnWithoutASavedViewAndItsLinksAreAssessed(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode s = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals(200, s.get("rowsAfter").asLong(), s.toString());

            // DT-16: the link index is built inside the user's own run, not dispatched as a signal-driven Job
            assertEquals("BUILT", s.get("index").get("result").asText(), s.get("index").toString());
            assertTrue(s.get("index").get("edges").asLong() > 0, s.get("index").toString());
            boolean indexed = false;
            for (JsonNode ix : data(send(c, "GET", "/inv/index", null), 200).get("indexes"))
                if (id.equals(ix.get("dataset").asText())) {
                    indexed = true;
                    // LA-COLUMN-ROLES-1 P2: every Time, Measure and Category column rides in the index, in column order
                    assertEquals("[\"channel\",\"amount\",\"booked_at\"]", ix.at("/mapping/attrCols").toString(), ix.toString());
                }
            assertTrue(indexed, "the index is published for the Dataset");

            // LA-ONBOARD-1 OB-13: the commit counted the first Dataset where it happened; no graph is drawn yet
            JsonNode m = data(send(c, "GET", "/onboarding/metrics", null), 200);
            assertTrue(m.get("firstDatasetSeconds").isNumber() && m.get("firstDatasetSeconds").asLong() >= 0, m.toString());
            assertTrue(m.get("firstGraph").isNull(), m.toString());

            // the Dataset draws with no saved view
            JsonNode g = data(send(c, "POST", "/inv/projection", ("{\"dataset\":\"" + id
                    + "\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\",\"linkKindCol\":\"channel\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertTrue(g.get("rows").size() > 0, "links are drawn (ids masked or not, per the Space's masking mode): " + g);
            // ... and the first graph is counted by the projection route itself (OB-13)
            m = data(send(c, "GET", "/onboarding/metrics", null), 200);
            assertTrue(m.get("firstGraphSeconds").isNumber() && m.get("firstGraphSeconds").asLong() >= 0, m.toString());
            assertTrue(g.toString().contains("\"kind\":\"mobile\""), "the kind column the draft proposed rides the links");

            // F-9: the account links are assessed from the Dataset's defaultLinkMapping - no view, no request mapping
            assertTrue(new ComponentStore(root.resolve("registry")).list("link-analysis-view").isEmpty(), "no saved view exists");
            JsonNode p = data(send(c, "GET", "/inv/entity-types/account/profile?sections=links,bindings", null), 200);
            assertTrue(p.at("/links/assessed").asBoolean(), p.get("links").toString());
            boolean source = false;
            for (JsonNode b : p.get("bindings"))
                if (id.equals(b.get("dataset").asText()) && "from_account".equals(b.get("column").asText()))
                    source = "source".equals(b.get("role").asText());
            assertTrue(source, "from_account is the link's source: " + p.get("bindings"));

            // F-9 isolated from the index: the same rows registered as wallets WITH a defaultLinkMapping (no index, no view) are
            // assessed; as agents WITHOUT one they stay "not assessed"
            ComponentStore registry = new ComponentStore(root.resolve("registry"));
            registry.write("dataset", id + "_wallets", Map.of("physicalRef", id + "/database",
                    "columns", List.of(Map.of("name", "from_account", "classification", "WALLET"),
                            Map.of("name", "to_account", "classification", "WALLET")),
                    "defaultLinkMapping", Map.of("from", "from_account", "to", "to_account")));
            registry.write("dataset", id + "_agents", Map.of("physicalRef", id + "/database",
                    "columns", List.of(Map.of("name", "from_account", "classification", "AGENT"),
                            Map.of("name", "to_account", "classification", "AGENT"))));
            JsonNode w = data(send(c, "GET", "/inv/entity-types/wallet/profile?sections=links", null), 200);
            assertTrue(w.at("/links/assessed").asBoolean(), w.toString());
            JsonNode a = data(send(c, "GET", "/inv/entity-types/agent/profile?sections=links", null), 200);
            assertFalse(a.at("/links/assessed").asBoolean(), a.toString());

            // Run now builds again inside the run: the index is current
            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", new byte[0]), 200);
            assertTrue(List.of("BUILT", "UP_TO_DATE").contains(now.get("index").get("result").asText()), now.get("index").toString());
        }
    }

    /**
     * LA-COLUMN-ROLES-1 P3: a role filter (a Measure range, a Category list, a Time window) on a link read is answered from the link index's
     * attribute columns - which Add data's first build carries - with exactly the rows the Dataset read gives, and narrows them.
     */
    @Test
    void aRoleFilterIsServedFromTheIndexAndAgreesWithTheDataset(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode s = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("BUILT", s.get("index").get("result").asText(), s.get("index").toString());
            String filter = "{\"kind\":\"group\",\"op\":\"AND\",\"items\":["
                    + "{\"kind\":\"condition\",\"field\":\"channel\",\"operator\":\"in\",\"value\":\"mobile,online\"},"
                    + "{\"kind\":\"condition\",\"field\":\"amount\",\"operator\":\"between\",\"value\":\"100\",\"value2\":\"600\"},"
                    // a window on the Time column, as the role panel writes it (datetime-local, no zone)
                    + "{\"kind\":\"condition\",\"field\":\"booked_at\",\"operator\":\">=\",\"value\":\"2026-09-05T00:00\"},"
                    + "{\"kind\":\"condition\",\"field\":\"booked_at\",\"operator\":\"<\",\"value\":\"2026-09-25T12:30\"}]}";
            String read = "{\"dataset\":\"" + id + "\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\",\"value\":\"ACC-0001\"";
            JsonNode all = data(send(c, "POST", "/inv/projection/neighbors", (read + "}").getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode viaIndex = data(send(c, "POST", "/inv/projection/neighbors",
                    (read + ",\"filter\":" + filter + "}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("index", viaIndex.at("/source/kind").asText(), viaIndex.get("source").toString());
            Files.writeString(root.resolve("link-analysis.toon"), "index:\n  enabled: false\n");
            JsonNode viaDataset = data(send(c, "POST", "/inv/projection/neighbors",
                    (read + ",\"filter\":" + filter + "}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals(sorted(viaDataset.get("rows")), sorted(viaIndex.get("rows")), "the index answers as the Dataset does");
            assertTrue(viaIndex.get("rows").size() > 0, viaIndex.toString());
            assertTrue(viaIndex.get("rows").size() < all.get("rows").size(), "the filter narrows the hub's links");
        }
    }

    private static List<String> sorted(JsonNode rows) {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode r : rows) out.add(r.toString());
        out.sort(null);
        return out;
    }

    /**
     * LA-ONBOARD-DEFECTS-1 (1): after Add a file in append mode the index summary says what the index now HOLDS - every link
     * and each entity once - not what the appended batch added ("2,215 links between 624 entities" for 4,430 rows over 312
     * accounts). Red on the old code: the APPEND build's own counts were reported (200 edges, the node rows of both builds).
     */
    @Test
    void afterAnAppendTheIndexSummaryCountsTheWholeDatasetAndEachEntityOnce(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode first = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200).get("index");
            assertEquals("BUILT", first.get("result").asText(), first.toString());
            assertEquals(200, first.get("edges").asLong(), first.toString());
            long entities = first.get("nodes").asLong();
            assertTrue(entities > 0 && entities <= 42, "at most the 42 accounts the fixture names: " + first);

            // the same rows in other bytes (CRLF): the same bytes again would be refused as already imported (D-3)
            JsonNode app = data(send(c, "POST", "/datasets/" + id + "/files?name=again.csv&mode=append",
                    transfers(200).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals(400, app.get("rowsAfter").asLong(), app.toString());
            JsonNode ix = app.get("index");
            assertEquals("BUILT", ix.get("result").asText(), ix.toString());
            assertEquals(400, ix.get("edges").asLong(), "every link the Dataset holds: " + ix);
            assertEquals(entities, ix.get("nodes").asLong(), "the same accounts, each counted once: " + ix);

            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", new byte[0]), 200).get("index");
            assertEquals("UP_TO_DATE", now.get("result").asText(), now.toString());
            assertEquals(400, now.get("edges").asLong(), now.toString());
            assertEquals(entities, now.get("nodes").asLong(), "an index that is already current counts each entity once too: " + now);
        }
    }

    /**
     * LA-ARRIVAL-NEW-COLUMNS-1: a later file's new column is kept; made a Category it rides the link index, which is then
     * built over the older files (no such column: read as empty, by name) and the newer one together.
     */
    @Test
    void aKeptNewColumnRidesTheIndexBuiltOverOlderAndNewerFiles(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("BUILT", data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200).at("/index/result").asText());
            StringBuilder more = new StringBuilder();
            String[] lines = transfers(50).replace("T0", "W0").split("\n");
            for (int i = 0; i < lines.length; i++) more.append(lines[i]).append(',').append(i == 0 ? "region" : "R" + i % 4).append('\n');
            JsonNode app = data(send(c, "POST", "/datasets/" + id + "/files?name=more.csv&mode=append",
                    more.toString().getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("[\"region\"]", app.get("keptColumns").toString(), app.toString());
            assertEquals("BUILT", app.at("/index/result").asText(), app.toString());

            data(send(c, "PUT", "/datasets/" + id + "/columns/region", "{\"role\":\"dimension\"}".getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", new byte[0]), 200).get("index");
            assertEquals("BUILT", now.get("result").asText(), now.toString());
            assertEquals(250, now.get("edges").asLong(), "every row of both files: " + now);
            boolean rides = false;
            for (JsonNode ix : data(send(c, "GET", "/inv/index", null), 200).get("indexes"))
                if (id.equals(ix.get("dataset").asText())) rides = ix.at("/mapping/attrCols").toString().contains("\"region\"");
            assertTrue(rides, "the kept column is an attribute of the index");

            JsonNode g = data(send(c, "POST", "/inv/projection", ("{\"dataset\":\"" + id
                    + "\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\",\"linkKindCol\":\"region\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertTrue(g.toString().contains("\"kind\":\"R1\""), "the newer file's values ride the links: " + g);
        }
    }

    /**
     * Operator 2026-11-05: where Pipelines run on their own (Standard and up, {@code pipeline.automation}), a person's import
     * still builds the link index inside the run - the graph is fresh at once; a scheduled feed's files are the Job's.
     */
    @Test
    void onStandardAnImportStillBuildsTheIndexInsideTheRun(@TempDir Path cfg, @TempDir Path root) throws Exception {
        EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
        try (Ctx c = open(cfg, root)) {
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            JsonNode s = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("BUILT", s.at("/index/result").asText(), s.toString());
            assertEquals(200, s.at("/index/edges").asLong(), s.toString());
            JsonNode app = data(send(c, "POST", "/datasets/" + id + "/files?name=more.csv&mode=append",
                    transfers(50).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("BUILT", app.at("/index/result").asText(), "Add a file too: " + app);
        } finally {
            EditionFeatures.overrideForTest(null);
        }
    }

    /**
     * LA-DATASET-COMPACT-2 (b): <i>Compact now</i> with a retention policy removes rows the link index still holds. The very
     * next graph read must not show them - not one fingerprint-cache lifetime later, and not until the next import.
     */
    @Test
    void afterARetentionCompactionTheGraphNoLongerShowsTheRemovedRows(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // Basic: the commit and the append are each indexed inside the run
            JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(200).getBytes(StandardCharsets.UTF_8)), 200);
            assertEquals("BUILT", data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                    ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200).at("/index/result").asText());
            String wire = transfers(50).replaceAll(",(mobile|branch|online),", ",wire,").replace("T0", "W0");
            assertEquals("BUILT", data(send(c, "POST", "/datasets/" + id + "/files?name=wire.csv&mode=append",
                    wire.getBytes(StandardCharsets.UTF_8)), 200).at("/index/result").asText());
            // Standard: keep the newest arrival only (the Dataset entry changes now, not between the two reads)
            EditionFeatures.overrideForTest(Set.of(OnboardImport.AUTOMATION_FEATURE));
            try {
                data(send(c, "PUT", "/datasets/" + id + "/storage", "{\"retention\":{\"arrivals\":1}}".getBytes(StandardCharsets.UTF_8)), 200);

                // the hub's links, from the index: both files' rows
                byte[] read = ("{\"dataset\":\"" + id + "\",\"sourceCol\":\"from_account\",\"targetCol\":\"to_account\",\"linkKindCol\":\"channel\","
                        + "\"value\":\"ACC-0001\"}").getBytes(StandardCharsets.UTF_8);
                JsonNode before = data(send(c, "POST", "/inv/projection/neighbors", read), 200);
                assertEquals("index", before.at("/source/kind").asText(), before.get("source").toString());
                assertTrue(before.toString().contains("mobile"), before.toString());

                // Compact now: the first file's 200 rows go
                JsonNode now = data(send(c, "POST", "/datasets/" + id + "/compact", new byte[0]), 200);
                assertEquals(200, now.at("/compaction/rowsRemoved").asLong(), now.toString());

                // within the input-fingerprint cache's lifetime: the index's own stale check must refuse it already
                JsonNode after = data(send(c, "POST", "/inv/projection/neighbors", read), 200);
                assertEquals("index_stale_refused", after.at("/source/reason").asText(), after.get("source").toString());
                assertTrue(after.toString().contains("wire"), after.toString());
                assertFalse(after.toString().contains("mobile"), "a removed row is drawn: " + after);
            } finally {
                EditionFeatures.overrideForTest(null);
            }
        }
    }

    /**
     * LA-ARRIVAL-LEDGER-1 V4: on Basic every Add a file (append) and every Run now that takes a dropped file builds the link
     * index INCREMENTALLY - mode {@code append}, never a full rebuild - so an append costs the new file, not the Dataset. The
     * arrivals list records each file's index outcome and mode, Run now's dropped file included.
     */
    @Test
    void onBasicEachAppendIsIndexedAsAnAppendAndTheArrivalsSayIt(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            long[] ms = v4(c, 2_000, 3, 200);
            JsonNode list = data(send(c, "GET", "/datasets/" + id + "/arrivals", null), 200).get("arrivals");
            assertEquals(5, list.size(), list.toString());   // the commit, three appends, one drop
            assertEquals("system", list.get(0).get("sender").asText(), list.get(0).toString());
            for (JsonNode a : list) assertEquals("BUILT", a.get("index").asText(), a.toString());
            assertEquals("full", list.get(4).get("indexMode").asText(), "the first build is the only full one: " + list.get(4));
            for (int i = 0; i < 4; i++) assertEquals("append", list.get(i).get("indexMode").asText(), list.get(i).toString());
            System.out.printf("V4 base=2000 first=%dms appends=%s%n", ms[0], java.util.Arrays.toString(java.util.Arrays.copyOfRange(ms, 1, ms.length)));
        }
    }

    /**
     * V4 at scale, run by hand: {@code -Dla.v4.rows=1000000} (the base file) and optionally {@code -Dla.v4.appendRows}.
     * Prints the commit's and each append's wall time; asserts only that every append stayed an append.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "la.v4.rows", matches = "\\d+")
    void v4MeasuredAtScale(@TempDir Path cfg, @TempDir Path root) throws Exception {
        int rows = Integer.parseInt(System.getProperty("la.v4.rows"));
        int appendRows = Integer.parseInt(System.getProperty("la.v4.appendRows", "10000"));
        try (Ctx c = open(cfg, root)) {
            long[] ms = v4(c, rows, 3, appendRows);
            System.out.printf("V4 base=%d appendRows=%d first=%dms appends+drop=%s%n", rows, appendRows, ms[0],
                    java.util.Arrays.toString(java.util.Arrays.copyOfRange(ms, 1, ms.length)));
        }
    }

    /** Commit {@code base} rows, then {@code appends} Add a file appends and one dropped file Run now; the wall time of each. */
    private long[] v4(Ctx c, int base, int appends, int appendRows) throws Exception {
        long[] ms = new long[appends + 2];
        JsonNode d = data(send(c, "POST", "/onboard/files?name=transfers.csv&kind=stream", transfers(base).getBytes(StandardCharsets.UTF_8)), 200);
        long t = System.nanoTime();
        JsonNode first = data(send(c, "POST", "/onboard/files/" + d.get("draftId").asText() + "/commit",
                ("{\"name\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8)), 200).get("index");
        ms[0] = (System.nanoTime() - t) / 1_000_000;
        assertEquals("BUILT", first.get("result").asText(), first.toString());
        assertEquals("full", first.get("mode").asText(), first.toString());
        long edges = first.get("edges").asLong();
        for (int i = 1; i <= appends; i++) {
            t = System.nanoTime();
            // each part its own ids, so no part is the same bytes as another (refused as already imported, D-3)
            JsonNode ix = data(send(c, "POST", "/datasets/" + id + "/files?name=part" + i + ".csv&mode=append",
                    transfers(appendRows).replace("\nT", "\nP" + i + "T").getBytes(StandardCharsets.UTF_8)), 200).get("index");
            ms[i] = (System.nanoTime() - t) / 1_000_000;
            assertEquals("BUILT", ix.get("result").asText(), ix.toString());
            assertEquals("append", ix.get("mode").asText(), "an append is indexed as an append, not rebuilt: " + ix);
            assertEquals(edges + appendRows, ix.get("edges").asLong(), ix.toString());
            edges = ix.get("edges").asLong();
        }
        Files.writeString(Path.of("database", "inbox", id).resolve("foreign_drop.csv"), transfers(appendRows));
        t = System.nanoTime();
        JsonNode now = data(send(c, "POST", "/datasets/" + id + "/run", new byte[0]), 200).get("index");
        ms[appends + 1] = (System.nanoTime() - t) / 1_000_000;
        assertEquals("BUILT", now.get("result").asText(), now.toString());
        assertEquals("append", now.get("mode").asText(), "Run now's dropped file is indexed as an append: " + now);
        return ms;
    }
}
