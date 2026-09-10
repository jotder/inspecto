package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.CollectorService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Optimistic concurrency on {@code POST /config/write} over real HTTP — {@code CLIENT-HALVES-1} (a),
 * 2026-09-11.
 *
 * <p><b>The defect this closes.</b> Every authoring caller of {@code ConfigService.write()} passes
 * {@code overwrite: true} (the write is a whole-file replace), which bypasses the existence-conflict
 * check entirely. So two editors of one config were <b>last-write-wins</b>: the second save destroyed the
 * first with no signal to either party. An {@code If-Match} precondition over the config's content hash
 * turns that silent loss into a {@code 409 CONFLICT_STALE_VERSION} the pane can report.
 *
 * <p>⚠ <b>Honoured, not required</b> — the house rule {@code ETags.requireMatch} already encodes. A caller
 * that sends no {@code If-Match} writes exactly as before, which is why adding this broke no existing
 * client. {@link #aWriteWithNoPreconditionStillSucceeds()} is what pins that promise.
 *
 * <p>🔴 <b>The load-bearing test is {@link #theReadsEtagIsExactlyWhatTheWriteDemands()}.</b> A precondition
 * hashed over different bytes than the ETag the client was handed is worse than no precondition at all: it
 * either refuses every write or accepts every stale one, and it fails silently either way. Both sides go
 * through {@code ConfigFileSupport.storedContent} for that reason, and this test is what would notice if
 * one of them stopped.
 */
class ControlApiConfigIfMatchTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        return open(configDir, writeRoot, null);
    }

    /** {@code corsOrigin != null} ⇒ the server emits CORS headers at all; it emits none otherwise. */
    private Ctx open(Path configDir, Path writeRoot, String corsOrigin) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        System.setProperty("assist.write.root", writeRoot.toString());
        if (corsOrigin != null) System.setProperty("control.cors", corsOrigin);
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
            System.clearProperty("control.cors");
        }
    }

    private static String pipeline(String name, int threads) {
        return """
                {"type":"pipeline","overwrite":true,"config":{
                   "name":"%s",
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":%d}}}""".formatted(name, threads);
    }

    /** POST /config/write, optionally carrying an If-Match precondition. */
    private HttpResponse<String> write(int port, String body, String ifMatch) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/config/write"));
        if (ifMatch != null) b.header("If-Match", ifMatch);
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> read(int port, String type, String name) throws Exception {
        return client.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/config/" + type + "/" + name)).GET().build(),
                BodyHandlers.ofString());
    }

    /** The ETag a read publishes — the handle an editor holds while the operator types. */
    private String etagOf(int port, String type, String name) throws Exception {
        HttpResponse<String> r = read(port, type, name);
        assertEquals(200, r.statusCode(), "the config must be readable to have a precondition at all");
        return r.headers().firstValue("ETag").orElseThrow(
                () -> new AssertionError("GET /config/" + type + "/" + name + " served no ETag"));
    }

    @Test
    void theReadPublishesAStrongContentEtagThatTracksTheContent(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, pipeline("p1", 1), null).statusCode());

            String first = etagOf(c.port, "pipeline", "p1");
            assertTrue(first.startsWith("\"sha256:"), "a strong content ETag, not an opaque token: " + first);
            assertEquals(first, etagOf(c.port, "pipeline", "p1"), "unchanged content ⇒ the same ETag");

            // Content hash, not an mtime or a counter: a real edit must move it.
            assertEquals(200, write(c.port, pipeline("p1", 4), first).statusCode());
            assertNotEquals(first, etagOf(c.port, "pipeline", "p1"), "an edited config must not keep its ETag");
        }
    }

    /**
     * 🔴 The property the whole feature rests on: the bytes the read hashes and the bytes the write
     * demands are the same bytes. If they ever diverge, a freshly-read ETag would be rejected as stale.
     */
    @Test
    void theReadsEtagIsExactlyWhatTheWriteDemands(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, pipeline("p2", 1), null).statusCode());

            String etag = etagOf(c.port, "pipeline", "p2");
            HttpResponse<String> r = write(c.port, pipeline("p2", 2), etag);

            assertEquals(200, r.statusCode(),
                    "a precondition taken straight from the read must be accepted; a 409 here means the read "
                            + "and the write hash different bytes (see ConfigFileSupport.storedContent): " + r.body());
            assertTrue(JSON.readTree(r.body()).get("data").get("written").asBoolean(), "and it must actually write");
        }
    }

    /** A stale precondition is the whole point: the second editor is refused instead of clobbering. */
    @Test
    void aStalePreconditionIsRefusedWith409RatherThanClobbering(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, pipeline("p3", 1), null).statusCode());
            String held = etagOf(c.port, "pipeline", "p3");        // editor A reads

            assertEquals(200, write(c.port, pipeline("p3", 7), held).statusCode());   // editor B saves first

            HttpResponse<String> stale = write(c.port, pipeline("p3", 9), held);      // A saves on a stale read
            assertEquals(409, stale.statusCode(), "⚠ NOT 412 — this codebase has no 412; the code is the payload");
            assertEquals("CONFLICT_STALE_VERSION",
                    JSON.readTree(stale.body()).get("error").get("errorCode").asText(),
                    "a non-2xx body is {error:{…}}, not the envelope");

            // And the refusal must be a refusal: B's value survives, A's is not applied.
            JsonNode after = JSON.readTree(read(c.port, "pipeline", "p3").body()).get("data").get("config");
            assertEquals(7, after.get("processing").get("threads").asInt(),
                    "the earlier save must still be on disk — a 409 that wrote anyway would be the worse bug");
        }
    }

    /**
     * ⚠ Honoured, not required. This is what makes the change non-breaking: every existing caller sends no
     * If-Match, and every one of them must keep working exactly as before.
     */
    @Test
    void aWriteWithNoPreconditionStillSucceeds(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, pipeline("p4", 1), null).statusCode());
            etagOf(c.port, "pipeline", "p4");   // a fresh ETag now exists and is deliberately ignored

            HttpResponse<String> r = write(c.port, pipeline("p4", 3), null);
            assertEquals(200, r.statusCode(), "an absent precondition must not become a refusal");
            assertTrue(JSON.readTree(r.body()).get("data").get("written").asBoolean());
        }
    }

    /**
     * 🔴 <b>SPLIT STORAGE — the case the pipeline-only tests could not see.</b> A {@code schema} is stored
     * as a TOON <em>without</em> its fields or rules, plus sibling {@code _structure.csv} /
     * {@code _mapping.csv}; the read serves the conflated view. So a write side that hashed the bare TOON
     * would disagree with the read's ETag <b>on every schema in the product</b> — and silently, refusing
     * every save that carried a freshly-read precondition.
     *
     * <p>⚠ This test exists because the mutation "storedContent drops the schema sibling merge"
     * <b>SURVIVED</b> a suite that only wrote {@code pipeline} configs. A precondition is exactly the kind
     * of feature whose happy path looks identical whether or not it hashes the right bytes.
     */
    @Test
    void theSameBytesPropertyHoldsForSplitStorageSchemas(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, schema("INTEGER", false), null).statusCode());
            // Sanity: this really is the split form, or the test proves nothing about siblings.
            assertTrue(Files.exists(root.resolve("ev_mapping.csv")), "mapping sibling written");
            assertTrue(Files.exists(root.resolve("ev_structure.csv")), "structure sibling written");

            String etag = etagOf(c.port, "schema", "ev");
            HttpResponse<String> r = write(c.port, schema("INTEGER", true), etag);
            assertEquals(200, r.statusCode(),
                    "a schema's freshly-read ETag must satisfy its own write; a 409 here means the write "
                            + "hashed the bare TOON while the read hashed the conflated view: " + r.body());

            // And it must still REFUSE a stale one for a schema, not merely accept everything.
            assertEquals(200, write(c.port, schema("DOUBLE", true), etagOf(c.port, "schema", "ev")).statusCode());
            assertEquals(409, write(c.port, schema("INTEGER", true), etag).statusCode(),
                    "the ETag held from before those saves is stale and must be refused");
        }
    }

    /** A schema draft (identity {@code raw.name=ev}) — split storage: fields and rules go to sibling CSVs. */
    private static String schema(String qtyType, boolean overwrite) {
        return """
                {"type":"schema",%s"compatibility":"none","config":{
                   "raw":{"name":"ev","format":"CSV","fields":[
                      {"name":"ID","selector":"0","type":"VARCHAR"},
                      {"name":"QTY","selector":"1","type":"%s"}]},
                   "mapping":{"canonicalName":"ev","rules":[
                      {"targetColumn":"ID","sourceExpression":"ID","transformType":"DIRECT"}]}}}
                """.formatted(overwrite ? "\"overwrite\":true," : "", qtyType);
    }

    /**
     * 🔴 <b>SAVE TWICE.</b> A successful write invalidates the ETag the caller was holding, so unless the
     * write hands back the new one, the editor's <b>second</b> save is refused as stale — a precondition
     * that breaks consecutive saves is worse than no precondition at all. The write therefore publishes
     * the post-save {@code ETag} and this pins that it is the one the next write accepts.
     */
    @Test
    void aWriteHandsBackTheEtagItsOwnNextWriteWillAccept(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, pipeline("twice", 1), null).statusCode());

            String held = etagOf(c.port, "pipeline", "twice");
            HttpResponse<String> first = write(c.port, pipeline("twice", 2), held);
            assertEquals(200, first.statusCode(), first.body());

            String afterSave = first.headers().firstValue("ETag").orElseThrow(
                    () -> new AssertionError("POST /config/write served no ETag, so a second save cannot be made"));
            assertNotEquals(held, afterSave, "the content changed, so the handle must have moved");
            assertEquals(afterSave, etagOf(c.port, "pipeline", "twice"),
                    "and it must equal what a fresh read reports, or the caller is holding a handle nobody honours");

            assertEquals(200, write(c.port, pipeline("twice", 3), afterSave).statusCode(),
                    "the second consecutive save must be accepted using the etag the first one returned");
        }
    }

    /** {@code If-Match: *} means "it must exist", which it does — the wildcard must not be read as stale. */
    @Test
    void theWildcardPreconditionIsAccepted(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root)) {
            assertEquals(200, write(c.port, pipeline("p5", 1), null).statusCode());
            assertEquals(200, write(c.port, pipeline("p5", 2), "*").statusCode());
        }
    }

    /**
     * A CREATE cannot be stale — there is nothing to be stale against — so a precondition on a brand-new
     * name must not refuse the very first save. (The gate is behind {@code exists}.)
     */
    @Test
    void aPreconditionOnAConfigThatDoesNotExistYetDoesNotRefuseTheCreate(@TempDir Path dir, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(dir, root)) {
            HttpResponse<String> r = write(c.port, pipeline("brand_new", 1), "\"sha256:whatever\"");
            assertEquals(200, r.statusCode(), "nothing existed, so nothing could have changed underneath: " + r.body());
        }
    }

    /**
     * 🔴 The browser half, and it was silently missing: the SPA runs cross-origin (:4204 → :8080), so it
     * cannot read a response header at all unless the server exposes it. ETags have been served since W3,
     * but nothing sent If-Match, so nobody noticed. Without this the feature would appear to work
     * server-side and do nothing in a browser.
     *
     * <p>⚠ Needs {@code control.cors} set: with no configured origin the server emits <b>no</b> CORS
     * headers at all, so asserting on them without one passes or fails for the wrong reason.
     */
    @Test
    void theEtagHeaderIsExposedToACrossOriginBrowser(@TempDir Path dir, @TempDir Path root) throws Exception {
        try (Ctx c = open(dir, root, "http://localhost:4204")) {
            assertEquals(200, write(c.port, pipeline("p6", 1), null).statusCode());

            String exposed = read(c.port, "pipeline", "p6")
                    .headers().firstValue("Access-Control-Expose-Headers").orElse("");
            assertTrue(exposed.contains("ETag"),
                    "a browser cannot read the ETag it must send back unless it is exposed; got: " + exposed);
            assertTrue(exposed.contains(", ETag") || exposed.startsWith("ETag"),
                    "comma-space separated, so the header stays a valid list: " + exposed);
        }
    }
}
