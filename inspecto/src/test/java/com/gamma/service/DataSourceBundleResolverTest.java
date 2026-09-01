package com.gamma.service;

import com.gamma.event.EventLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
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
