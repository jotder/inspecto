package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PROCESSOR-RELEASE-READINESS-1} G8 — the two blind spots of the graph dry run, over real HTTP.
 *
 * <ol>
 *   <li>An {@code enrichment} node is a post-commit Stage-2 job over the COMMITTED store; the dry-run walk
 *       has no executor for it and used to skip it — and everything below it — without a word. It now
 *       answers a warning that names the node and what it starved.</li>
 *   <li>A {@code sink.webhook} in a bundle with no {@code WebhookSinkTransport} (this module's test classpath
 *       is Personal-like: it does not depend on {@code inspecto-notify-channels}) used to preview as a
 *       healthy branch, while the job dry run ({@code DryRunSinkWriter}) refuses it. Both now refuse the
 *       same way, through {@code WebhookSink.plan}.</li>
 * </ol>
 *
 * <p>The companion shape {@code GET /graph/raw} synthesizes for a {@code *_enrich.toon} is joined to the
 * sink by a derived display-only edge the save path drops — see {@code ControlApiPipelineGraphCompanionTest}.
 */
class ControlApiDryRunBlindSpotsTest {

    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port, String priorRoots)
            implements AutoCloseable {
        public void close() {
            api.close();
            svc.close();
            if (priorRoots != null) System.setProperty("assist.safety.roots", priorRoots);
            else System.clearProperty("assist.safety.roots");
        }
    }

    private Ctx open(Path dir) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(dir, "");
        String priorRoots = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", dir.toString());
        System.setProperty("assist.write.root", dir.toString());
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port(), priorRoots);
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> send(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        if (body != null) b.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        else b.method(method, BodyPublishers.noBody());
        return client.send(b.build(), BodyHandlers.ofString());
    }

    private static String warningNaming(JsonNode warnings, String nodeId) {
        for (JsonNode w : warnings) if (w.asText().contains("'" + nodeId + "'")) return w.asText();
        return null;
    }

    // ── 1 · enrichment ───────────────────────────────────────────────────────────────────────────

    /**
     * An enrichment authored mid-walk: the silent case the row names. The filter ran, the sink below the
     * enrichment received nothing, and the answer carried no warning at all — the DRYRUN-2 warnings only
     * fire for "reached no node" and "every sink got zero", and here there IS no sink branch.
     */
    @Test
    @DisplayName("G8: an enrichment mid-walk names itself AND the nodes below it that were not previewed")
    void aMidWalkEnrichmentNamesItselfAndWhatItStarved(@TempDir Path dir) throws Exception {
        String candidate = """
            {"pipeline":
              {"name":"enr_flow","active":false,
               "nodes":[{"id":"acq","type":"acquisition"},
                        {"id":"flt","type":"transform.filter","config":{"where":"CAST(amt AS INT) >= 100"}},
                        {"id":"enr","type":"enrichment","use":"enrichment/orders_daily"},
                        {"id":"sink","type":"sink.persistent","config":{"store":"out"}}],
               "edges":[{"from":"acq","rel":"data","to":"flt"},{"from":"flt","rel":"data","to":"enr"},
                        {"from":"enr","rel":"data","to":"sink"}]},
             "sampleRows":[{"id":"1","amt":"150"},{"id":"2","amt":"50"}]}""";
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "POST", "/pipelines/authored/enr_flow/dry-run", candidate);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode res = V1Body.of(r.body());
            String w = warningNaming(res.get("warnings"), "enr");
            assertTrue(w != null, "the enrichment node must be named, never skipped silently: " + res);
            assertTrue(w.contains("'sink'"), "the nodes it starved are named too: " + w);
            assertTrue(w.contains("post-commit") && w.contains("/enrichment/preview"),
                    "it says WHY it was not run and where to preview it instead: " + w);
            assertEquals(1, res.get("nodes").size(), "the filter above it still previewed: " + res);
        }
    }

    // ── 2 · sink.webhook in a bundle with no transport ───────────────────────────────────────────

    @Test
    @DisplayName("G8: a sink.webhook dry run refuses 422 in a bundle with no transport — as the job dry run does")
    void aWebhookSinkRefusesWhereNoTransportIsBundled(@TempDir Path dir) throws Exception {
        String candidate = """
            {"pipeline":
              {"name":"wh_flow","active":false,
               "nodes":[{"id":"acq","type":"acquisition"},
                        {"id":"hook","type":"sink.webhook","config":{"connection":"crm"}}],
               "edges":[{"from":"acq","rel":"data","to":"hook"}]},
             "sampleRows":[{"id":"1"}]}""";
        try (Ctx c = open(dir)) {
            HttpResponse<String> r = send(c.port, "POST", "/pipelines/authored/wh_flow/dry-run", candidate);
            assertEquals(422, r.statusCode(),
                    "a preview must not show a healthy branch the run would refuse: " + r.body());
            assertTrue(r.body().contains("'hook'") && r.body().contains("Professional"),
                    "the refusal names the sink and the edition, in WebhookSink's own words: " + r.body());
        }
    }

    // ── 3 · SEC-DRYRUN-EXPR-UNSEALED-1: authored SQL in a dry run runs on a sealed connection ────

    private static String fwd(Path p) { return p.toString().replace("\\", "/"); }

    /** acq -> map(one authored expression reading {@code readFile}) -> join(reference) -> sink. */
    private static String exprThenJoin(String readFile, String reference) {
        return """
            {"pipeline":
              {"name":"seal_flow","active":false,
               "nodes":[{"id":"acq","type":"acquisition"},
                        {"id":"m","type":"transform.sql","config":{"columns":[
                            {"name":"id","expr":"id"},
                            {"name":"leak","expr":"(SELECT content FROM read_text('%s'))"}]}},
                        {"id":"j","type":"transform.join","config":{"reference":"%s","on":"id"}},
                        {"id":"sink","type":"sink.persistent","config":{"store":"out"}}],
               "edges":[{"from":"acq","rel":"data","to":"m"},{"from":"m","rel":"data","to":"j"},
                        {"from":"j","rel":"data","to":"sink"}]},
             "sampleRows":[{"id":"1"}]}""".formatted(readFile, reference);
    }

    private HttpResponse<String> dryRunSealFlow(Path dir, String body) throws Exception {
        try (Ctx c = open(dir)) {
            return send(c.port, "POST", "/pipelines/authored/seal_flow/dry-run", body);
        }
    }

    private static void assertSealed(HttpResponse<String> r) {
        assertTrue(r.statusCode() >= 400, "the expression must not run: " + r.body());
        assertTrue(r.body().contains("Permission Error"), "a DuckDB permission refusal, not a later failure: " + r.body());
        assertFalse(r.body().contains("host-secret"), "the file content must not leak: " + r.body());
    }

    @Test
    @DisplayName("SEC-DRYRUN: a path: join reference admits that one file, not its siblings")
    void aPathReferenceDoesNotAdmitItsSiblings(@TempDir Path dir) throws Exception {
        Path ref = Files.writeString(dir.resolve("ref.csv"), "id,label\n1,Alpha\n");
        Path sibling = Files.writeString(dir.resolve("other.csv"), "host-secret");
        assertSealed(dryRunSealFlow(dir, exprThenJoin(fwd(sibling), fwd(ref))));
    }

    /**
     * A {@code path:} reference is admitted as a LITERAL file, so a glob names no file and fails closed: the
     * expression (which runs first) still cannot read a sibling the glob would have matched.
     */
    @Test
    @DisplayName("SEC-DRYRUN: a glob path: reference admits no sibling (fails closed)")
    void aGlobPathReferenceAdmitsNoSibling(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ref.csv"), "id,label\n1,Alpha\n");
        Path sibling = Files.writeString(dir.resolve("other.csv"), "host-secret");
        HttpResponse<String> r = dryRunSealFlow(dir, exprThenJoin(fwd(sibling), fwd(dir) + "/*.csv"));
        assertTrue(r.statusCode() >= 400, "a glob reference must not run: " + r.body());
        assertFalse(r.body().contains("host-secret"), "no sibling is read: " + r.body());
    }

    /** The same file spelled natively (backslashes on Windows) is the same allowlist entry, and still only that file. */
    @Test
    @DisplayName("SEC-DRYRUN: a natively spelled path: reference admits that file only, not a sibling")
    void aNativelySpelledPathReferenceAdmitsNoSibling(@TempDir Path dir) throws Exception {
        Path ref = Files.writeString(dir.resolve("ref.csv"), "id,label\n1,Alpha\n");
        Path sibling = Files.writeString(dir.resolve("other.csv"), "host-secret");
        HttpResponse<String> r = dryRunSealFlow(dir,
                exprThenJoin(fwd(sibling), ref.toString().replace("\\", "\\\\")));
        assertSealed(r);
    }

    @Test
    @DisplayName("SEC-DRYRUN: an expression cannot read a host file outside the roots, whatever the join reads")
    void anExpressionCannotReadAHostFileThroughTheRoute(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        Path ref = Files.writeString(dir.resolve("ref.csv"), "id,label\n1,Alpha\n");
        Path host = Files.writeString(elsewhere.resolve("host.txt"), "host-secret");
        assertSealed(dryRunSealFlow(dir, exprThenJoin(fwd(host), fwd(ref))));
    }

    @Test
    @DisplayName("SEC-DRYRUN: a join reference file directly in the data root is refused 400")
    void aReferenceFileInTheDataRootIsRefused(@TempDir Path dir, @TempDir Path elsewhere) throws Exception {
        Path ref = Files.writeString(dir.resolve("ref.csv"), "id,label\n1,Alpha\n");
        Path host = Files.writeString(elsewhere.resolve("host.txt"), "host-secret");
        String prior = System.getProperty("data.dir");
        System.setProperty("data.dir", dir.toString());
        try {
            HttpResponse<String> r = dryRunSealFlow(dir, exprThenJoin(fwd(host), fwd(ref)));
            assertEquals(400, r.statusCode(), r.body());
            assertTrue(r.body().contains("refused"), r.body());
        } finally {
            if (prior != null) System.setProperty("data.dir", prior); else System.clearProperty("data.dir");
        }
    }
}
