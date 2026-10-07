package com.gamma.service;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolves a pipeline to its cohesive data-source bundle (pipeline + connection + schema(s) + jobs) by
 * walking the references inside a real, booted space's {@code config/} tree.
 */
class DataSourceBundleResolverTest {

    @Test
    void resolvesPipelineConnectionSchemaAndJobsForOneDataSource(@TempDir Path tmp) throws Exception {
        Path base   = tmp.resolve("ds-space");
        Path config = base.resolve("config");
        Files.createDirectories(config);

        // A remote data source: pipeline → connection VOUCHER_CONN → its own schema, plus a job that targets it.
        Path voucherSchema = config.resolve("voucher_schema.toon");
        Files.writeString(voucherSchema, schema("VOUCHER", "voucher"));
        Files.writeString(config.resolve("voucher_pipeline.toon"),
                pipeline("VOUCHER_ETL", voucherSchema, "VOUCHER_CONN", tmp.resolve("voucher")));
        Files.writeString(config.resolve("voucher_conn_connection.toon"), connection("VOUCHER_CONN"));
        Files.writeString(config.resolve("voucher_job.toon"), job("voucher_heartbeat", "voucher_etl")); // lowercased

        // A second, unrelated local data source — proves the bundle does not bleed across pipelines.
        Path otherSchema = config.resolve("other_schema.toon");
        Files.writeString(otherSchema, schema("OTHER", "other"));
        Files.writeString(config.resolve("other_pipeline.toon"),
                pipeline("OTHER_ETL", otherSchema, null, tmp.resolve("other")));
        Files.writeString(config.resolve("other_job.toon"), job("other_heartbeat", "other_etl"));

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            DataSourceBundleResolver resolver = new DataSourceBundleResolver(ctx.service(), ctx.root().config());

            // The engine lowercases pipeline names (the ConsignmentEvent.pipeline() convention), so the
            // data-source id is the lowercased form of the in-file name.
            assertEquals(java.util.List.of("other_etl", "voucher_etl"), resolver.dataSourceIds(),
                    "both pipelines are listed as data sources");

            DataSourceBundle voucher = resolver.resolve("voucher_etl");
            assertEquals("voucher_etl", voucher.id());
            assertEquals(config.resolve("voucher_pipeline.toon"), voucher.pipeline());
            assertEquals(config.resolve("voucher_conn_connection.toon"), voucher.connection(),
                    "connection matched by in-file id, not filename");
            assertTrue(voucher.schemas().contains(voucherSchema), "the referenced schema is in the bundle");
            assertEquals(java.util.List.of(config.resolve("voucher_job.toon")), voucher.jobs(),
                    "only the job whose on_pipeline targets this pipeline (case-insensitively)");
            assertTrue(voucher.files().contains(config.resolve("voucher_pipeline.toon")));
            assertTrue(voucher.files().contains(config.resolve("voucher_conn_connection.toon")));
            assertFalse(voucher.files().contains(config.resolve("other_job.toon")), "no cross-pipeline bleed");

            DataSourceBundle other = resolver.resolve("other_etl");
            assertNull(other.connection(), "a local source has no connection file");
            assertEquals(java.util.List.of(config.resolve("other_job.toon")), other.jobs());

            assertThrows(NoSuchElementException.class, () -> resolver.resolve("NOPE"),
                    "unknown data source");
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "ds-space");
            try { com.gamma.acquire.AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    /**
     * W3, 2026-08-01 — the closure now reaches the component registry. Decision Rules and Datasets live at
     * {@code config/registry/<type-dir>/<id>.toon} and are typed by their DIRECTORY, never by a filename
     * suffix, so the connection/job suffix scans could never have found them: a promoted data source
     * arrived without the rules that decide what happens to its rows, or the datasets that read it.
     */
    @Test
    void resolvesDecisionRulesAndDatasetsBoundToTheDataSource(@TempDir Path tmp) throws Exception {
        Path base   = tmp.resolve("reg-space");
        Path config = base.resolve("config");
        Files.createDirectories(config);

        Path voucherSchema = config.resolve("voucher_schema.toon");
        Files.writeString(voucherSchema, schema("VOUCHER", "voucher"));
        Files.writeString(config.resolve("voucher_pipeline.toon"),
                pipeline("VOUCHER_ETL", voucherSchema, null, tmp.resolve("voucher")));
        Files.writeString(config.resolve("voucher_job.toon"), job("voucher_heartbeat", "voucher_etl"));

        Path otherSchema = config.resolve("other_schema.toon");
        Files.writeString(otherSchema, schema("OTHER", "other"));
        Files.writeString(config.resolve("other_pipeline.toon"),
                pipeline("OTHER_ETL", otherSchema, null, tmp.resolve("other")));

        // ── decision rules ──────────────────────────────────────────────────────────────────────────
        Path onPipeline = component(config, "decision-rules", "voucher_hold",
                // Upper-case target against the lowercased data-source id: matching is case-insensitive,
                // the same rule DecisionRules.forTarget applies at evaluation time.
                "name: voucher_hold\ntargetType: pipeline\ntarget: VOUCHER_ETL\npriority: 10\n");
        Path disabled = component(config, "decision-rules", "voucher_disabled",
                "name: voucher_disabled\ntarget: voucher_etl\nenabled: false\n");
        Path defaultType = component(config, "decision-rules", "voucher_default_type",
                // No targetType at all — defaults to `pipeline`, as DecisionRules does.
                "name: voucher_default_type\ntarget: voucher_etl\n");
        Path onBundledJob = component(config, "decision-rules", "job_rule",
                "name: job_rule\ntargetType: job\ntarget: voucher_heartbeat\n");
        Path onAbsentJob = component(config, "decision-rules", "absent_job_rule",
                "name: absent_job_rule\ntargetType: job\ntarget: nobody_at_all\n");
        Path otherRule = component(config, "decision-rules", "other_hold",
                "name: other_hold\ntargetType: pipeline\ntarget: other_etl\n");
        Path unknownType = component(config, "decision-rules", "weird",
                "name: weird\ntargetType: galaxy\ntarget: voucher_etl\n");

        // ── datasets ────────────────────────────────────────────────────────────────────────────────
        Path dsStore = component(config, "datasets", "voucher_dataset", "physicalRef: voucher_etl\n");
        Path dsDeep  = component(config, "datasets", "voucher_deep", "physicalRef: voucher_etl/database\n");
        Path dsOther = component(config, "datasets", "other_dataset", "physicalRef: other_etl\n");
        Path dsView  = component(config, "datasets", "view_backed", "view: some_saved_view\n");
        Path dsNull  = component(config, "datasets", "null_ref", "physicalRef: null\n");
        Path dsBroken = component(config, "datasets", "broken", "physicalRef: [[[ not toon\n");

        // A ComponentStore history snapshot: NOT a component, and must not be exported as one.
        Path history = config.resolve("registry/decision-rules/.history/voucher_hold.toon");
        Files.createDirectories(history.getParent());
        Files.writeString(history, "name: voucher_hold\ntarget: voucher_etl\n");

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            DataSourceBundleResolver resolver = new DataSourceBundleResolver(ctx.service(), ctx.root().config());
            DataSourceBundle b = resolver.resolve("voucher_etl");

            assertEquals(
                    java.util.List.of(onBundledJob, defaultType, disabled, onPipeline, dsStore, dsDeep),
                    b.components(),
                    "decision rules then datasets, each sorted by filename");

            assertTrue(b.components().contains(disabled),
                    "a DISABLED rule still travels — export promotes the config as authored");
            assertFalse(b.components().contains(otherRule), "another pipeline's rule stays behind");
            assertFalse(b.components().contains(onAbsentJob),
                    "a job-targeted rule whose job is not in this bundle stays behind");
            assertFalse(b.components().contains(unknownType), "an unknown targetType is not ours");
            assertFalse(b.components().contains(dsOther), "another pipeline's dataset stays behind");
            assertFalse(b.components().contains(dsView),
                    "a view-backed dataset is not matched — its ViewStore lives outside config/");
            assertFalse(b.components().contains(dsNull), "a null physicalRef references no store");
            assertFalse(b.components().contains(dsBroken), "an unparseable component is skipped, not fatal");
            assertFalse(b.components().contains(history), "a .history/ snapshot is not a component");

            // And they reach the zip, at their config-relative registry paths.
            byte[] zip = BundleExporter.exportDataSource(b, ctx.root().config(), "reg-space");
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) names.add(e.getName());
            }
            assertTrue(names.contains("registry/decision-rules/voucher_hold.toon"), names.toString());
            assertTrue(names.contains("registry/datasets/voucher_dataset.toon"), names.toString());
            assertFalse(names.contains("registry/datasets/other_dataset.toon"), names.toString());
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "reg-space");
            try { com.gamma.acquire.AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    /**
     * W5 forward closure, 2026-09-24 — the bundle carries the Reference Datasets its pipeline READS, as the
     * {@code produces: reference} pipelines that produce them: through a join step ({@code processing.join}),
     * through the enrichment companion's {@code references.<alias>.ref}, and transitively through a carried
     * producer's own join. A path reference, a dangling id, a by-name read of a pipeline that produces no
     * Reference, and a self-join carry nothing; a file both closures reach appears once.
     */
    @Test
    void carriesTheReferenceDatasetsThePipelineReadsAndNothingElse(@TempDir Path tmp) throws Exception {
        Path base   = tmp.resolve("fwd-space");
        Path config = base.resolve("config");
        Files.createDirectories(config);

        Path sharedSchema = config.resolve("shared_schema.toon");
        Files.writeString(sharedSchema, schema("SHARED", "shared"));
        Files.writeString(config.resolve("shared_conn_connection.toon"), connection("SHARED_CONN"));

        // The consumer joins region_dim (processing.join).
        Files.writeString(config.resolve("orders_pipeline.toon"), inactive(withJoin(
                pipeline("ORDERS_ETL", sharedSchema, "SHARED_CONN", tmp.resolve("orders")),
                "reference/region_dim")));
        // A second consumer whose steps[] reads only things that carry nothing: itself (a Reference producer
        // self-join), a data path, a dangling id, and a pipeline that produces no Reference.
        Path miscSchema = config.resolve("misc_schema.toon");
        Files.writeString(miscSchema, schema("MISC", "misc"));
        Files.writeString(config.resolve("misc_pipeline.toon"), produces(
                pipeline("MISC_DIM", miscSchema, null, tmp.resolve("misc"))) + """
                steps[4]:
                  - join:
                      reference: reference/misc_dim
                      on[1]: ID
                  - join:
                      reference: data/lookup.csv
                      on[1]: ID
                  - join:
                      reference: reference/nobody_at_all
                      on[1]: ID
                  - join:
                      reference: reference/plain_etl
                      on[1]: ID
                """);

        // region_dim: a Reference producer sharing the consumer's schema AND connection, which itself joins
        // country_dim — the transitive hop.
        Path region = config.resolve("region_pipeline.toon");
        Files.writeString(region, produces(withJoin(
                pipeline("REGION_DIM", sharedSchema, "SHARED_CONN", tmp.resolve("region")), "reference/country_dim")));
        Path countrySchema = config.resolve("country_schema.toon");
        Files.writeString(countrySchema, schema("COUNTRY", "country"));
        Path country = config.resolve("country_pipeline.toon");
        Files.writeString(country, produces(pipeline("COUNTRY_DIM", countrySchema, null, tmp.resolve("country"))));
        Path currencySchema = config.resolve("currency_schema.toon");
        Files.writeString(currencySchema, schema("CURRENCY", "currency"));
        Path currency = config.resolve("currency_pipeline.toon");
        Files.writeString(currency, produces(pipeline("CURRENCY_DIM", currencySchema, null, tmp.resolve("currency"))));
        // A pipeline that produces NO Reference, and one nobody reads.
        Path plainSchema = config.resolve("plain_schema.toon");
        Files.writeString(plainSchema, schema("PLAIN", "plain"));
        Files.writeString(config.resolve("plain_pipeline.toon"),
                pipeline("PLAIN_ETL", plainSchema, null, tmp.resolve("plain")));
        Path unreadSchema = config.resolve("unread_schema.toon");
        Files.writeString(unreadSchema, schema("UNREAD", "unread"));
        Path unread = config.resolve("unread_pipeline.toon");
        Files.writeString(unread, produces(pipeline("UNREAD_DIM", unreadSchema, null, tmp.resolve("unread"))));

        // A routed consumer whose only read is a join inside a branch's steps[].
        Path routedSchema = config.resolve("routed_schema.toon");
        Files.writeString(routedSchema, schema("ROUTED", "routed"));
        String routedRoot = fwd(tmp.resolve("routed"));
        // Written by the canonical TOON writer: a branch's nested steps[] has no hand-typed spelling to trust.
        Map<String, Object> routing = new java.util.LinkedHashMap<>();
        routing.put("sinks", java.util.List.of(
                new java.util.LinkedHashMap<>(Map.of("database", routedRoot + "/db", "format", "PARQUET")),
                new java.util.LinkedHashMap<>(Map.of("database", routedRoot + "/other", "format", "PARQUET"))));
        Map<String, Object> hi = new java.util.LinkedHashMap<>();
        hi.put("key", "hi");
        hi.put("where", "AMOUNT >= 200");
        hi.put("database", routedRoot + "/db");
        hi.put("steps", java.util.List.of(Map.of("join",
                new java.util.LinkedHashMap<>(Map.of("reference", "reference/currency_dim", "on", java.util.List.of("ID"))))));
        Map<String, Object> other = new java.util.LinkedHashMap<>();
        other.put("key", "other");
        other.put("where", "AMOUNT < 200");
        other.put("database", routedRoot + "/other");
        Map<String, Object> route = new java.util.LinkedHashMap<>();
        route.put("mode", "case");
        route.put("default", "other");
        route.put("branches", java.util.List.of(hi, other));
        routing.put("route", route);
        Files.writeString(config.resolve("routed_pipeline.toon"),
                inactive(pipeline("ROUTED_ETL", routedSchema, null, tmp.resolve("routed")))
                        + com.gamma.config.io.ConfigCodec.toToon(routing));

        // The companion: reads currency_dim by name, plain_etl by name (no Reference), and a data path.
        Path enrich = config.resolve("orders_kpi_enrich.toon");
        Files.writeString(enrich, """
                name: ORDERS_KPI
                version: 1
                input:
                  database: %s/orders/db
                  format: PARQUET
                references:
                  cur:
                    ref: currency_dim
                  plain:
                    ref: plain_etl
                  file:
                    path: %s/lookup.csv
                    format: CSV
                output:
                  database: %s/orders_kpi
                  format: PARQUET
                transform: "SELECT * FROM input"
                triggers:
                  on_pipeline: ORDERS_ETL
                """.formatted(fwd(tmp), fwd(tmp), fwd(tmp)));

        try (SpaceContext ctx = SpaceBootstrap.load(SpaceRoot.under(base))) {
            DataSourceBundleResolver resolver = new DataSourceBundleResolver(ctx.service(), ctx.root().config());

            DataSourceBundle orders = resolver.resolve("orders_etl");
            assertEquals(java.util.List.of(enrich), orders.enrichments(), "the on_pipeline companion travels");
            assertEquals(java.util.List.of("region_dim", "currency_dim", "country_dim"),
                    orders.references().stream().map(DataSourceBundle.Reference::id).toList(),
                    "join read, companion read, then the transitive hop — and nothing for the path, the"
                            + " dangling id or the non-producer");
            DataSourceBundle.Reference regionRef = orders.references().get(0);
            assertEquals(region, regionRef.files().get(0), "the producer's pipeline file comes first");
            assertTrue(regionRef.files().contains(config.resolve("shared_conn_connection.toon")),
                    "the producer's connection rides with it");
            assertTrue(orders.references().get(2).files().contains(countrySchema), "and its schema");
            assertFalse(orders.files().contains(unread), "a Reference nobody reads stays behind");
            assertFalse(orders.files().contains(config.resolve("plain_pipeline.toon")),
                    "a by-name read of a pipeline that produces no Reference carries nothing");

            java.util.List<Path> files = orders.files();
            assertEquals(new java.util.LinkedHashSet<>(files).size(), files.size(), "no file twice: " + files);
            assertEquals(1, java.util.Collections.frequency(files, sharedSchema),
                    "the schema both the pipeline and its Reference read appears once");
            assertTrue(files.containsAll(java.util.List.of(region, country, currency)), files.toString());

            // The zip indexes each Reference by the entries ONLY it brought — the shared schema and
            // connection belong to the data source itself, so a kept Reference can never take them away.
            byte[] zip = BundleExporter.exportDataSource(orders, ctx.root().config(), "fwd-space");
            Map<String, Object> manifest;
            try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                String text = null;
                java.util.List<String> names = new java.util.ArrayList<>();
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                    names.add(e.getName());
                    if (e.getName().equals(BundleExporter.MANIFEST))
                        text = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                assertEquals(new java.util.LinkedHashSet<>(names).size(), names.size(), "no entry twice: " + names);
                manifest = com.gamma.config.io.ConfigCodec.toMap(text);
            }
            Map<?, ?> index = (Map<?, ?>) manifest.get(BundleExporter.REFERENCES);
            assertEquals(java.util.List.of("region_pipeline.toon"), index.get("region_dim"),
                    "region_dim's schema and connection are the data source's own");
            assertEquals(java.util.List.of("country_pipeline.toon", "country_schema.toon"),
                    index.get("country_dim"));

            // ── the third read site: a join inside a route branch's steps[] ──
            DataSourceBundle routed = resolver.resolve("routed_etl");
            assertEquals(java.util.List.of("currency_dim"),
                    routed.references().stream().map(DataSourceBundle.Reference::id).toList());

            // ── a self-join, a path, a dangling id and a non-producer carry nothing ──
            DataSourceBundle misc = resolver.resolve("misc_dim");
            assertEquals(4, ctx.service().configFor("misc_dim").orElseThrow().steps().size(),
                    "the four joins were parsed — the probe would otherwise pass vacuously");
            assertTrue(misc.references().isEmpty(), "carried: " + misc.references());

            // ── negative: a pipeline reading nothing carries nothing extra ──
            DataSourceBundle plain = resolver.resolve("plain_etl");
            assertTrue(plain.references().isEmpty(), "no reads, no forward closure");
            assertTrue(plain.enrichments().isEmpty());
            byte[] plainZip = BundleExporter.exportDataSource(plain, ctx.root().config(), "fwd-space");
            try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(plainZip))) {
                for (var e = zis.getNextEntry(); e != null; e = zis.getNextEntry()) {
                    if (e.getName().equals(BundleExporter.MANIFEST))
                        assertNull(com.gamma.config.io.ConfigCodec.toMap(new String(zis.readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)).get(BundleExporter.REFERENCES),
                                "no references index when nothing is read");
                }
            }
        } finally {
            MDC.put(EventLog.SPACE_MDC_KEY, "fwd-space");
            try { com.gamma.acquire.AcquisitionLedgers.use(null); }
            finally { MDC.remove(EventLog.SPACE_MDC_KEY); }
        }
    }

    private static String fwd(Path p) {
        return p.toString().replace("\\", "/");
    }

    /** A pipeline TOON with a {@code processing.join} on {@code reference}. */
    private static String withJoin(String pipeline, String reference) {
        return pipeline.replace("processing:\n", "processing:\n  join:\n    reference: " + reference + "\n    on: ID\n");
    }

    /** A pipeline TOON that produces a Reference. Inactive: a join without {@code output_store} cannot arm. */
    private static String produces(String pipeline) {
        return "produces: reference\n" + inactive(pipeline);
    }

    private static String inactive(String pipeline) {
        return pipeline.replace("active: true", "active: false");
    }

    /** Write a registry component at {@code config/registry/<typeDir>/<id>.toon}. */
    private static Path component(Path config, String typeDir, String id, String body) throws Exception {
        Path p = config.resolve("registry").resolve(typeDir).resolve(id + ".toon");
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
        return p;
    }

    // ── inline TOON builders (shapes mirror the shipped sample configs) ──────────────────────────────

    private static String pipeline(String name, Path schemaFile, String connectionId, Path dataRoot) {
        String fwd = dataRoot.toString().replace("\\", "/");
        String source = connectionId == null ? "" : """
                collector:
                  connection: %s
                """.formatted(connectionId);
        return """
                name: %s
                active: true
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
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
                  schema_file: "%s"
                  csv_settings:
                    delimiter: ","
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d %%H:%%M:%%S"
                %s""".formatted(name, fwd, fwd, fwd, fwd, fwd, fwd, fwd, fwd, fwd,
                schemaFile.toString().replace("\\", "/"), source);
    }

    private static String schema(String rawName, String canonical) {
        return """
                partitionKey: ID
                raw:
                  name: %s
                  format: CSV
                  fields[2]{name,selector,type,description,unit,classification}:
                    ID,"0",VARCHAR,"id","","INTERNAL"
                    AMOUNT,"1",DOUBLE,"amount","","INTERNAL"
                mapping:
                  canonicalName: %s
                  rawName: %s
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID,ID,DIRECT
                    AMOUNT,AMOUNT,DIRECT
                """.formatted(rawName, canonical, rawName);
    }

    private static String connection(String id) {
        return """
                connection:
                  id: %s
                  connector: sftp
                  host: sftp.example.com
                  port: 22
                  base_path: /voucher
                  username: voucheruser
                  password: "${ENV:VOUCHER_PASSWORD}"
                """.formatted(id);
    }

    private static String job(String name, String onPipeline) {
        return """
                job:
                  name: %s
                  type: maintenance
                  task: heartbeat
                  on_pipeline: %s
                """.formatted(name, onPipeline);
    }
}
