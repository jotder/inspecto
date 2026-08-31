package com.gamma.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.etl.TestConfigs;
import com.gamma.metrics.MetricRegistry;
import com.gamma.service.BundleExporter;
import com.gamma.service.DataSourceBundle;
import com.gamma.service.SpaceManager;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips a data-source bundle over real HTTP: export from one space, import into another, and prove
 * conflict handling — a clashing re-import 409s, and {@code ?on_conflict=overwrite} replaces.
 */
class ControlApiBundleImportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();

    private record Ctx(SpaceManager spaces, ControlApi api, int port) implements AutoCloseable {
        public void close() { api.close(); spaces.close(); MetricRegistry.global().reset(); }
    }

    private Ctx open(Path root) throws Exception {
        Path config = root.resolve("alpha").resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.createDirectories(root.resolve("beta").resolve("config"));   // empty target space

        SpaceManager spaces = SpaceManager.discover(root);
        ControlApi api = new ControlApi(spaces, 0);
        spaces.startAll();
        api.start();
        return new Ctx(spaces, api, api.port());
    }

    @Test
    void exportsFromOneSpaceAndImportsIntoAnotherWithConflictHandling(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(), "beta starts empty");

            // export test_etl from alpha
            byte[] bundle = getBytes(c.port, "/spaces/alpha/datasources/test_etl/export").body();

            // import into beta → the pipeline is registered and live there
            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(200, imp.statusCode(), imp.body());
            assertTrue(V1Body.of(imp.body()).get("pipelines").toString().contains("test_etl"));
            assertTrue(idList(c.port, "/spaces/beta/datasources").contains("test_etl"),
                    "beta now hosts the imported data source");

            // re-import without overwrite → 409 listing the clash, nothing changes
            HttpResponse<String> clash = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(409, clash.statusCode());
            assertTrue(V1Body.envelope(clash.body()).get("error").get("details")
                    .get("conflicts").toString().contains("test_etl"));

            // re-import with overwrite → 200
            assertEquals(200, post(c.port, "/spaces/beta/import?on_conflict=overwrite", bundle).statusCode());
        }
    }

    @Test
    void previewsAnImportWithoutWriting(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = getBytes(c.port, "/spaces/alpha/datasources/test_etl/export").body();

            HttpResponse<String> pv = post(c.port, "/spaces/beta/import/preview", bundle);
            assertEquals(200, pv.statusCode(), pv.body());
            JsonNode r = V1Body.of(pv.body());
            assertTrue(r.get("dataSources").toString().contains("test_etl"), "lists the bundled data source");
            assertTrue(r.get("conflicts").isEmpty(), "no clash in empty beta");
            assertTrue(r.get("valid").asBoolean(), "the exported pipeline validates: " + r.get("findings"));

            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(), "preview wrote nothing");
        }
    }

    @Test
    void createsANewSpaceFromAWholeSpaceBundle(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] spaceBundle = getBytes(c.port, "/spaces/alpha/export").body();

            HttpResponse<String> created = post(c.port, "/spaces/import?id=gamma", spaceBundle);
            assertEquals(200, created.statusCode(), created.body());
            assertEquals("gamma", V1Body.of(created.body()).get("id").asText());

            // the new space is hosted and carries alpha's data source
            JsonNode spaces = V1Body.of(getBytes(c.port, "/spaces").body());
            boolean hasGamma = false;
            for (JsonNode s : spaces) if ("gamma".equals(s.get("id").asText())) hasGamma = true;
            assertTrue(hasGamma, "gamma is now hosted");
            assertTrue(idList(c.port, "/spaces/gamma/datasources").contains("test_etl"),
                    "the bundled data source booted in the new space");

            // a clashing id → 409
            assertEquals(409, post(c.port, "/spaces/import?id=gamma", spaceBundle).statusCode());
            // a missing/invalid id → 400
            assertEquals(400, post(c.port, "/spaces/import", spaceBundle).statusCode());
        }
    }

    /**
     * W3, 2026-07-31 — the import-time referential-integrity gate. Before it, a bundle naming a connection
     * nobody has was written and then registered in *manifest order*, so the failure surfaced one file at a
     * time as a 422 from `registerPipeline` (or not until the first poll), potentially after other pipelines
     * in the same bundle had already gone live. Now nothing is registered unless every reference resolves.
     */
    @Test
    void aBundleNamingAnUnknownConnectionIsRejectedAndRegistersNothing(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = bundleReferencingConnection(root, "absent_conn", false);

            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(422, imp.statusCode(), imp.body());
            String details = V1Body.envelope(imp.body()).get("error").get("details").toString();
            assertTrue(details.contains("absent_conn"), "names the unresolvable connection: " + details);
            assertTrue(details.contains("collector.connection"), "attributes it to the right key: " + details);

            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(),
                    "nothing was registered — the gate is all-or-nothing, not best-effort");
        }
    }

    /**
     * PATH-2 tier 2, 2026-08-14 — the import gate asked whether a schema reference EXISTS but never whether
     * it is CONTAINED, so a bundle naming a schema outside the allowed roots passed here and was refused
     * later by {@code registerPipeline}, one file at a time: the mid-walk partial registration this gate
     * exists to prevent. The referenced file is deliberately created, so the existence half passes and only
     * the containment half can be what fires.
     */
    @Test
    void aBundleWhoseSchemaRefEscapesTheAllowedRootsIsRejectedAndRegistersNothing(
            @TempDir Path root, @TempDir Path elsewhere) throws Exception {
        // ⚠ The surefire config allows the WHOLE temp dir, so nothing under a @TempDir escapes by
        // default — an "outside/" subdir inside `root` is still contained, and this test passed
        // vacuously until the roots were narrowed. Narrow them to `root`, and put the schema under a
        // second @TempDir that is therefore genuinely outside. ⛔ Restore the previous value rather than
        // clearing it: surefire reuses one JVM per module and a cleared root poisons every later test.
        String prior = System.getProperty("assist.safety.roots");
        System.setProperty("assist.safety.roots", root.toAbsolutePath().toString());
        try (Ctx c = open(root)) {
            Path escaping = elsewhere.resolve("stolen_schema.toon");
            Files.writeString(escaping, PipelineConfigBatchTest.miniSchema());

            byte[] bundle = bundleWithSchemaRef(root, escaping.toAbsolutePath().toString());

            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(422, imp.statusCode(), imp.body());
            String details = V1Body.envelope(imp.body()).get("error").get("details").toString();
            // Attribute it, or this passes for the wrong reason — every other path in the bundle is
            // under `root` and contained, so schema_file must be what fired.
            assertTrue(details.contains("processing.schema_file"), "names the escaping key: " + details);
            assertTrue(details.contains("outside the allowed roots"), "says WHY it was refused: " + details);
            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(),
                    "nothing was registered — containment is part of the all-or-nothing gate");

            // Preview must say the same thing, or `valid: true` invites a 422. Containment needs the roots,
            // not the filesystem, so unlike existence it IS answerable before the files land.
            HttpResponse<String> pv = post(c.port, "/spaces/beta/import/preview", bundle);
            assertEquals(200, pv.statusCode(), pv.body());
            assertFalse(V1Body.of(pv.body()).get("valid").asBoolean(),
                    "preview and commit must agree about an escaping ref: " + pv.body());
        } finally {
            if (prior == null) System.clearProperty("assist.safety.roots");
            else System.setProperty("assist.safety.roots", prior);
        }
    }

    /** A bundle that brings its own connection is complete, even though it is not registered yet when the
     *  gate runs — the check is the union of the target's registry and the bundle's own contents. */
    @Test
    void aBundleCarryingItsOwnConnectionImportsCleanly(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = bundleReferencingConnection(root, "carried_conn", true);

            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(200, imp.statusCode(), imp.body());
            assertTrue(idList(c.port, "/spaces/beta/datasources").contains("test_etl"),
                    "the pipeline registered even though its connection was only in the bundle");
        }
    }

    /** Preview must agree with commit about a missing connection, or `valid:true` invites a 422. */
    @Test
    void previewReportsAnUnknownConnectionToo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = bundleReferencingConnection(root, "absent_conn", false);

            HttpResponse<String> pv = post(c.port, "/spaces/beta/import/preview", bundle);
            assertEquals(200, pv.statusCode(), pv.body());
            JsonNode r = V1Body.of(pv.body());
            assertFalse(r.get("valid").asBoolean(), "preview agrees the bundle is not importable");
            assertTrue(r.get("findings").toString().contains("absent_conn"), r.get("findings").toString());

            // And the same bundle carrying the connection previews clean.
            JsonNode ok = V1Body.of(post(c.port, "/spaces/beta/import/preview",
                    bundleReferencingConnection(root, "carried_conn", true)).body());
            assertTrue(ok.get("valid").asBoolean(), "findings: " + ok.get("findings"));
        }
    }

    /**
     * Build a data-source bundle whose pipeline binds {@code connId}, optionally including the connection
     * file. Constructed directly rather than exported from a live space on purpose: a space whose pipeline
     * names a missing connection would not boot, so exporting could not produce this bundle.
     */
    /** A one-pipeline bundle whose {@code processing.schema_file} is rewritten to {@code ref}. No schema
     *  rides along — {@code ref} is meant to point somewhere the bundle does not carry. */
    private static byte[] bundleWithSchemaRef(Path root, String ref) throws Exception {
        Path config = root.resolve("scratch-schema-ref").resolve("config");
        Files.createDirectories(config);
        Path pipeline = config.resolve("etl_pipeline.toon");
        Files.move(TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write(), pipeline);
        Files.writeString(pipeline, Files.readString(pipeline)
                .replaceAll("(?m)^(\\s*schema_file:).*$", "$1 " + java.util.regex.Matcher.quoteReplacement(ref)));
        return BundleExporter.exportDataSource(
                new DataSourceBundle("test_etl", pipeline, null, java.util.List.of(),
                        java.util.List.of(), java.util.List.of()), config, "alpha");
    }

    private static byte[] bundleReferencingConnection(Path root, String connId, boolean carryConnection)
            throws Exception {
        Path config = root.resolve("scratch-" + connId).resolve("config");
        Files.createDirectories(config);
        // TestConfigs writes `pipeline_<hash>.toon`; the `*_pipeline.toon` suffix is what marks a file as a
        // pipeline to the importer (and to the gate), so the rename is load-bearing, not cosmetic.
        Path pipeline = config.resolve("etl_pipeline.toon");
        Files.move(TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write(), pipeline);
        Files.writeString(pipeline, Files.readString(pipeline)
                + "\ncollector:\n  id: test_etl\n  connector: sftp\n  connection: " + connId + "\n");

        Path conn = null;
        if (carryConnection) {
            conn = config.resolve(connId + "_connection.toon");
            Files.writeString(conn, "connection:\n  id: " + connId + "\n  connector: sftp\n"
                    + "  host: sftp.example.com\n  password: ${ENV:PW}\n");
        }
        // The schema TestConfigs wrote beside the pipeline must ride along, or the gate's schema half fires.
        java.util.List<Path> schemas = new java.util.ArrayList<>();
        try (var s = Files.list(config)) {
            s.filter(f -> f.getFileName().toString().startsWith("schema_")).forEach(schemas::add);
        }
        return BundleExporter.exportDataSource(
                new DataSourceBundle("test_etl", pipeline, conn, schemas, java.util.List.of(), java.util.List.of()), config, "alpha");
    }

    // ── apply order (pipeline spec gap 6c) ───────────────────────────────────────────────────────────

    /**
     * <b>A referenced kind must be applied before its referencer.</b> That is the invariant the import
     * order exists for, and the one {@code mapping} broke: it is a live component kind
     * ({@code ComponentStore.WRITABLE_TYPES}) that a pipeline names, but it never appeared in
     * {@code APPLY_ORDER}, so {@code orderOf} sorted it LAST — after the pipeline referencing it.
     * {@code grammar}, the companion beside it, was ordered correctly all along, which is what made the
     * omission easy to miss.
     *
     * <p>🔴 <b>{@code schema} is now REQUIRED here, and this assertion was inverted on 2026-08-31</b>
     * (BUNDLE-SCHEMA-1). It previously demanded schema's ABSENCE, on the belief the kind was retired by
     * unification W1 (2026-07-31). That retirement was <b>reversed five days later</b>:
     * {@code ComponentStore.WRITABLE_TYPES} records {@code schema} re-added 2026-08-05 with the original
     * objection resolved — {@code processing.schema_file: schema/<id>} resolves to
     * {@code registry/schema/<id>}, so a registry schema IS the executed schema. It is therefore a
     * referenced kind exactly like {@code grammar}, and excluding it meant a pipeline was written before
     * the schema it parses with.
     *
     * <p>⚠ <b>Deliberately not "every supported kind must be listed".</b> Omission means "apply last",
     * which is the CORRECT place for a kind that references a pipeline rather than being referenced by
     * one — {@code expectation} and {@code decision-rule} are right to be absent. A test demanding
     * completeness would push those into the wrong half of the order.
     */
    @Test
    void aPipelinesCompanionsAreAppliedBeforeThePipeline() {
        int pipeline = BundleRoutes.APPLY_ORDER.indexOf("authored-pipeline");
        assertTrue(pipeline >= 0, "authored-pipeline must be ordered at all");
        for (String companion : java.util.List.of("grammar", "mapping", "schema", "connection")) {
            int at = BundleRoutes.APPLY_ORDER.indexOf(companion);
            assertTrue(at >= 0, companion + " is a supported kind an authored pipeline references, so "
                    + "leaving it out of APPLY_ORDER applies it AFTER the pipeline that needs it");
            assertTrue(at < pipeline, companion + " must be applied before authored-pipeline, got "
                    + at + " vs " + pipeline + " in " + BundleRoutes.APPLY_ORDER);
        }
    }

    /** A job may reference the pipeline it triggers on, so it follows one. */
    @Test
    void aJobIsAppliedAfterThePipelineItMayTriggerOn() {
        assertTrue(BundleRoutes.APPLY_ORDER.indexOf("job")
                        > BundleRoutes.APPLY_ORDER.indexOf("authored-pipeline"),
                BundleRoutes.APPLY_ORDER.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private java.util.List<String> idList(int port, String path) throws Exception {
        JsonNode arr = V1Body.of(getBytes(port, path).body());
        java.util.List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private HttpResponse<byte[]> getBytes(int port, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .GET().build(), BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> post(int port, String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/zip")
                .POST(BodyPublishers.ofByteArray(body)).build(), BodyHandlers.ofString());
    }
}
