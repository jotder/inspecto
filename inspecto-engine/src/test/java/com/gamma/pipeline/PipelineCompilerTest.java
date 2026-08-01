package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase-1 parity gate: a {@code lift → compile} round-trip recovers every execution input the
 * engine consumes, unchanged ({@link PipelineCompiler}). {@code assertSame} proves the IR carries the
 * <em>identical</em> typed objects (lossless), so compile-back to today's primitives is faithful.
 */
class PipelineCompilerTest {

    @Test
    void singleSchemaRoundTripIsLossless(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        PipelineGraph g = PipelineLift.lift(cfg);
        PipelineCompiler.Compiled c = PipelineCompiler.compile(g);

        assertEquals(cfg.identity().pipelineName(), c.name());
        assertEquals(cfg.active(), c.active());

        // acquisition: source sub-records + dirs.poll recovered identically
        PipelineNode acq = c.acquisition().orElseThrow();
        assertSame(cfg.collector().guarantee(), acq.cfg("guarantee"));
        assertSame(cfg.collector().stability(), acq.cfg("stability"));
        assertSame(cfg.collector().postAction(), acq.cfg("post_action"));
        assertEquals(cfg.dirs().poll(), acq.cfg("poll"));

        // parser: the whole CsvSettings record + the single schema map, by identity
        PipelineNode parse = c.parser().orElseThrow();
        assertSame(cfg.csv(), parse.cfg("csv"));
        assertSame(cfg.schemas().single(), parse.cfg("schema"));

        // sink: output + dirs recovered; exactly one sink (single schema)
        assertEquals(1, c.sinks().size());
        PipelineNode sink = c.sinks().get(0);
        assertEquals(cfg.output().format(), sink.cfg("format"));
        assertEquals(cfg.dirs().database(), sink.cfg("database"));

        // dedup: path mode + duplicate_check on ⇒ exactly the marker subsystem, no fingerprint
        assertEquals(List.of("transform.dedup.marker"), c.dedups().stream().map(PipelineNode::type).toList());
        assertTrue(c.gap().isEmpty());
    }

    /**
     * <b>W0 collector round-trip gate.</b> A config carrying a full {@code collector:} block (every Phase B–F
     * sub-record) survives {@code load → lift → toConfigMap → toToon → load} with its {@link
     * PipelineConfig.Collector} record recovered field-for-field. Asserted per sub-record so the coverage map
     * is explicit: whatever the lower dropped would surface as a single failing field, not a vague diff.
     */
    @Test
    void collectorBlockRoundTripsEverySourceSubRecord(@TempDir Path dir) throws Exception {
        Path toon = writeRichCollectorPipeline(dir);
        PipelineConfig.Collector orig = PipelineConfig.load(toon.toString()).collector();

        PipelineGraph g = PipelineLift.lift(PipelineConfig.load(toon.toString()));
        Map<String, Object> rebuilt = PipelineCompiler.toConfigMap(g, dir.resolve("schemas"));
        Path toonB = dir.resolve("rebuilt_pipeline.toon");
        Files.writeString(toonB, ConfigCodec.toToon(rebuilt));
        PipelineConfig.Collector round = PipelineConfig.load(toonB.toString()).collector();

        assertEquals(orig.connector(), round.connector(), "connector");
        assertEquals(orig.connection(), round.connection(), "connection ref");
        assertEquals(orig.discovery(), round.discovery(), "discovery mode");
        assertEquals(orig.recursiveDepth(), round.recursiveDepth(), "recursive_depth");
        assertEquals(orig.includes(), round.includes(), "include globs");
        assertEquals(orig.excludes(), round.excludes(), "exclude globs");
        assertEquals(orig.stability(), round.stability(), "stability (Phase B)");
        assertEquals(orig.guarantee(), round.guarantee(), "guarantee (Phase D)");
        assertEquals(orig.duplicate(), round.duplicate(), "duplicate policy (Phase C)");
        assertEquals(orig.incremental(), round.incremental(), "incremental watermark (Phase C4)");
        assertEquals(orig.gapDetection(), round.gapDetection(), "gap_detection (Phase D)");
        assertEquals(orig.fetch(), round.fetch(), "fetch tuning (Phase E/F)");
        assertEquals(orig.retry(), round.retry(), "retry/backoff (Phase F)");
        assertEquals(orig.circuitBreaker(), round.circuitBreaker(), "circuit breaker (Phase F)");
        assertEquals(orig.postAction(), round.postAction(), "post_action finalizer (Phase F)");

        // the whole record, as one statement: the lower is lossless for the collector shape
        assertEquals(orig, round, "the entire Collector record round-trips");
    }

    /** A single-schema pipeline whose {@code collector:} block exercises every Phase B–F sub-record. */
    private static Path writeRichCollectorPipeline(Path dir) throws Exception {
        Path sf = dir.resolve("schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: rc_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    EVENT_DATE, "1", DATE
                mapping:
                  canonicalName: rc_data
                  rawName: rc_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String toon = """
                name: RICH_COLLECTOR
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  markers: %1$s/markers
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                collector:
                  connector: sftp
                  connection: prod_sftp
                  include[2]: "glob:**/*.csv", "glob:**/*.dat"
                  exclude[1]: "glob:**/_*"
                  recursive_depth: 3
                  discovery: watch
                  guarantee: EXACTLY_ONCE
                  duplicate:
                    mode: checksum
                    algorithm: SHA256
                    on_change: reprocess
                  incremental:
                    watermark: last_modified
                  stability:
                    window: 45s
                    size_checks: 3
                    ready_marker: .ready
                    exclude_temp_files: true
                  gap_detection:
                    enabled: true
                    sequence: "SEQ_{n}"
                  fetch:
                    mode: STAGE
                    staging_dir: %1$s/staging
                    parallel_fetch: 4
                    rate_limit: 50MB/s
                  retry:
                    count: 3
                    backoff: EXPONENTIAL
                    initial_delay: 2s
                    max_delay: 30s
                  circuit_breaker:
                    failure_threshold: 7
                    cooldown: 5m
                  post_action:
                    on_success: MOVE
                    archive_path: %1$s/archive
                    on_unsupported: FAIL
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.csv"
                  schema_file: %2$s
                  csv_settings:
                    delimiter: ","
                    has_header: true
                """.formatted(posix(dir), posix(sf));
        Path p = dir.resolve("rc_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private static String posix(Path p) {
        return p.toString().replace('\\', '/');
    }

    @Test
    void compileGroupsNodesByRole() {
        PipelineGraph g = new PipelineGraph("X", true,
                List.of(PipelineNode.of("acq", "acquisition"),
                        PipelineNode.of("dm", "transform.dedup.marker"),
                        PipelineNode.of("df", "transform.dedup.fingerprint"),
                        PipelineNode.of("parse", "parser"),
                        PipelineNode.of("gap", "gap"),
                        PipelineNode.of("s1", "sink.persistent", Map.of(PipelineStores.CONFIG_STORE, "a")),
                        PipelineNode.of("s2", "sink.materialized", Map.of(PipelineStores.CONFIG_STORE, "b"))),
                List.of());
        PipelineCompiler.Compiled c = PipelineCompiler.compile(g);

        assertEquals("acq", c.acquisition().orElseThrow().id());
        assertEquals("parse", c.parser().orElseThrow().id());
        assertEquals("gap", c.gap().orElseThrow().id());
        assertEquals(List.of("dm", "df"), c.dedups().stream().map(PipelineNode::id).toList());
        assertEquals(List.of("s1", "s2"), c.sinks().stream().map(PipelineNode::id).toList());
    }
}
