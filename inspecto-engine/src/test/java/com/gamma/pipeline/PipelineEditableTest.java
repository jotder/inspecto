package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import com.gamma.config.io.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The W5 gate: the editable lift/lower pair round-trips the canonical {@code *_pipeline.toon}
 * <b>verbatim</b> — including keys the graph does not model — and refuses unrepresentable
 * topologies with named codes instead of silently truncating.
 */
class PipelineEditableTest {

    /** editable-lift → codec decode (the HTTP shape) → strict lower == the original raw map. */
    @Test
    void editableRoundTripIsVerbatim(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        // the editable shape must survive the codec (plain maps only — no typed records leaked)
        PipelineGraph g = PipelineCodec.fromMap(editable);
        Map<String, Object> lowered = PipelineEditable.lower(g, raw, true);

        assertEquals(raw, lowered, "strict lower over the original file reproduces it verbatim");
    }

    /** Keys the graph does not model (description, status_dir, …) survive a strict lower. */
    @Test
    void unmodeledKeysArePreserved(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> lowered = PipelineEditable.lower(
                PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw)), raw, true);

        assertEquals("real operator notes", lowered.get("description"));
        Map<?, ?> dirs = (Map<?, ?>) lowered.get("dirs");
        assertEquals(dir.toString().replace('\\', '/') + "/status", dirs.get("status_dir"));
        assertEquals(dir.toString().replace('\\', '/') + "/logs", dirs.get("log_dir"));
        assertEquals(dir.toString().replace('\\', '/') + "/quarantine", dirs.get("quarantine"),
                "single-schema quarantine dir has no owning node and must be preserved");
    }

    @Test
    void unsupportedNodeRefusesWithNamedCode() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("d1", "transform.derive", Map.of()),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertEquals(1, ex.refusals().size());
        assertEquals(PipelineEditable.UNSUPPORTED_NODE, ex.refusals().get(0).code());
        assertEquals("d1", ex.refusals().get(0).nodeId());
    }

    @Test
    void twoDistinctDatabasesRefuseMultiSink() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("s1", "sink.persistent", Map.of("database", "db_a")),
                node("s2", "sink.persistent", Map.of("database", "db_b"))), List.of());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertTrue(ex.refusals().stream().anyMatch(r -> PipelineEditable.MULTI_SINK.equals(r.code())));
    }

    /** Multi-schema branch sinks share one database — that is NOT a MULTI_SINK refusal. */
    @Test
    void sharedDatabaseBranchSinksAreAllowed() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schemas", List.of())),
                node("s1", "sink.persistent", Map.of("database", "db")),
                node("s2", "sink.persistent", Map.of("database", "db"))), List.of());
        assertDoesNotThrow(() -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
    }

    @Test
    void strictIncompleteGraphNamesEveryMissingRole() {
        PipelineGraph g = new PipelineGraph("x", true,
                List.of(node("p", "parser", Map.of())), List.of());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        List<String> codes = ex.refusals().stream().map(PipelineCompileException.Refusal::code).toList();
        assertTrue(codes.contains(PipelineEditable.NO_ACQUISITION));
        assertTrue(codes.contains(PipelineEditable.NO_PERSISTENT_SINK));
        assertTrue(codes.contains(PipelineEditable.PARSER_NO_SCHEMA));
    }

    /** An inactive draft may be partial: present nodes own their sections, the rest is untouched. */
    @Test
    void lenientDraftOverlaysOnlyWhatThePresentNodesOwn() {
        Map<String, Object> existing = new LinkedHashMap<>(Map.of(
                "name", "draft1", "active", false,
                "dirs", new LinkedHashMap<>(Map.of("poll", "data/inbox/draft1", "database", "data/draft1/database")),
                "processing", new LinkedHashMap<>(Map.of("threads", 1))));
        PipelineGraph g = new PipelineGraph("draft1", false, List.of(
                node("acq", "acquisition", Map.of("connector", "sftp", "poll", "custom/inbox"))), List.of());

        Map<String, Object> lowered = PipelineEditable.lower(g, existing, false);

        Map<?, ?> collector = (Map<?, ?>) lowered.get("collector");
        assertEquals("sftp", collector.get("connector"));
        Map<?, ?> dirs = (Map<?, ?>) lowered.get("dirs");
        assertEquals("custom/inbox", dirs.get("poll"), "acq is present, so it owns dirs.poll");
        assertEquals("data/draft1/database", dirs.get("database"), "no sink node — existing database untouched");
        assertEquals(1, ((Map<?, ?>) lowered.get("processing")).get("threads"), "no sink node — threads untouched");
    }

    /** Enrichment nodes are companion-persisted (W4b): the lower ignores them, never refuses. */
    @Test
    void enrichmentNodesAreIgnoredNotRefused() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("premium_enrich", "enrichment", Map.of()),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());
        Map<String, Object> lowered = assertDoesNotThrow(
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertFalse(lowered.toString().contains("premium_enrich"), "no mirror of the companion in the file");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static PipelineNode node(String id, String type, Map<String, Object> cfg) {
        return new PipelineNode(id, type, null, null, cfg, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(Path toon) throws Exception {
        return (Map<String, Object>) (Map<?, ?>) ConfigLoader.filesystem().decode(toon.toString());
    }

    /**
     * A single-schema pipeline exercising every section the editable pair owns PLUS unmodeled keys
     * (description, status_dir/log_dir/quarantine) that must travel verbatim.
     */
    private static Path writeRichPipeline(Path dir) throws Exception {
        Path sf = dir.resolve("schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: ed_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    EVENT_DATE, "1", DATE
                mapping:
                  canonicalName: ed_data
                  rawName: ed_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: EDITABLE_RICH
                active: true
                description: real operator notes
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                  markers: %1$s/markers
                  quarantine: %1$s/quarantine
                  status_dir: %1$s/status
                  log_dir: %1$s/logs
                output:
                  format: CSV
                collector:
                  connector: sftp
                  connection: prod_sftp
                  include[1]: "glob:**/*.csv"
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
                  retry:
                    count: 4
                    backoff: exponential
                    initial_delay: 2s
                    max_delay: 60s
                processing:
                  threads: 2
                  file_pattern: "*.csv"
                  duplicate_check:
                    enabled: true
                    marker_extension: .processed
                    retention_days: 30
                  schema_file: %2$s
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("editable_rich_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }
}
