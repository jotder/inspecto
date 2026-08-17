package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.io.ConfigLoader;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@code POST /config/write} (v4.1.0, scope {@code assist.write}) over real
 * HTTP: the author→save loop that persists a validated config draft to a {@code .toon} file under
 * the {@code -Dassist.write.root} jail. Covers the fail-closed gate ordering (writes disabled →
 * scope → safety gate → identity-derived filename → path jail → overwrite policy) and that the
 * written TOON round-trips back off disk.
 */
class ControlApiConfigWriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(CollectorService svc, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); svc.close(); }
    }

    /** Boot a server. {@code writeRoot==null} ⇒ writes disabled. */
    private Ctx open(Path configDir, Path writeRoot) throws Exception {
        Path pipe = PipelineConfigBatchTest.writePipeline(configDir, "");
        if (writeRoot != null) System.setProperty("assist.write.root", writeRoot.toString());
        else System.clearProperty("assist.write.root");
        try {
            CollectorService svc = new CollectorService(List.of(pipe), 3600, 1);
            // The write root is read in the constructor, so it is captured here regardless of the
            // clear in finally (which only keeps the JVM-wide property from leaking to other tests).
            ControlApi api = new ControlApi(svc, 0);
            api.start();
            return new Ctx(svc, api, api.port());
        } finally {
            System.clearProperty("assist.write.root");
        }
    }

    private HttpResponse<String> post(int port, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
        return client.send(b.method("POST", BodyPublishers.ofString(body)).build(), BodyHandlers.ofString());
    }

    private static String pipeline(String name) {
        return """
                {"type":"pipeline","config":{
                   "name":"%s",
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1}}}""".formatted(name);
    }

    @Test
    void disabledWhenNoWriteRootConfigured(@TempDir Path cfg) throws Exception {
        try (Ctx c = open(cfg, null)) {
            HttpResponse<String> r = post(c.port, "/config/write", pipeline("orders"));
            assertEquals(503, r.statusCode(), "no -Dassist.write.root ⇒ writes disabled");
        }
    }

    @Test
    void writesValidPipelineAndRoundTripsOffDisk(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", pipeline("orders_daily"));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertEquals("orders_daily", out.get("name").asText());
            // Pipeline files are named for the bootstrap-scan convention (*_pipeline.toon) so a
            // written config is picked up on the next service restart; name stays the identity.
            assertEquals("orders_daily_pipeline.toon", out.get("path").asText());
            assertFalse(out.get("overwritten").asBoolean());

            Path written = root.resolve("orders_daily_pipeline.toon");
            assertTrue(Files.exists(written), "file persisted under the write root");
            // The encoded TOON decodes back to the same config (round-trip correctness).
            Map<String, Object> decoded = ConfigLoader.filesystem().decode(written.toString());
            assertEquals("orders_daily", decoded.get("name"));
        }
    }

    @Test
    void refusesOverwriteByDefaultThenAllowsWithFlag(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/config/write", pipeline("dup")).statusCode());
            assertEquals(409, post(c.port, "/config/write", pipeline("dup")).statusCode(),
                    "existing file refused without overwrite");
            String withFlag = """
                    {"type":"pipeline","overwrite":true,"config":{
                       "name":"dup","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = V1Body.of(post(c.port, "/config/write", withFlag).body());
            assertTrue(out.get("written").asBoolean());
            assertTrue(out.get("overwritten").asBoolean(), "overwrite:true replaces");
        }
    }

    @Test
    void errorConfigIs422AndWritesNothing(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            // ingester set but no segments → the plugin-ingester-requires-segments ERROR finding.
            String bad = """
                    {"type":"pipeline","config":{
                       "name":"broken","dirs":{"poll":"in","database":"out"},
                       "processing":{"ingester":"com.x.Plugin","threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", bad);
            assertEquals(422, r.statusCode(), r.body());
            // v1 errors carry the rejected write's payload under error.details.
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertFalse(out.get("written").asBoolean());
            assertTrue(out.get("findings").size() > 0, "findings returned");
            assertFalse(Files.exists(root.resolve("broken.toon")), "nothing written on a rejected config");
        }
    }

    /**
     * G4: {@code active: true} with no schema source loads nowhere, so accepting the write means the
     * pipeline is dropped from the index and skipped by the scheduler forever, silently.
     */
    @Test
    void armedPipelineWithoutASchemaIs422(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String armed = """
                    {"type":"pipeline","config":{
                       "name":"armed_no_schema","active":true,
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", armed);
            assertEquals(422, r.statusCode(), r.body());
            JsonNode out = V1Body.envelope(r.body()).get("error").get("details");
            assertTrue(out.get("findings").toString().contains("no schema is configured"),
                    "the finding names the missing schema: " + out.get("findings"));
            assertFalse(Files.exists(root.resolve("armed_no_schema_pipeline.toon")));
        }
    }

    /** The same draft stays writable while inactive — that is the whole point of a draft. */
    @Test
    void inactiveDraftWithoutASchemaStillWrites(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String draft = """
                    {"type":"pipeline","config":{
                       "name":"draft_no_schema","active":false,
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", draft);
            assertEquals(200, r.statusCode(), r.body());
            assertTrue(Files.exists(root.resolve("draft_no_schema_pipeline.toon")));
        }
    }

    /**
     * An armed pipeline whose schema reference does not resolve on <em>this</em> host stays a
     * WARNING and is written — the new gate must not swallow that deliberate distinction.
     */
    @Test
    void armedPipelineWithAnUnresolvableSchemaIsStillOnlyAWarning(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String armed = """
                    {"type":"pipeline","config":{
                       "name":"armed_elsewhere","active":true,
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"nowhere/orders_schema.toon","threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", armed);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertTrue(out.get("findings").size() > 0, "the unresolvable reference is still reported");
        }
    }

    /**
     * 🔴 W3. The UI now writes the PORTABLE form of a schema reference — a bare {@code <name>.toon}
     * that resolves beside its own config file. The pre-flight check used to run BEFORE the target
     * path was resolved, so it had no directory to be relative to and fell back to the server's
     * working directory: every portable reference was reported "does not resolve", on every save.
     *
     * <p>The two writes here are ordered exactly as the Parse drawer orders them — schema first,
     * then the pipeline naming it — which is the only order in which the file can already exist.
     */
    @Test
    void aPortableSchemaReferenceBesideTheConfigIsNotWarnedAbout(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            String schema = """
                    {"type":"schema","config":{"raw":{"name":"portable_schema","format":"CSV",
                       "fields":[{"name":"ID","selector":"0","type":"VARCHAR"}]}}}""";
            assertEquals(200, post(c.port, "/config/write", schema).statusCode());

            String armed = """
                    {"type":"pipeline","config":{
                       "name":"portable","active":true,
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"schema_file":"portable_schema.toon","threads":1}}}""";
            HttpResponse<String> r = post(c.port, "/config/write", armed);
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertTrue(out.get("written").asBoolean());
            assertFalse(out.get("findings").toString().contains("does not resolve"),
                    "a bare basename beside the config resolves; nothing to warn about: " + out.get("findings"));
        }
    }

    @Test
    void pathJailRejectsEscapingSubdirAndAbsolute(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String escaping = """
                    {"type":"pipeline","subdir":"../escape","config":{
                       "name":"x","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(403, post(c.port, "/config/write", escaping).statusCode(),
                    "subdir escaping the root is blocked");
            // A rooted subdir: absolute on POSIX (→400 'must be relative'); on Windows "/etc" is
            // drive-relative (isAbsolute()==false) so it falls through to the jail (→403 escapes root).
            // Either way it is blocked from escaping the write root.
            String absolute = """
                    {"type":"pipeline","subdir":"/etc","config":{
                       "name":"x","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            int code = post(c.port, "/config/write", absolute).statusCode();
            assertTrue(code == 400 || code == 403, "rooted subdir is rejected (got " + code + ")");
        }
    }

    @Test
    void unsafeIdentityNameRejected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String traversalName = """
                    {"type":"pipeline","config":{
                       "name":"../../etc/passwd","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(422, post(c.port, "/config/write", traversalName).statusCode(),
                    "a config name with path separators / .. is rejected");
        }
    }

    /** A pipeline carrying an explicit identity, plus a display `name` that differs from it. */
    private static String pipelineWithId(String id, String name) {
        return """
                {"type":"pipeline","config":{
                   "name":"%s","id":"%s",
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1}}}""".formatted(name, id);
    }

    /**
     * A pipeline's filename comes from its {@code id} — the field {@code PipelineRoutes.rename} already
     * named the file from, so create and rename finally agree on one name. Until 2026-08-17 create used
     * {@code name}, which has no pattern of its own.
     */
    @Test
    void pipelineFilenameComesFromTheIdNotTheDisplayName(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", pipelineWithId("orders_daily", "Orders Daily"));
            assertEquals(200, r.statusCode(), r.body());
            JsonNode out = V1Body.of(r.body());
            assertEquals("orders_daily", out.get("name").asText());
            assertEquals("orders_daily_pipeline.toon", out.get("path").asText());
            assertTrue(Files.exists(root.resolve("orders_daily_pipeline.toon")));
            // The display name survives verbatim; only the FILE stopped being keyed by it.
            assertEquals("Orders Daily",
                    ConfigLoader.filesystem().decode(root.resolve("orders_daily_pipeline.toon").toString()).get("name"));
        }
    }

    /**
     * The case that used to be unwritable: a space is legal in a display `name` (no pattern) but illegal in
     * a filename, so keying the file off `name` 422'd every such pipeline before the id existed.
     */
    @Test
    void aDisplayNameWithASpaceIsWritableOnceAnIdCarriesIdentity(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(422, post(c.port, "/config/write", pipeline("My Pipe")).statusCode(),
                    "no id ⇒ the filename still comes from `name`, and a space is not a safe name");
            HttpResponse<String> r = post(c.port, "/config/write", pipelineWithId("my_pipe", "My Pipe"));
            assertEquals(200, r.statusCode(), r.body());
            assertEquals("my_pipe_pipeline.toon", V1Body.of(r.body()).get("path").asText(), r.body());
            assertTrue(Files.exists(root.resolve("my_pipe_pipeline.toon")));
        }
    }

    /**
     * A pipeline written before the id was stamped at birth lives under a name-derived filename. Editing it
     * must UPDATE that file, not fork a second config beside it under the id.
     *
     * <p>⚠ The two candidates differ by more than CASE on purpose. An earlier version of this test used
     * {@code Legacy}/{@code legacy}, and on a case-insensitive filesystem (Windows — where this team's
     * gate runs) the id-cased path already resolves to the legacy file, so {@code Files.exists(target)}
     * is true and the fallback branch never executes. It asserted a file count that held either way and
     * therefore pinned nothing on the platform it actually ran on.
     */
    @Test
    void anIdStampedOntoALegacyConfigKeepsEditingTheExistingFile(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            assertEquals(200, post(c.port, "/config/write", pipeline("Legacy-Feed")).statusCode());
            assertTrue(Files.exists(root.resolve("Legacy-Feed_pipeline.toon")), "legacy file is named for `name`");

            String stamped = """
                    {"type":"pipeline","overwrite":true,"config":{
                       "name":"Legacy-Feed","id":"legacy_feed",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = V1Body.of(post(c.port, "/config/write", stamped).body());
            assertTrue(out.get("overwritten").asBoolean(), "the existing file was updated");
            assertEquals("Legacy-Feed_pipeline.toon", out.get("path").asText(), "the legacy file is edited in place");
            // The reported identity stays the ID even though the FILE kept its legacy name — a caller
            // using this to address the pipeline afterwards must get the key the routes are keyed by.
            assertEquals("legacy_feed", out.get("name").asText());
            try (var entries = Files.list(root)) {
                assertEquals(1, entries.filter(p -> p.toString().endsWith(".toon")).count(),
                        "no second config forked beside the legacy one");
            }
        }
    }

    /**
     * ⛔ The legacy-filename fallback matches on the display LABEL, and a label is not unique. A file that
     * declares an identity of its own is definitively not "this config before it had an id", so it is
     * never adopted — otherwise a write to one pipeline would destroy another that merely shares a name,
     * silently and with {@code written:true}.
     *
     * <p>⚠ The residual case the server cannot decide: a legacy file with NO {@code id:} at all is
     * indistinguishable from "the same pipeline gaining an id", because the request carries nothing that
     * separates them. Closing that needs the caller to name the file it means — BACKLOG §6 WRITE-1.
     */
    @Test
    void aPipelineDeclaringADifferentIdIsNeverAdoptedAsTheLegacyFile(@TempDir Path cfg, @TempDir Path root)
            throws Exception {
        try (Ctx c = open(cfg, root)) {
            // A config already keyed by its own id, but stored under a display-name filename.
            String legacy = """
                    {"type":"pipeline","config":{
                       "name":"Orders","id":"orders",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(200, post(c.port, "/config/write", legacy).statusCode());
            Files.move(root.resolve("orders_pipeline.toon"), root.resolve("Orders_pipeline.toon"));
            String legacyBefore = Files.readString(root.resolve("Orders_pipeline.toon"));

            String other = """
                    {"type":"pipeline","overwrite":true,"config":{
                       "name":"Orders","id":"orders_v2",
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = V1Body.of(post(c.port, "/config/write", other).body());
            assertEquals("orders_v2_pipeline.toon", out.get("path").asText());
            assertFalse(out.get("overwritten").asBoolean(), "a brand-new file, not the one named 'Orders'");
            assertEquals(legacyBefore, Files.readString(root.resolve("Orders_pipeline.toon")),
                    "the other pipeline's config is untouched");
        }
    }

    /** A pipeline whose collector binds a connection by id. */
    private static String pipelineBoundTo(String name, String connectionId) {
        return """
                {"type":"pipeline","config":{
                   "name":"%s",
                   "collector":{"connector":"sftp","connection":"%s"},
                   "dirs":{"poll":"in","database":"out"},
                   "processing":{"threads":1}}}""".formatted(name, connectionId);
    }

    /**
     * A {@code collector.connection} naming a profile this space does not have is refused at save. It was
     * accepted until 2026-08-11 and surfaced only at poll time, where the connector factory throws once per
     * cycle — bundle import has always refused the same thing, so the two write paths now agree.
     */
    @Test
    void unknownCollectorConnectionIsRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            HttpResponse<String> r = post(c.port, "/config/write", pipelineBoundTo("remote", "ghost"));
            assertEquals(422, r.statusCode(), r.body());
            // A 422 is the v1 error object, not an unwrapped resource: the refusal detail sits under
            // error.details, which is where `written` and `findings` live.
            JsonNode details = V1Body.of(r.body()).get("error").get("details");
            assertFalse(details.get("written").asBoolean(), "nothing is written when the binding dangles");
            assertTrue(details.get("findings").toString().contains("collector.connection"),
                    "the finding names the offending field: " + details.get("findings"));
            assertFalse(Files.exists(root.resolve("remote_pipeline.toon")));
        }
    }

    /** The refusal is about the id being unresolvable, not about binding a connection at all. */
    @Test
    void knownCollectorConnectionSaves(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            c.svc.registerConnection(new com.gamma.acquire.ConnectionProfile(
                    "cdr_sftp", "sftp", "sftp.example.test", 22, null,
                    "/in", "svc", "${ENV:SFTP_PW}", Map.of(), null));
            assertEquals(200, post(c.port, "/config/write", pipelineBoundTo("remote", "cdr_sftp")).statusCode());
            assertTrue(Files.exists(root.resolve("remote_pipeline.toon")));
        }
    }

    /**
     * A stale {@code connection} on a LOCAL collector still saves. The local connector never resolves the
     * binding — {@code CollectorConnectors.forConfig} short-circuits before looking it up — so the field is
     * inert, not broken, and refusing it would reject configs that run today (it broke five
     * {@code /config/patch} fixtures shaped exactly like this).
     */
    @Test
    void aStaleConnectionOnALocalCollectorIsNotRefused(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String localWithStaleRef = """
                    {"type":"pipeline","config":{
                       "name":"inbox","collector":{"connector":"local","connection":"old_conn"},
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(200, post(c.port, "/config/write", localWithStaleRef).statusCode());
        }
    }

    /** A collector with no connection at all is untouched by the check (the local-inbox shape). */
    @Test
    void aCollectorWithNoConnectionIsUnaffected(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String local = """
                    {"type":"pipeline","config":{
                       "name":"local","collector":{"connector":"local"},
                       "dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            assertEquals(200, post(c.port, "/config/write", local).statusCode());
        }
    }

    @Test
    void writesIntoAJailedSubdir(@TempDir Path cfg, @TempDir Path root) throws Exception {
        try (Ctx c = open(cfg, root)) {
            String inSub = """
                    {"type":"pipeline","subdir":"team/etl","config":{
                       "name":"nested","dirs":{"poll":"in","database":"out"},
                       "processing":{"threads":1}}}""";
            JsonNode out = V1Body.of(post(c.port, "/config/write", inSub).body());
            assertEquals("team/etl/nested_pipeline.toon", out.get("path").asText());
            assertTrue(Files.exists(root.resolve("team").resolve("etl").resolve("nested_pipeline.toon")));
        }
    }
}
