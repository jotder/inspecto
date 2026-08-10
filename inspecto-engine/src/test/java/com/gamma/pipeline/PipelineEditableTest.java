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

    // ── the four single-slot node kinds ────────────────────────────────────────────────
    // `lower` keeps ONE node per scalar slot, and all four now REFUSE a second rather than keeping
    // the last silently. Until 2026-08-11 only transform.join was guarded; dedup/route/summarize were
    // last-one-wins, and the tests here pinned that discard rather than endorsing it. Flipping them
    // was an operator call precisely because a graph holding two of a kind saves today and stops
    // saving on its next edit — taken deliberately, since the alternative is dropping authored work
    // with no signal at all.

    /** A second {@code transform.dedup} refuses; the first keeps the {@code processing.dedup} slot. */
    @Test
    void secondDedupNodeRefusesInsteadOfDiscarding() {
        assertSingleSlotRefusal(PipelineEditable.MULTI_DEDUP, "dd1", "dd2",
                node("dd1", "transform.dedup", Map.of("keys", List.of("msisdn"))),
                node("dd2", "transform.dedup", Map.of("keys", List.of("imsi"))));
    }

    /** A second {@code transform.route} refuses — {@code lower} writes ONE {@code route} key. */
    @Test
    void secondRouteNodeRefusesInsteadOfDiscarding() {
        assertSingleSlotRefusal(PipelineEditable.MULTI_ROUTE, "r1", "r2",
                node("r1", "transform.route", Map.of("on", "first")),
                node("r2", "transform.route", Map.of("on", "second")));
    }

    /** A second {@code transform.summarize} refuses; the first keeps the grain. */
    @Test
    void secondSummarizeNodeRefusesInsteadOfDiscarding() {
        assertSingleSlotRefusal(PipelineEditable.MULTI_SUMMARIZE, "s1", "s2",
                node("s1", "transform.summarize", Map.of("group_by", List.of("day"))),
                node("s2", "transform.summarize", Map.of("group_by", List.of("cell"))));
    }

    /** One node per scalar slot: the SECOND is named as the offender and the message points at the
     *  first, which keeps the slot — the shape {@code MULTI_JOIN} established. */
    private static void assertSingleSlotRefusal(String code, String keeper, String offender,
                                                PipelineNode... duplicates) {
        PipelineGraph g = graphWith(duplicates);

        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));

        assertEquals(1, ex.refusals().size(), "exactly one refusal: " + ex.refusals());
        assertEquals(code, ex.refusals().get(0).code());
        assertEquals(offender, ex.refusals().get(0).nodeId(),
                "the SECOND node is named as the offender; the first keeps the slot");
        assertTrue(ex.refusals().get(0).message().contains(keeper),
                "the message points at the node already holding the slot");
    }

    /**
     * The contrast case, and the only one of the four that is guarded: a second {@code transform.join}
     * REFUSES with {@code MULTI_JOIN} rather than discarding. The guard shipped with the join verb but
     * had no engine-level test — the join slice asserted it only at the palette contract level.
     */
    @Test
    void secondJoinNodeRefusesInsteadOfDiscarding() {
        PipelineGraph g = graphWith(
                node("j1", "transform.join", Map.of("reference", "sites")),
                node("j2", "transform.join", Map.of("reference", "cells")));

        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));

        assertEquals(1, ex.refusals().size());
        assertEquals(PipelineEditable.MULTI_JOIN, ex.refusals().get(0).code());
        assertEquals("j2", ex.refusals().get(0).nodeId(),
                "the SECOND join is named as the offender; the first keeps the slot");
        assertTrue(ex.refusals().get(0).message().contains("j1"),
                "the message points at the node already holding the slot");
    }

    /** A minimal strict-lowerable graph (acquisition + parser + persistent sink) plus {@code extra}. */
    private static PipelineGraph graphWith(PipelineNode... extra) {
        List<PipelineNode> nodes = new java.util.ArrayList<>(List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("sink", "sink.persistent", Map.of("database", "db"))));
        nodes.addAll(List.of(extra));
        return new PipelineGraph("dup", true, nodes, List.of());
    }

    /** Strict-lower a minimal graph carrying {@code extra}; fails the test if it refuses. */
    /**
     * A pipeline authored by the Onboarding Parsing stage carries its parse options in the top-level
     * {@code parsing:} block — the design-of-record spelling, which {@code PipelineConfigParser}
     * overlays <em>over</em> {@code processing.csv_settings}. The editor must therefore SEE that block
     * on the parser node; otherwise it edits the losing key and the operator's change is masked.
     */
    @Test
    void parsingBlockIsCarriedOnTheParserNode(@TempDir Path dir) throws Exception {
        Path toon = writeParsingBlockPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        Map<?, ?> parser = nodeOfType(editable, "parser");
        Map<?, ?> config = (Map<?, ?>) parser.get("config");
        assertNotNull(config, "the parser node has config");

        Map<?, ?> parsing = (Map<?, ?>) config.get("parsing");
        assertNotNull(parsing, "the parser node carries the parsing: block it owns");
        assertEquals("|", ((Map<?, ?>) parsing.get("delimited")).get("delimiter"));
    }

    /** …and an edit to it lowers back into {@code parsing:}, not the losing legacy key. */
    @Test
    void editedParsingBlockLowersBackIntoParsing(@TempDir Path dir) throws Exception {
        Path toon = writeParsingBlockPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        // the operator changes the delimiter in the editor
        Map<?, ?> parser = nodeOfType(editable, "parser");
        Map<?, ?> parsing = (Map<?, ?>) ((Map<?, ?>) parser.get("config")).get("parsing");
        @SuppressWarnings("unchecked")
        Map<String, Object> delimited = (Map<String, Object>) parsing.get("delimited");
        delimited.put("delimiter", ";");

        Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

        Map<?, ?> loweredParsing = (Map<?, ?>) lowered.get("parsing");
        assertEquals(";", ((Map<?, ?>) loweredParsing.get("delimited")).get("delimiter"),
                "the edit lands in parsing:, the block that wins the overlay");
    }

    /**
     * A parser node bound to a reusable Grammar component lowers to a config that still references it.
     * {@link PipelineNode} documents {@code grammar/<id>} as an intended {@code use:} ref and the
     * Grammar editor writes exactly that — but {@code lower()} used to translate only
     * {@code connection/}, so the binding was dropped on the way to disk (and, with no PARSER_OWNED
     * key present, refused {@code PARSER_NO_SCHEMA} instead).
     */
    @Test
    void grammarBindingSurvivesLowering() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                new PipelineNode("parse", "parser", Map.of(), "grammar/pipe_delimited"),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());

        Map<String, Object> lowered = PipelineEditable.lower(g, new LinkedHashMap<>(), true);

        Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
        assertNotNull(parsing, "a grammar-bound parser lowers to a parsing: block");
        assertEquals("grammar/pipe_delimited", parsing.get("grammar"),
                "the Grammar the operator bound must survive the round-trip to disk");
    }

    /** Unbinding the Grammar in the editor unbinds it on disk — a strict save is the whole truth. */
    @Test
    void unbindingTheGrammarClearsTheRef() {
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("parsing", new LinkedHashMap<>(Map.of("grammar", "grammar/old", "frontend", "delimited")));
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());

        Map<String, Object> lowered = PipelineEditable.lower(g, existing, true);

        Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
        assertNull(parsing == null ? null : parsing.get("grammar"), "the stale Grammar ref is cleared");
    }

    /** The binding round-trips: a grammar-bound file lifts back to a use: ref, not a raw config key. */
    @Test
    void grammarRefLiftsBackOntoUse(@TempDir Path dir) throws Exception {
        Path toon = writeParsingBlockPipeline(dir);
        Map<String, Object> raw = decode(toon);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsing = (Map<String, Object>) raw.get("parsing");
        parsing.put("grammar", "grammar/pipe_delimited");
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<?, ?> parser = nodeOfType(PipelineEditable.toMap(cfg, raw), "parser");

        assertEquals("grammar/pipe_delimited", parser.get("use"),
                "a bound Grammar presents as a binding, like connection/ on acquisition");
        Map<?, ?> config = (Map<?, ?>) parser.get("config");
        Map<?, ?> nodeParsing = config == null ? null : (Map<?, ?>) config.get("parsing");
        assertNull(nodeParsing == null ? null : nodeParsing.get("grammar"),
                "…and not ALSO as a free-text config key the operator could corrupt");
    }

    /** Two distinct databases now lower to a plural sinks: block (slice 4), not a MULTI_SINK refusal. */
    @Test
    void twoDistinctDatabasesLowerToASinksList() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("s1", "sink.persistent", Map.of("database", "db_a", "format", "PARQUET")),
                node("s2", "sink.persistent", Map.of("database", "db_b", "format", "CSV"))), List.of());
        Map<String, Object> lowered = PipelineEditable.lower(g, new LinkedHashMap<>(), true);

        assertTrue(lowered.get("sinks") instanceof List<?>, "multi-destination lowers to a sinks: list");
        List<?> sinks = (List<?>) lowered.get("sinks");
        assertEquals(2, sinks.size());
        java.util.Set<Object> dbs = new java.util.LinkedHashSet<>();
        for (Object s : sinks) dbs.add(((Map<?, ?>) s).get("database"));
        assertEquals(java.util.Set.of("db_a", "db_b"), dbs);
        // the single output:/dirs.database shorthand stays consistent with the first destination
        assertEquals("db_a", ((Map<?, ?>) lowered.get("dirs")).get("database"));
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

    /**
     * D7: the post-parse predicate {@code processing.csv_settings.where} must survive lift → lower on
     * the flat representation, because this is the only representation the pipeline editor can save.
     * Lift has to surface it on the Filter node (otherwise opening and saving a pipeline silently drops
     * an authored predicate) and lower has to put it back unchanged.
     */
    @Test
    void postParsePredicateRoundTripsThroughTheFilterNode(@TempDir Path dir) throws Exception {
        Path toon = writePredicatePipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        // ── lift surfaces the predicate on a filter node the editor can render ──
        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));
        PipelineNode filter = g.nodes().stream()
                .filter(n -> BuiltinNodeType.TRANSFORM_FILTER.type().equals(n.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a csv_settings.where must lift to a filter node, else the editor cannot see it"));
        assertEquals("AMT > 1.0", filter.cfg("where"));
        assertNull(filter.cfg("filter_target_column"),
                "meaningless without the pre-parse lists — emitting it would make lower non-verbatim");

        // ── lower puts it back, and the whole file round-trips verbatim ──
        Map<String, Object> lowered = PipelineEditable.lower(g, raw, true);
        assertEquals(raw, lowered, "strict lower reproduces the predicate pipeline verbatim");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static PipelineNode node(String id, String type, Map<String, Object> cfg) {
        return new PipelineNode(id, type, null, null, cfg, null);
    }

    /** The single node of {@code type} in an editable map — fails loudly if there isn't exactly one. */
    private static Map<?, ?> nodeOfType(Map<String, Object> editable, String type) {
        List<?> nodes = (List<?>) editable.get("nodes");
        List<Map<?, ?>> hits = new java.util.ArrayList<>();
        for (Object n : nodes) if (type.equals(((Map<?, ?>) n).get("type"))) hits.add((Map<?, ?>) n);
        assertEquals(1, hits.size(), "exactly one '" + type + "' node");
        return hits.get(0);
    }

    /**
     * A pipeline in the Onboarding spelling: parse options in the top-level {@code parsing:} block,
     * with only the schema reference left in {@code processing:}. Deliberately has NO
     * {@code processing.csv_settings} — that is the point: the options live where the parser gives
     * them precedence.
     */
    private static Path writeParsingBlockPipeline(Path dir) throws Exception {
        Path sf = dir.resolve("pb_schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: pb_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    EVENT_DATE, "1", DATE
                mapping:
                  canonicalName: pb_data
                  rawName: pb_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: PARSING_BLOCK
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                collector:
                  connector: local
                parsing:
                  frontend: delimited
                  delimited:
                    delimiter: "|"
                    has_header: false
                processing:
                  schema_file: %2$s
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("parsing_block_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decode(Path toon) throws Exception {
        return (Map<String, Object>) (Map<?, ?>) ConfigLoader.filesystem().decode(toon.toString());
    }

    /**
     * Minimal single-schema pipeline whose only filtering is the post-parse predicate — deliberately
     * separate from {@link #writeRichPipeline} so adding a Filter node here cannot perturb the node
     * counts the other tests assert against that fixture.
     *
     * <p>⚠ No {@code dirs.markers} and no {@code duplicate_check}: {@code markers} is a <em>modeled</em>
     * key owned by the dedup-marker node, so without that node a strict lower correctly drops it and the
     * verbatim assertion fails on a diff that has nothing to do with the predicate. Add the two together
     * or not at all.
     */
    private static Path writePredicatePipeline(Path dir) throws Exception {
        Path sf = dir.resolve("pred_schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: pred_data
                  format: CSV
                  fields[3]{name,selector,type}:
                    ID, "0", VARCHAR
                    AMT, "1", DOUBLE
                    EVENT_DATE, "2", DATE
                mapping:
                  canonicalName: pred_data
                  rawName: pred_data
                  rules[3]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    AMT, AMT, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: EDITABLE_PREDICATE
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                  backup: %1$s/backup
                  temp: %1$s/temp
                output:
                  format: CSV
                processing:
                  threads: 2
                  file_pattern: "*.csv"
                  schema_file: %2$s
                  csv_settings:
                    where: "AMT > 1.0"
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("editable_predicate_pipeline.toon");
        Files.writeString(p, toon);
        return p;
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
