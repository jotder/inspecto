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
        return open(root, false);
    }

    /**
     * @param readsAReference when true, alpha's {@code test_etl} joins {@code reference/region_dim}, alpha also
     *                        hosts the {@code REGION_DIM} Reference producer, and a second empty target
     *                        space {@code gamma} exists
     */
    private Ctx open(Path root, boolean readsAReference) throws Exception {
        Path config = root.resolve("alpha").resolve("config");
        Files.createDirectories(config);
        Path tmp = TestConfigs.csv(config, PipelineConfigBatchTest.miniSchema()).write();
        Files.move(tmp, config.resolve("etl_pipeline.toon"));
        Files.createDirectories(root.resolve("beta").resolve("config"));   // empty target space
        if (readsAReference) {
            // Inactive: a join without output_store cannot arm (PipelineConfig.prepare).
            Path etl = config.resolve("etl_pipeline.toon");
            Files.writeString(etl, Files.readString(etl).replace("active: true", "active: false")
                    .replace("processing:\n", "processing:\n  join:\n    reference: reference/region_dim\n    on: ID\n"));
            Path region = TestConfigs.csv(config.resolve("region"), PipelineConfigBatchTest.miniSchema())
                    .name("REGION_DIM").write();
            Files.writeString(config.resolve("region").resolve("region_pipeline.toon"),
                    "produces: reference\n" + Files.readString(region).replace("active: true", "active: false"));
            Files.delete(region);
            Files.createDirectories(root.resolve("gamma").resolve("config"));
        }

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

    /**
     * W4/W5 — Alert Rules and Expectations that reference the data source travel with it, next to the
     * Decision Rules and Datasets W3 already bundles. An Alert Rule references the pipeline through
     * {@code onPipeline}, or a BUNDLED Dataset through {@code dataset}; an Expectation through
     * {@code targetType}/{@code target}. A global Alert Rule (no {@code onPipeline}) and another
     * pipeline's components stay behind.
     */
    @Test
    void anExportCarriesTheAlertRulesAndExpectationsThatReferenceItAndAnImportRoundTripsThem(
            @TempDir Path root) throws Exception {
        Path reg = root.resolve("alpha").resolve("config").resolve("registry");
        java.util.Map<String, String> ours = new java.util.LinkedHashMap<>();
        ours.put("datasets/test_ds.toon", "name: test_ds\nphysicalRef: test_etl\n");
        ours.put("alert-rules/test_errors.toon", """
                name: test_errors
                metric: error_rate
                comparator: gt
                threshold: 0.05
                window: 1h
                severity: WARNING
                onPipeline: TEST_ETL
                """);
        ours.put("alert-rules/test_low_rows.toon", """
                name: test_low_rows
                dataset: test_ds
                measure: count
                comparator: lt
                threshold: 1
                severity: WARNING
                """);
        ours.put("expectations/test_id_non_null.toon",
                "name: test_id_non_null\ntargetType: pipeline\ntarget: test_etl\ncolumn: ID\nkind: non_null\n");
        java.util.Map<String, String> theirs = new java.util.LinkedHashMap<>();
        theirs.put("alert-rules/global_errors.toon",   // no onPipeline = every pipeline: nobody's to carry
                "name: global_errors\nmetric: error_rate\ncomparator: gt\nthreshold: 0.5\nwindow: 1h\nseverity: WARNING\n");
        theirs.put("alert-rules/other_errors.toon",
                "name: other_errors\nmetric: error_rate\ncomparator: gt\nthreshold: 0.5\nwindow: 1h\nseverity: WARNING\nonPipeline: other_etl\n");
        theirs.put("alert-rules/other_ds_rows.toon",   // a Dataset this bundle does not carry
                "name: other_ds_rows\ndataset: other_ds\nmeasure: count\ncomparator: lt\nthreshold: 1\nseverity: WARNING\n");
        theirs.put("expectations/other_id_non_null.toon",
                "name: other_id_non_null\ntargetType: pipeline\ntarget: other_etl\ncolumn: ID\nkind: non_null\n");
        for (var m : java.util.List.of(ours, theirs)) {
            for (var e : m.entrySet()) {
                Path p = reg.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue());
            }
        }

        try (Ctx c = open(root)) {
            byte[] bundle = getBytes(c.port, "/spaces/alpha/datasources/test_etl/export").body();
            java.util.Set<String> names = new java.util.HashSet<>();
            try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bundle))) {
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) names.add(e.getName());
            }
            for (String k : ours.keySet()) assertTrue(names.contains("registry/" + k), k + " in " + names);
            for (String k : theirs.keySet()) assertFalse(names.contains("registry/" + k), k + " in " + names);

            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(200, imp.statusCode(), imp.body());

            // FILE-level truth: each component landed in beta's registry byte-for-byte, the others did not.
            Path betaReg = root.resolve("beta").resolve("config").resolve("registry");
            for (var e : ours.entrySet()) {
                assertEquals(e.getValue(), Files.readString(betaReg.resolve(e.getKey())), e.getKey());
            }
            for (String k : theirs.keySet()) assertFalse(Files.exists(betaReg.resolve(k)), k);
        }
    }

    /**
     * W5 forward closure, 2026-09-24 — an export carries the Reference Dataset its pipeline READS (as the
     * {@code produces: reference} pipeline producing it), and the import round-trips it: into a space without
     * that Reference both pipelines land; into a space that already hosts it, the target's own copy is kept —
     * a 200 naming it in {@code referencesKept}, never a 409 on a pipeline the data source merely reads.
     */
    @Test
    void anExportCarriesTheReferenceItReadsAndAnImportRoundTripsOrKeepsTheTargetsOwn(@TempDir Path root)
            throws Exception {
        try (Ctx c = open(root, true)) {
            byte[] bundle = getBytes(c.port, "/spaces/alpha/datasources/test_etl/export").body();
            java.util.Set<String> names = new java.util.HashSet<>();
            try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bundle))) {
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) names.add(e.getName());
            }
            assertTrue(names.contains("region/region_pipeline.toon"), names.toString());

            // Fresh target: the data source AND the Reference it reads both land and register.
            HttpResponse<String> fresh = post(c.port, "/spaces/gamma/import", bundle);
            assertEquals(200, fresh.statusCode(), fresh.body());
            assertEquals(java.util.List.of(), JSON.convertValue(V1Body.of(fresh.body()).get("referencesKept"),
                    java.util.List.class));
            assertTrue(idList(c.port, "/spaces/gamma/datasources").containsAll(java.util.List.of("test_etl", "region_dim")),
                    "gamma hosts both");

            // A target that already hosts the Reference: import region_dim on its own first...
            byte[] regionOnly = getBytes(c.port, "/spaces/alpha/datasources/region_dim/export").body();
            assertEquals(200, post(c.port, "/spaces/beta/import", regionOnly).statusCode());
            // ...then the consumer: kept, not clashed — and the preview agrees.
            JsonNode preview = V1Body.of(post(c.port, "/spaces/beta/import/preview", bundle).body());
            assertEquals("[\"region_dim\"]", preview.get("referencesKept").toString());
            assertEquals("[]", preview.get("conflicts").toString());
            HttpResponse<String> kept = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(200, kept.statusCode(), kept.body());
            JsonNode body = V1Body.of(kept.body());
            assertEquals("[\"region_dim\"]", body.get("referencesKept").toString());
            assertEquals("[\"test_etl\"]", body.get("pipelines").toString());
            assertFalse(body.get("imported").toString().contains("region_pipeline.toon"),
                    "the target's own Reference is not rewritten: " + body.get("imported"));
            assertTrue(body.get("imported").toString().contains("etl_pipeline.toon"));
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
     * Operator decision 2026-09-25 — a missing connection WARNS, it no longer refuses. Until then (W3,
     * 2026-07-31) a bundle naming a connection the target lacks was a 422 that registered nothing, which made
     * moving a data source into a Space that has not yet onboarded its connection impossible. Now the import
     * succeeds and every config that needs the connection lands switched OFF by its OWN switch — a pipeline's
     * {@code active}, a job's {@code job.enabled} — with one structured warning per missing connection naming
     * what it disabled. A config that does not need it is left exactly as bundled.
     */
    @Test
    void aBundleNamingAnUnknownConnectionImportsWithItsDependentsDisabledAndAWarning(@TempDir Path root)
            throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = bundleReferencingConnection(root, "absent_conn", false, true);
            Path scratch = root.resolve("scratch-absent_conn").resolve("config");
            assertTrue(Files.readString(scratch.resolve("etl_pipeline.toon")).contains("active: true"),
                    "the bundled pipeline is ACTIVE, or 'lands disabled' would pass vacuously");

            HttpResponse<String> imp = post(c.port, "/spaces/beta/import", bundle);
            assertEquals(200, imp.statusCode(), imp.body());
            JsonNode body = V1Body.of(imp.body());
            assertEquals("[\"test_etl\"]", body.get("pipelines").toString(), "the pipeline still registers");
            assertTrue(idList(c.port, "/spaces/beta/datasources").contains("test_etl"));

            // The switch each kind really reads, on the file that landed.
            Path beta = root.resolve("beta").resolve("config");
            java.util.Map<String, Object> pipeline = com.gamma.config.io.ConfigCodec.toMap(
                    Files.readString(beta.resolve("etl_pipeline.toon")));
            assertEquals("false", String.valueOf(pipeline.get("active")), "pipeline lands inactive: " + pipeline);
            assertFalse(pipeline.containsKey("enabled"), "no cross-kind stamp on a pipeline: " + pipeline);
            java.util.Map<?, ?> job = (java.util.Map<?, ?>) com.gamma.config.io.ConfigCodec.toMap(
                    Files.readString(beta.resolve("export_job.toon"))).get("job");
            assertEquals("false", String.valueOf(job.get("enabled")), "the job needing it lands disabled: " + job);
            assertEquals(Files.readString(scratch.resolve("report_job.toon")),
                    Files.readString(beta.resolve("report_job.toon")),
                    "a job that does not need the connection is untouched, byte for byte");

            JsonNode warnings = body.get("connectionWarnings");
            assertNotNull(warnings, "the response carries the warnings: " + body);
            assertEquals(1, warnings.size(), "one warning per missing connection: " + warnings);
            JsonNode w = warnings.get(0);
            assertEquals("absent_conn", w.get("connection").asText());
            assertEquals("WARN_UNRESOLVED_CONNECTION", w.get("code").asText());
            assertTrue(w.get("message").asText().contains("connect absent_conn to enable"), w.toString());
            java.util.Set<String> disabled = new java.util.HashSet<>();
            w.get("disabled").forEach(d -> disabled.add(d.get("kind").asText() + ":" + d.get("name").asText()
                    + "@" + d.get("file").asText()));
            assertEquals(java.util.Set.of("pipeline:test_etl@etl_pipeline.toon", "job:export_x@export_job.toon"),
                    disabled);
        }
    }

    /** No missing connection ⇒ no warning, and nothing is switched off. */
    @Test
    void aBundleWhoseConnectionsAllResolveCarriesNoWarningsAndStaysActive(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            HttpResponse<String> imp = post(c.port, "/spaces/beta/import",
                    bundleReferencingConnection(root, "carried_conn", true, true));
            assertEquals(200, imp.statusCode(), imp.body());
            assertEquals("[]", V1Body.of(imp.body()).get("connectionWarnings").toString());
            Path beta = root.resolve("beta").resolve("config");
            assertEquals("true", String.valueOf(com.gamma.config.io.ConfigCodec.toMap(
                    Files.readString(beta.resolve("etl_pipeline.toon"))).get("active")));
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

    /**
     * Preview must agree with commit about a missing connection: commit now imports it with its dependents
     * disabled, so preview says {@code valid: true}, reports a WARNING (not an ERROR) finding, and carries the
     * same {@code connectionWarnings} the commit will return.
     */
    @Test
    void previewReportsAnUnknownConnectionAsAWarningToo(@TempDir Path root) throws Exception {
        try (Ctx c = open(root)) {
            byte[] bundle = bundleReferencingConnection(root, "absent_conn", false);

            HttpResponse<String> pv = post(c.port, "/spaces/beta/import/preview", bundle);
            assertEquals(200, pv.statusCode(), pv.body());
            JsonNode r = V1Body.of(pv.body());
            assertTrue(r.get("valid").asBoolean(), "a missing connection no longer blocks the import: " + r);
            String findings = r.get("findings").toString();
            assertTrue(findings.contains("absent_conn") && findings.contains("WARNING")
                    && !findings.contains("ERROR"), findings);
            assertEquals("absent_conn", r.get("connectionWarnings").get(0).get("connection").asText(), r.toString());
            assertTrue(idList(c.port, "/spaces/beta/datasources").isEmpty(), "preview wrote nothing");

            // And the same bundle carrying the connection previews with no warning at all.
            JsonNode ok = V1Body.of(post(c.port, "/spaces/beta/import/preview",
                    bundleReferencingConnection(root, "carried_conn", true)).body());
            assertTrue(ok.get("valid").asBoolean(), "findings: " + ok.get("findings"));
            assertEquals("[]", ok.get("connectionWarnings").toString());
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
                        java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()), config, "alpha");
    }

    private static byte[] bundleReferencingConnection(Path root, String connId, boolean carryConnection)
            throws Exception {
        return bundleReferencingConnection(root, connId, carryConnection, false);
    }

    /** @param withJobs also carry {@code export_job.toon} (an object-store export binding {@code connId}) and
     *                  {@code report_job.toon} (binding nothing) */
    private static byte[] bundleReferencingConnection(Path root, String connId, boolean carryConnection,
                                                      boolean withJobs) throws Exception {
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
        java.util.List<Path> jobs = new java.util.ArrayList<>();
        if (withJobs) {
            Path export = config.resolve("export_job.toon");
            Files.writeString(export, "job:\n  name: export_x\n  type: objectstore.export\n  on_pipeline: test_etl\n"
                    + "  connection: " + connId + "\n  local_path: out\n  remote_prefix: drop/\n");
            Path report = config.resolve("report_job.toon");
            Files.writeString(report, "job:\n  name: report_x\n  type: report\n  on_pipeline: test_etl\n");
            jobs.add(export);
            jobs.add(report);
        }
        return BundleExporter.exportDataSource(
                new DataSourceBundle("test_etl", pipeline, conn, schemas, jobs, java.util.List.of(), java.util.List.of(), java.util.List.of()), config, "alpha");
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
        int pipeline = BundleRoutes.APPLY_ORDER.indexOf("pipeline");
        assertTrue(pipeline >= 0, "pipeline must be ordered at all");
        for (String companion : java.util.List.of("grammar", "mapping", "schema", "connection")) {
            int at = BundleRoutes.APPLY_ORDER.indexOf(companion);
            assertTrue(at >= 0, companion + " is a supported kind an authored pipeline references, so "
                    + "leaving it out of APPLY_ORDER applies it AFTER the pipeline that needs it");
            assertTrue(at < pipeline, companion + " must be applied before pipeline, got "
                    + at + " vs " + pipeline + " in " + BundleRoutes.APPLY_ORDER);
        }
    }

    /** A job may reference the pipeline it triggers on, so it follows one. */
    @Test
    void aJobIsAppliedAfterThePipelineItMayTriggerOn() {
        assertTrue(BundleRoutes.APPLY_ORDER.indexOf("job")
                        > BundleRoutes.APPLY_ORDER.indexOf("pipeline"),
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
