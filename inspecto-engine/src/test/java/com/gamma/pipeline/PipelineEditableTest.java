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

    // ── CONSIGNMENT-HOME-1 (2026-09-02): the planner caps moved from the sink to the collector ──

    /** A file still on the legacy processing.batch: map lifts onto the acquisition node's `consignment`
     *  map and lowers into collector.consignment: — the legacy spellings gone, the values intact. */
    @Test
    void legacyBatchCapsHealIntoCollectorConsignment(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        Map<String, Object> raw = decode(toon);
        Map<String, Object> processing = (Map<String, Object>) raw.get("processing");
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("max_files", 40);
        batch.put("max_bytes", 1024L);
        batch.put("on_partial", "HOLD");          // unmodeled sub-key — must survive the move
        processing.put("batch", batch);
        processing.put("batch_max_files", 7);     // the write-only flat spelling, read by nothing
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));
        PipelineNode acq = g.node("acq").orElseThrow();
        Map<?, ?> onNode = (Map<?, ?>) acq.cfg("consignment");
        assertEquals(40, ((Number) onNode.get("max_files")).intValue(), "healed onto the collector node");
        assertEquals("HOLD", onNode.get("on_partial"));
        assertNull(g.node("sink").map(n -> n.cfg("batch")).orElse(null), "the sink no longer carries it");

        Map<String, Object> lowered = PipelineEditable.lower(g, raw, true);
        Map<?, ?> collector = (Map<?, ?>) lowered.get("collector");
        Map<?, ?> consignment = (Map<?, ?>) collector.get("consignment");
        assertEquals(40, ((Number) consignment.get("max_files")).intValue());
        assertEquals(1024L, ((Number) consignment.get("max_bytes")).longValue());
        assertEquals("HOLD", consignment.get("on_partial"), "unmodeled sub-key survives");
        Map<?, ?> proc = (Map<?, ?>) lowered.get("processing");
        for (String k : List.of("batch", "batch_max_files", "batch_max_bytes"))
            assertFalse(proc.containsKey(k), k + " must not survive the heal — one spelling per knob");
    }

    /** The canonical home round-trips verbatim, and the parser reads it FIRST when both are present. */
    @Test
    void collectorConsignmentIsCanonicalAndWinsOverTheLegacyBlock(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        Map<String, Object> raw = decode(toon);
        Map<String, Object> collector = (Map<String, Object>) raw.computeIfAbsent("collector", k -> new LinkedHashMap<>());
        collector.put("consignment", new LinkedHashMap<>(Map.of("max_files", 12, "order", "name")));
        ((Map<String, Object>) raw.get("processing")).put("batch", new LinkedHashMap<>(Map.of("max_files", 3)));
        Path both = dir.resolve("both_pipeline.toon");
        Files.writeString(both, com.gamma.config.io.ConfigCodec.toToon(raw));
        PipelineConfig cfg = PipelineConfig.load(both.toString());
        assertEquals(12, cfg.processing().batchMaxFiles(), "collector.consignment wins over processing.batch");
        assertEquals("name", cfg.processing().batchOrder());

        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));
        Map<String, Object> lowered = PipelineEditable.lower(g, raw, true);
        Map<?, ?> out = (Map<?, ?>) ((Map<?, ?>) lowered.get("collector")).get("consignment");
        assertEquals(12, ((Number) out.get("max_files")).intValue());
        assertEquals("name", out.get("order"));
        assertFalse(((Map<?, ?>) lowered.get("processing")).containsKey("batch"), "the legacy block is healed away");
    }

    // ── P5-a: marker dedup moved from its own node onto acquisition ─────────────────────
    // The keys live in processing.duplicate_check + dirs.markers and are only BORROWED by the
    // acquisition node, so the two hazards worth pinning are (a) leaking them into collector:,
    // where nothing reads them, and (b) losing an editor's dedup because its graph predates the move.

    /** The lifted acquisition node carries the marker keys, and lowering puts them back where the engine reads them. */
    @Test
    void markerDedupRidesAcquisitionAndLowersToItsOwnBlock(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        PipelineGraph g = PipelineCodec.fromMap(editable);
        assertTrue(g.node("dedup_marker").isEmpty(), "the marker node is no longer emitted");
        PipelineNode acq = g.node("acq").orElseThrow();
        assertEquals(true, acq.cfg("duplicate_check"));
        assertEquals(".processed", acq.cfg("marker_extension"));
        // ⚠ numeric compare: this path carries the file's decoded value (a Long), while the typed
        // PipelineLift path carries the record's int — assertEquals on the boxes would fail on one
        assertEquals(30, ((Number) acq.cfg("retention_days")).intValue());
        assertEquals(dir.toString().replace('\\', '/') + "/markers", acq.cfg("markers_dir"));

        Map<String, Object> lowered = PipelineEditable.lower(g, raw, true);
        Map<?, ?> dc = (Map<?, ?>) ((Map<?, ?>) lowered.get("processing")).get("duplicate_check");
        assertEquals(true, dc.get("enabled"));
        assertEquals(30, ((Number) dc.get("retention_days")).intValue());
        assertEquals(dir.toString().replace('\\', '/') + "/markers",
                ((Map<?, ?>) lowered.get("dirs")).get("markers"));
        // ⚠ the leak guard: acquisition-node keys are dumped wholesale into collector:, so a borrowed
        // key missing from ACQ_FOREIGN_KEYS would land in a block the engine never reads it from
        Map<?, ?> collector = (Map<?, ?>) lowered.get("collector");
        for (String k : List.of("duplicate_check", "marker_extension", "retention_days", "markers_dir"))
            assertFalse(collector.containsKey(k), k + " must not leak into the collector: block");
    }

    /**
     * Read-compat: an editor opened before P5-a holds a lifted graph that still carries a standalone
     * marker node. Lower must keep accepting it — ignoring it would delete that operator's dedup on
     * their next save. (The lift never emits one again; only LOWER is compatible.)
     */
    @Test
    void aLegacyMarkerNodeStillLowers() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("dedup_marker", "transform.dedup.marker", Map.of("retention_days", 7)),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);

        Map<?, ?> dc = (Map<?, ?>) ((Map<?, ?>) lowered.get("processing")).get("duplicate_check");
        assertEquals(true, dc.get("enabled"));
        assertEquals(7, dc.get("retention_days"));
    }

    /**
     * ⚠ The acquisition toggle is authoritative when PRESENT, in BOTH directions: a graph that turns
     * dedup off while a stale marker node is still on the canvas must not fall through to it and
     * silently re-enable what the operator just switched off.
     */
    @Test
    void anExplicitlyDisabledToggleBeatsAStaleMarkerNode() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in", "duplicate_check", false)),
                node("dedup_marker", "transform.dedup.marker", Map.of("retention_days", 7)),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);

        assertNull(((Map<?, ?>) lowered.get("processing")).get("duplicate_check"));
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

    // ── AUTHOR-1: a use: component ref the flat file has no home for ───────────────────
    // The editor's component picker is keyed on a node's CATEGORY, so it offers `transform/<id>` on
    // every TRANSFORM node and `sink/<id>` on every sink. lower() read use: for two kinds only and
    // dropped the rest without a word — a 200 written:true save whose binding never reached the file.
    // These pin the refusal, and that it names the offending node.

    /** The reported case: a map node bound to a transform component. Refused, not swallowed. */
    @Test
    void aTransformComponentRefOnAMapNodeRefusesWithNamedCode() {
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(graphBinding(
                        bound("map", "transform.sql", "transform/orders_std")), new LinkedHashMap<>(), true));
        assertEquals(1, ex.refusals().size(), ex.getMessage());
        assertEquals(PipelineEditable.UNSUPPORTED_BINDING, ex.refusals().get(0).code());
        assertEquals("map", ex.refusals().get(0).nodeId());
        assertTrue(ex.refusals().get(0).message().contains("transform/orders_std"),
                "the refusal names the ref, so the author can see what was rejected");
    }

    /**
     * ⚠ The same picker writes the same ref onto the five CHAIN kinds and onto a sink — so the defect was
     * never about map's absence from STEP_KIND (as first diagnosed): a chain node IS in STEP_KIND and its
     * ref was dropped too, because stepsOf emits config only.
     */
    @Test
    void everyOtherNodeKindRefusesAnUnhomedRefToo() {
        for (String type : List.of("transform.filter", "transform.join", "transform.dedup",
                "transform.summarize", "transform.route")) {
            PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                    () -> PipelineEditable.lower(graphBinding(
                            bound("t", type, "transform/x")), new LinkedHashMap<>(), true), type);
            assertEquals(PipelineEditable.UNSUPPORTED_BINDING, ex.refusals().get(0).code(), type);
        }
        PipelineCompileException sink = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                        node("acq", "acquisition", Map.of("poll", "in")),
                        node("parse", "parser", Map.of("schema_file", "s.toon")),
                        new PipelineNode("sink", "sink.persistent", null, null,
                                Map.of("database", "db"), "sink/warehouse")), List.of()),
                        new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.UNSUPPORTED_BINDING, sink.refusals().get(0).code());
    }

    /** The two refs that DO have a home keep saving — the refusal is per-kind, not a blanket ban on use:. */
    @Test
    void theTwoHomedBindingsStillLower() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                new PipelineNode("acq", "acquisition", null, null, Map.of("poll", "in"), "connection/sftp1"),
                new PipelineNode("parse", "parser", null, null, Map.of(), "grammar/cdr_v2"),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);
        assertEquals("sftp1", section(lowered, "collector").get("connection"));
        assertEquals("grammar/cdr_v2", section(lowered, "parsing").get("grammar"));
    }

    /** A plugin parser's synthesized ingester/<fqcn> binding is the third homed prefix — lift emits it. */
    @Test
    void aPluginParsersIngesterBindingIsNotRefused() {
        PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                new PipelineNode("parse", "parser", null, null,
                        Map.of("ingester", "com.acme.Ingester"), "ingester/com.acme.Ingester"),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);
    }

    /**
     * ⚠ An enrichment node's {@code use: enrichment/<name>} is written by the editor itself
     * ({@code node-config.dialog.ts:714}, W4b) on every enrichment save — the companion file is the
     * truth and the ref is DERIVED from it, which is why lower has "nothing to lower" for the kind.
     * It is not an unhomed authored binding, and refusing it would make every pipeline holding an
     * enrichment node unsaveable.
     */
    @Test
    void anEnrichmentsCompanionBindingIsNotRefused() {
        PipelineEditable.lower(graphBinding(
                bound("enrich", "enrichment", "enrichment/customer_lookup")), new LinkedHashMap<>(), true);
    }

    /** Only the kind's OWN derived prefix is exempt — the picker's `transform/<id>` still refuses there. */
    @Test
    void anUnhomedRefOnAnEnrichmentNodeStillRefuses() {
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(graphBinding(
                        bound("enrich", "enrichment", "transform/x")), new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.UNSUPPORTED_BINDING, ex.refusals().get(0).code());
        assertEquals("enrich", ex.refusals().get(0).nodeId());
    }

    /** A minimal saveable graph whose one transform node carries {@code bound}'s ref. */
    private static PipelineGraph graphBinding(PipelineNode bound) {
        return new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                bound,
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());
    }

    private static PipelineNode bound(String id, String type, String use) {
        return new PipelineNode(id, type, null, null, Map.of(), use);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.getOrDefault(key, Map.of());
    }

    // ── the four kinds that used to hold a single slot ─────────────────────────────────
    // These four were last-one-wins (a second node vanished silently), then briefly REFUSED
    // (MULTI_DEDUP / MULTI_ROUTE / MULTI_SUMMARIZE joining MULTI_JOIN, 2026-08-11). Both were stations
    // on the way here: the refusal existed only to make the discard visible while the flat file still
    // had one slot per kind, and it was removed in the same slice (A3) that gave the file an ordered
    // `steps:` chain — never before, which would have restored the silent discard.
    //
    // So the assertion inverts: the graphs below used to be the refusal fixtures, and now they SAVE.

    /** Two record dedups: both survive, in order, rather than one claiming a slot. */
    @Test
    void secondDedupNodeLowersToTheChain() {
        assertChain(List.of("dedup", "dedup"),
                node("dd1", "transform.dedup", Map.of("keys", List.of("msisdn"))),
                node("dd2", "transform.dedup", Map.of("keys", List.of("imsi"))));
    }

    // ── JAVA-SIMP-2 seam #2 (RouteBranch): branch entries must survive the lower verbatim ──

    /**
     * Unmodeled per-branch keys — and the AUTHORED ORDER of each entry — survive a lower, and the
     * {@code database} join-key stamp lands in place. This is the historical branch-destruction seam
     * (an untyped {@code List<Map>} mutated in place); the file-level {@code unmodeledKeysArePreserved}
     * never looked inside {@code branches[]}.
     */
    @Test
    void routeBranchUnmodeledKeysAndOrderSurviveLower() {
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("note", "operator note");     // unmodeled, authored first
        big.put("database", "stale_db");      // authored BEFORE key; the stamp must replace it in place
        big.put("key", "big");
        Map<String, Object> rest = new LinkedHashMap<>();
        rest.put("key", "rest");              // no route edge for this key — entry stays verbatim
        rest.put("threshold", 5);             // unmodeled
        Map<String, Object> rc = new LinkedHashMap<>();
        rc.put("on", "amount");
        rc.put("branches", List.of(big, rest));

        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon")),
                node("route", "transform.route", rc),
                node("sink", "sink.persistent", Map.of("database", "big_orders"))),
                List.of(new PipelineEdge("route", PipelineRel.route("big"), "sink"))),
                new LinkedHashMap<>(), true);

        Map<?, ?> route = (Map<?, ?>) lowered.get("route");
        List<?> branches = (List<?>) route.get("branches");
        Map<?, ?> b0 = (Map<?, ?>) branches.get(0);
        assertEquals("operator note", b0.get("note"), "unmodeled per-branch key must survive");
        assertEquals("big_orders", b0.get("database"), "database restamped from the route edge's sink");
        assertEquals(List.of("note", "database", "key"), List.copyOf(((Map<String, ?>) b0).keySet()),
                "the entry keeps its authored key order — a reorder is a spurious file diff on save");
        Map<?, ?> b1 = (Map<?, ?>) branches.get(1);
        assertEquals(5, b1.get("threshold"));
        assertFalse(b1.containsKey("database"), "an edge-less branch is not stamped");
    }

    /** Two routes: the top-level {@code route:} key held one, the chain holds both. */
    @Test
    void secondRouteNodeLowersToTheChain() {
        assertChain(List.of("route", "route"),
                node("r1", "transform.route", Map.of("on", "first")),
                node("r2", "transform.route", Map.of("on", "second")));
    }

    /** Two summarizes: two grains, not one grain and a discarded node. */
    @Test
    void secondSummarizeNodeLowersToTheChain() {
        assertChain(List.of("summarize", "summarize"),
                node("s1", "transform.summarize", Map.of("group_by", List.of("day"))),
                node("s2", "transform.summarize", Map.of("group_by", List.of("cell"))));
    }

    /** Two reference joins — the kind that was guarded first, and is now just another chain kind. */
    @Test
    void secondJoinNodeLowersToTheChain() {
        assertChain(List.of("join", "join"),
                node("j1", "transform.join", Map.of("reference", "sites")),
                node("j2", "transform.join", Map.of("reference", "cells")));
    }

    /**
     * ⚠ Two of a kind is not the only thing the singular keys could not hold — <b>order</b> is the other,
     * and it looks harmless. One dedup and one summarize fit the singular slots whichever way round they
     * are authored, but the flat file stores no order, so {@code summarize → dedup} would come back from
     * the next lift as {@code dedup → summarize}: a two-node pipeline quietly changing meaning with
     * nothing over-full about it. Authored out of the lift's order ⇒ the chain, same as a repeat.
     */
    @Test
    void aChainAuthoredOutOfTheLiftsOrderAlsoLowersToTheChain() {
        assertChain(List.of("summarize", "dedup"),
                node("s1", "transform.summarize", Map.of("group_by", List.of("day"))),
                node("dd1", "transform.dedup", Map.of("keys", List.of("msisdn"))));
    }

    /** …while the same two kinds authored IN the lift's order keep the singular keys, untouched. */
    @Test
    void aLegacyShapedChainKeepsTheSingularKeysAndWritesNoStepsBlock() {
        Map<String, Object> out = PipelineEditable.lower(graphWith(
                node("dd1", "transform.dedup", Map.of("keys", List.of("msisdn"))),
                node("s1", "transform.summarize", Map.of("group_by", List.of("day")))),
                new LinkedHashMap<>(), true);

        assertFalse(out.containsKey("steps"),
                "an existing pipeline must round-trip verbatim — steps: is only for what the keys cannot hold");
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) out.get("processing");
        assertEquals(Map.of("keys", List.of("msisdn")), processing.get("dedup"));
        assertEquals(Map.of("group_by", List.of("day")), processing.get("summarize"));
    }

    // ── transform.sql: a chain step with no singular block, so it always takes the steps: spelling ──

    private static final Map<String, Object> SIMPLE_AUTHORED_SQL = Map.of(
            "sql", "SELECT id, amt * 2 AS amt2 FROM input",
            // an opaque authoring artifact — its PRESENCE means "Simple-authored"; the engine never reads it
            "fields", List.of(Map.of("name", "amt2", "expr", "amt * 2")));

    /** One sql node lowers to a {@code kind: sql} step carrying {@code sql} AND {@code fields} verbatim. */
    @Test
    void aSqlNodeLowersToASqlStepCarryingSqlAndFieldsVerbatim() {
        Map<String, Object> out = PipelineEditable.lower(graphWith(
                node("s1", "transform.sql", SIMPLE_AUTHORED_SQL)), new LinkedHashMap<>(), true);

        Object steps = out.get("steps");
        assertInstanceOf(List.class, steps, "sql has no singular block, so even one node takes steps:");
        assertEquals(1, ((List<?>) steps).size());
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) steps).get(0);
        assertEquals(java.util.Set.of("sql"), entry.keySet());
        assertEquals(SIMPLE_AUTHORED_SQL, entry.get("sql"));
    }

    /** A hand-written node (no {@code fields}) lowers to a step with only {@code sql} — no invented key. */
    @Test
    void aHandWrittenSqlNodeLowersWithoutAFieldsKey() {
        Map<String, Object> out = PipelineEditable.lower(graphWith(
                node("s1", "transform.sql", Map.of("sql", "SELECT * FROM input"))), new LinkedHashMap<>(), true);
        Map<?, ?> entry = (Map<?, ?>) ((List<?>) out.get("steps")).get(0);
        assertEquals(Map.of("sql", "SELECT * FROM input"), entry.get("sql"));
    }

    /** sql sits in an authored chain like any other step, in node order. */
    @Test
    void aSqlStepKeepsItsPlaceInTheChain() {
        assertChain(List.of("dedup", "sql", "summarize"),
                node("dd1", "transform.dedup", Map.of("keys", List.of("msisdn"))),
                node("s1", "transform.sql", Map.of("sql", "SELECT * FROM input")),
                node("sm", "transform.summarize", Map.of("group_by", List.of("day"))));
    }

    @Test
    void aBlankSqlRefusesWithNamedCode() {
        for (Map<String, Object> cfg : List.<Map<String, Object>>of(Map.of(), Map.of("sql", "   "))) {
            PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                    () -> PipelineEditable.lower(graphWith(node("s1", "transform.sql", cfg)),
                            new LinkedHashMap<>(), true));
            assertEquals(1, ex.refusals().size(), ex.getMessage());
            assertEquals(PipelineEditable.SQL_STEP_EMPTY, ex.refusals().get(0).code());
            assertEquals("s1", ex.refusals().get(0).nodeId());
        }
    }

    @Test
    void transformSqlIsAuthorable() {
        assertTrue(PipelineEditable.isLowerable("transform.sql"));
        assertTrue(PipelineEditable.isAuthorable("transform.sql"));
    }

    /**
     * A stored {@code kind: sql} step lifts to a {@code transform.sql} node carrying {@code {sql, fields}},
     * and the file that node lowers back to is byte-identical — the opaque {@code fields[]} list included,
     * through the real codec.
     */
    @Test
    void aSqlStepRoundTripsByteIdenticalThroughTheFile(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("s.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("sq", false, List.of(   // inactive: an ACTIVE steps: file demands output_store: at load
                node("acq", "acquisition", Map.of("poll", dir.resolve("in").toString())),
                node("parse", "parser", Map.of("schema_file", schema.toString())),
                node("s1", "transform.sql", SIMPLE_AUTHORED_SQL),
                node("sink", "sink.persistent", Map.of("database", dir.resolve("db").toString()))),
                List.of()), new LinkedHashMap<>(), true);
        Path toon = dir.resolve("sq_pipeline.toon");
        Files.writeString(toon, com.gamma.config.io.ConfigCodec.toToon(lowered));
        Map<String, Object> raw = decode(toon);

        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        assertTrue(cfg.hasExplicitSteps());
        assertEquals(List.of("sql"), cfg.steps().stream().map(PipelineConfig.Step::kind).toList());

        PipelineGraph lifted = PipelineLift.lift(cfg);
        PipelineNode sqlNode = lifted.nodes().stream()
                .filter(n -> BuiltinNodeType.TRANSFORM_SQL.type().equals(n.type()))
                .filter(n -> !PipelineEditable.isProjectionSlot(n))   // the slot is a transform.sql too
                .findFirst().orElseThrow(() -> new AssertionError("no transform.sql node lifted: " + lifted.nodes()));
        assertEquals(SIMPLE_AUTHORED_SQL, sqlNode.config(), "the node carries sql + the opaque fields list");

        Map<String, Object> relowered = PipelineEditable.lower(
                PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw)), raw, true);
        assertEquals(raw, relowered);
        assertEquals(Files.readString(toon), com.gamma.config.io.ConfigCodec.toToon(relowered),
                "lift -> lower reproduces the stored file byte for byte");
    }

    /** Lower {@code extra} and assert the {@code steps:} kinds it produced, in order. */
    private static void assertChain(List<String> expected, PipelineNode... extra) {
        Map<String, Object> out = PipelineEditable.lower(graphWith(extra), new LinkedHashMap<>(), true);

        Object steps = out.get("steps");
        assertInstanceOf(List.class, steps, "the chain lowers to a steps: list, not a refusal");
        List<String> kinds = ((List<?>) steps).stream()
                .map(e -> ((Map<?, ?>) e).keySet().iterator().next().toString())
                .toList();
        assertEquals(expected, kinds);
        // ⚠ Both spellings in one file is a PARSE refusal, so a stale singular key would not merely be
        // untidy — it would write config that can never be loaded again.
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) out.get("processing");
        for (String legacy : List.of("dedup", "join", "summarize"))
            assertFalse(processing.containsKey(legacy), "processing." + legacy + " must not survive steps:");
        assertFalse(out.containsKey("route"), "route: must not survive steps:");
    }

    // ── A1: the multiplicity round-trip property (docs/superpower/pipeline-multiplicity-plan.md) ──
    //
    // Pinned red in A1 so A2/A3 had something to turn green rather than a shape argued on a whiteboard;
    // GREEN since A3 (lower emits steps:, lift walks it), so the @Disabled markers are gone.

    /**
     * The <b>authored</b> transform chain of {@code g}, in node order, as step kinds.
     *
     * <p>⚠ {@code transform.map} is filtered out, and that is not cosmetic — it is the schema projection
     * {@code PipelineLift} emits for every branch, authored by nobody. While these tests were pinned they
     * compared against a raw {@code transform.*} list and so expected {@code [filter, filter]} against an
     * actual {@code [filter, map]}: red for the right reason at the time, but an expectation no
     * implementation could ever have satisfied. Restricting to the kinds a chain can hold is the same rule
     * {@code PipelineStepsProjectionTest.liftedChain} already applies.
     */
    private static List<String> transformChain(PipelineGraph g) {
        return g.nodes().stream()
                // the projection slot (a transform.sql since 2026-09-05) is not a chain step
                .filter(n -> !PipelineEditable.isProjectionSlot(n))
                .map(PipelineNode::type)
                .filter(t -> t.startsWith("transform."))
                .map(t -> t.substring("transform.".length()))
                .filter(com.gamma.etl.PipelineConfig.Step.KINDS::contains)
                .map(k -> "transform." + k)
                .toList();
    }

    /**
     * graph → strict lower → {@code PipelineConfig} → lift → graph, the full authoring round-trip.
     *
     * <p>⚠ Unlike {@link #graphWith}, this needs a schema file that actually exists: {@code fromMap}
     * resolves {@code schema_file} eagerly, so the {@code s.toon} placeholder the refusal tests use dies
     * with {@code FileNotFound} <em>before</em> reaching the code under test — a red test for the wrong
     * reason, which proves nothing.
     */
    private static PipelineGraph roundTrip(Path dir, PipelineNode... extra) throws Exception {
        Path schema = dir.resolve("s.toon");
        Files.writeString(schema, com.gamma.etl.PipelineConfigBatchTest.miniSchema());
        List<PipelineNode> nodes = new java.util.ArrayList<>(List.of(
                node("acq", "acquisition", Map.of("poll", dir.resolve("in").toString())),
                node("parse", "parser", Map.of("schema_file", schema.toString())),
                node("sink", "sink.persistent", Map.of("database", dir.resolve("db").toString()))));
        nodes.addAll(List.of(extra));

        Map<String, Object> lowered = PipelineEditable.lower(
                new PipelineGraph("dup", true, nodes, List.of()), new LinkedHashMap<>(), true);
        return PipelineLift.lift(PipelineConfig.fromMap(lowered));
    }

    /**
     * ⚠ <b>The finding that reframes A1, and it is about {@code transform.filter} — a kind the plan does
     * not list.</b> Filter <em>looks</em> handled: {@code lower} collects filters into a {@code List} and
     * has no {@code MULTI_FILTER} refusal, so it reads as the one kind that already allows many. It does
     * not. The list is merged into a single {@code processing.csv_settings} map with {@code putAll}
     * ({@code PipelineEditable.java:398}) and {@code lift} emits exactly one Filter node, so a second
     * filter is <b>silently absorbed</b> — and where two filters set the same key, last-one-wins decides
     * the pipeline's behaviour with no signal anywhere.
     *
     * <p>That is the same data loss {@code 2cf7005e} refused for the other kinds, still live, in the kind
     * most likely to be authored more than once and the most order-sensitive of them all.
     */
    @Test
    void twoFiltersSurviveTheRoundTrip(@TempDir Path dir) throws Exception {
        assertEquals(List.of("transform.filter", "transform.filter"),
                transformChain(roundTrip(dir,
                        node("f1", "transform.filter", Map.of("where", "duration > 0")),
                        node("f2", "transform.filter", Map.of("where", "cell_id IS NOT NULL")))),
                "both authored filters survive; today the second is merged away without a word");
    }

    /**
     * The property the plan names: two of each kind survive with their <b>order</b> intact.
     *
     * <p>⚠ <b>Order here is not one question but two, and the plan only names the first.</b> Within a kind,
     * list position can carry it. <em>Across</em> kinds nothing can, because the flat file stores no order
     * at all — {@code PipelineLift.branch} emits a hard-coded chain, {@code map → join → dedup → summarize
     * → route → sink} ({@code PipelineLift.java:187-238}). Today that is invisible: with at most one node
     * per kind a constant order is indistinguishable from a stored one. Add a second of any kind and it
     * stops being: an authored {@code dedup → summarize → dedup} cannot be represented by per-kind plural
     * lists however they are keyed, since the lift will always emit both dedups adjacent.
     *
     * <p>So A1's real decision is not "index or list position" — it is whether the flat format keeps
     * per-kind blocks at all, or grows an ordered {@code steps:} sequence. This test deliberately asserts
     * the <em>interleaved</em> order so it cannot be made green by the per-kind shape alone.
     */
    @Test
    void twoOfEachKindSurviveTheRoundTripInAuthoredOrder(@TempDir Path dir) throws Exception {
        assertEquals(List.of("transform.dedup", "transform.summarize",
                        "transform.dedup", "transform.summarize"),
                transformChain(roundTrip(dir,
                        node("dd1", "transform.dedup", Map.of("keys", List.of("msisdn"))),
                        node("s1", "transform.summarize", Map.of("group_by", List.of("day"))),
                        node("dd2", "transform.dedup", Map.of("keys", List.of("imsi"))),
                        node("s2", "transform.summarize", Map.of("group_by", List.of("cell"))))),
                "the authored interleaving survives — per-kind lists alone cannot express this");
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
        Map<?, ?> parser = nodeOfType(editable, "parser.delimited");
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
        Map<?, ?> parser = nodeOfType(editable, "parser.delimited");
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

        Map<?, ?> parser = nodeOfType(PipelineEditable.toMap(cfg, raw), "parser.delimited");

        assertEquals("grammar/pipe_delimited", parser.get("use"),
                "a bound Grammar presents as a binding, like connection/ on acquisition");
        Map<?, ?> config = (Map<?, ?>) parser.get("config");
        Map<?, ?> nodeParsing = config == null ? null : (Map<?, ?>) config.get("parsing");
        assertNull(nodeParsing == null ? null : nodeParsing.get("grammar"),
                "…and not ALSO as a free-text config key the operator could corrupt");
    }

    // ── P3a: the delimited parser subtype (B6 — per-format parser identity) ─────────

    /** An explicit {@code frontend: delimited} file round-trips verbatim through the subtype. */
    @Test
    void explicitDelimitedFrontendRoundTripsVerbatimThroughTheSubtype(@TempDir Path dir) throws Exception {
        Path toon = writeParsingBlockPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        nodeOfType(editable, "parser.delimited");   // the retype happened
        Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

        assertEquals(raw, lowered, "strict lower over the original file reproduces it verbatim");
    }

    /**
     * Delimited is also the parser's IMPLICIT default — a legacy file that never says the word keeps the
     * plain type, so nothing already deployed changes shape on a read before its author opts in.
     */
    @Test
    void aFileWithoutAnExplicitFrontendKeepsThePlainParserType(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        nodeOfType(PipelineEditable.toMap(PipelineConfig.load(toon.toString()), decode(toon)), "parser");
    }

    /** A delimited node authored fresh from the palette gets its frontend stamped into the file. */
    @Test
    void aNewDelimitedParserNodeIsStampedWithItsFrontend() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser.delimited",
                        Map.of("parsing", Map.of("delimited", Map.of("delimiter", ";")))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);

        Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
        assertEquals("delimited", parsing.get("frontend"),
                "the file must say the word the type means, or the next lift loses the identity");
        assertEquals(";", ((Map<?, ?>) parsing.get("delimited")).get("delimiter"));
    }

    /** A parsing.frontend that contradicts the node's own type refuses by name. */
    @Test
    void aContradictoryFrontendOnADelimitedParserRefuses() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser.delimited", Map.of("parsing", Map.of("frontend", "json"))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.PARSER_FRONTEND_MISMATCH, ex.refusals().get(0).code());
        assertEquals("parse", ex.refusals().get(0).nodeId());
    }

    /** The flat file has ONE parse slot; a second parser-family node refuses instead of last-one-wins. */
    @Test
    void twoParserFamilyNodesRefuse() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("p1", "parser", Map.of("schema_file", "s.toon")),
                node("p2", "parser.delimited", Map.of("parsing", Map.of("delimited", Map.of()))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.MULTI_PARSER, ex.refusals().get(0).code());
        assertEquals("p2", ex.refusals().get(0).nodeId());
    }

    /** A Grammar binds to the subtype like the plain parser; a plugin ingester/ ref contradicts it. */
    @Test
    void delimitedParserTakesAGrammarButRefusesAnIngesterBinding() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                new PipelineNode("parse", "parser.delimited", null, null, Map.of(), "grammar/pipes"),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);
        assertEquals("grammar/pipes", section(lowered, "parsing").get("grammar"));

        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                        node("acq", "acquisition", Map.of("poll", "in")),
                        new PipelineNode("parse", "parser.delimited", null, null,
                                Map.of(), "ingester/com.example.Custom"),
                        node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                        new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.UNSUPPORTED_BINDING, ex.refusals().get(0).code());
    }

    // ── P3b: the fixed-width parser subtype ─────────────────────────────────────────

    /** An explicit {@code frontend: fixedwidth} file round-trips verbatim through the subtype. */
    @Test
    void explicitFixedWidthFrontendRoundTripsVerbatimThroughTheSubtype(@TempDir Path dir) throws Exception {
        Path toon = writeFixedWidthPipeline(dir, "fixedwidth", "");
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        nodeOfType(editable, "parser.fixedwidth");   // the retype happened
        Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

        assertEquals(raw, lowered, "strict lower over the original file reproduces it verbatim");
    }

    /**
     * The parser accepts TWO spellings of this frontend ({@code PipelineConfigParser#parseFixedWidth}),
     * so both must retype — and a lifted file keeps the spelling its author wrote. Canonicalising
     * {@code fixed_width} → {@code fixedwidth} on a read would rewrite a deployed file on save, which
     * is exactly the "nothing already deployed changes shape" rule P3a set.
     */
    @Test
    void theAlternateFixedWidthSpellingRetypesAndIsNotCanonicalised(@TempDir Path dir) throws Exception {
        Path toon = writeFixedWidthPipeline(dir, "fixed_width", "");
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        nodeOfType(editable, "parser.fixedwidth");
        Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

        assertEquals(raw, lowered);
        assertEquals("fixed_width", section(lowered, "parsing").get("frontend"),
                "the author's spelling survives — a read must not rewrite a deployed file");
    }

    /**
     * Binary fixed-width ({@code record: bytes}) lifts to the subtype too — the node TYPE spans the
     * format even though its layout comes from {@code processing.ingester_config} and only the
     * text mode can be authored in the drawer (operator decision, P3b).
     */
    @Test
    void aBinaryFixedWidthConfigAlsoLiftsToTheSubtype(@TempDir Path dir) throws Exception {
        Path toon = writeFixedWidthPipeline(dir, "fixedwidth", """
                    record: bytes
                    record_length: 24
                """);
        nodeOfType(PipelineEditable.toMap(PipelineConfig.load(toon.toString()), decode(toon)),
                "parser.fixedwidth");
    }

    /** A fixed-width node authored fresh from the palette gets the CANONICAL spelling stamped in. */
    @Test
    void aNewFixedWidthParserNodeIsStampedWithItsCanonicalFrontend() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser.fixedwidth", Map.of("parsing", Map.of(
                        "fixedwidth", Map.of("fields", List.of(Map.of("name", "ID", "start", 0, "length", 6)))))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);

        Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
        assertEquals("fixedwidth", parsing.get("frontend"),
                "the file must say the word the type means, or the next lift loses the identity");
    }

    /**
     * The contradiction check compares by SUBTYPE, not by string: {@code fixed_width} on a
     * {@code parser.fixedwidth} node is the same format and must NOT refuse, while a genuinely
     * different frontend still does.
     */
    @Test
    void eitherFixedWidthSpellingIsAcceptedButAForeignFrontendRefuses() {
        for (String spelling : List.of("fixedwidth", "fixed_width"))
            assertDoesNotThrow(() -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                    node("acq", "acquisition", Map.of("poll", "in")),
                    node("parse", "parser.fixedwidth", Map.of("parsing", Map.of("frontend", spelling))),
                    node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                    new LinkedHashMap<>(), true),
                    "'" + spelling + "' names this very format — it cannot contradict its own node");

        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                        node("acq", "acquisition", Map.of("poll", "in")),
                        node("parse", "parser.fixedwidth", Map.of("parsing", Map.of("frontend", "delimited"))),
                        node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                        new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.PARSER_FRONTEND_MISMATCH, ex.refusals().get(0).code());
        assertEquals("parse", ex.refusals().get(0).nodeId());
    }

    // ── P3c: the ASN.1 parser subtype ────────────────────────────────────────────────

    /**
     * An explicit {@code frontend: asn1} file round-trips verbatim through the subtype — the whole
     * {@code asn1:} block (inline grammar, root_type, strictness, segments) is carried, not read.
     */
    @Test
    void explicitAsn1FrontendRoundTripsVerbatimThroughTheSubtype(@TempDir Path dir) throws Exception {
        Path toon = writeAsn1Pipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        nodeOfType(editable, "parser.asn1");   // the retype happened
        Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

        assertEquals(raw, lowered, "strict lower over the original file reproduces it verbatim");
    }

    /** An ASN.1 node authored fresh from the palette gets {@code frontend: asn1} stamped in. */
    @Test
    void aNewAsn1ParserNodeIsStampedWithItsCanonicalFrontend() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser.asn1", Map.of("parsing", Map.of(
                        "asn1", Map.of("root_type", "Record")))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);

        Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
        assertEquals("asn1", parsing.get("frontend"),
                "the file must say the word the type means, or the next lift loses the identity");
    }

    /** {@code asn1} has one spelling; anything else on the node is a genuine contradiction. */
    @Test
    void aForeignFrontendOnAnAsn1NodeRefuses() {
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                        node("acq", "acquisition", Map.of("poll", "in")),
                        node("parse", "parser.asn1", Map.of("parsing", Map.of("frontend", "delimited"))),
                        node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                        new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.PARSER_FRONTEND_MISMATCH, ex.refusals().get(0).code());
        assertEquals("parse", ex.refusals().get(0).nodeId());
    }

    // ── P3d: the JSON and text/regex parser subtypes ─────────────────────────────────

    /**
     * Both remaining built-in frontends round-trip verbatim through their own subtype. Neither is
     * implicit — a config is JSON or text/regex only by saying so — so unlike delimited every such
     * file retypes, and there is no "keeps the plain type until its author opts in" caveat.
     */
    @Test
    void theRemainingBuiltinFrontendsRoundTripVerbatimThroughTheirSubtypes(@TempDir Path dir) throws Exception {
        for (String[] c : new String[][]{
                {"json", "parser.json", "  json:\n    format: newline\n"},
                {"text_regex", "parser.text_regex", "  text_regex:\n    pattern: \"(?<ID>\\\\w+) (?<EVENT_DATE>.+)\"\n"},
                // multiformat X3: xlsx joins the same never-implicit contract, its block verbatim.
                {"xlsx", "parser.xlsx", "  xlsx:\n    sheet: Data\n    header: true\n"}}) {
            Path toon = writeSimpleFrontendPipeline(dir, c[0], c[2]);
            Map<String, Object> raw = decode(toon);
            PipelineConfig cfg = PipelineConfig.load(toon.toString());

            Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
            nodeOfType(editable, c[1]);   // the retype happened
            Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

            assertEquals(raw, lowered, c[0] + ": strict lower over the original file reproduces it verbatim");
        }
    }

    /** A JSON / text-regex node authored fresh from the palette gets its frontend word stamped in. */
    @Test
    void aNewJsonOrTextRegexParserNodeIsStampedWithItsFrontend() {
        for (String frontend : List.of("json", "text_regex", "xlsx")) {
            Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                    node("acq", "acquisition", Map.of("poll", "in")),
                    node("parse", "parser." + frontend, Map.of("parsing", Map.of())),
                    node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                    new LinkedHashMap<>(), true);

            Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
            assertEquals(frontend, parsing.get("frontend"),
                    "the file must say the word the type means, or the next lift loses the identity");
        }
    }

    /** Each has ONE spelling; anything else on the node is a genuine contradiction. */
    @Test
    void aForeignFrontendOnAJsonOrTextRegexNodeRefuses() {
        for (String frontend : List.of("json", "text_regex")) {
            PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                    () -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                            node("acq", "acquisition", Map.of("poll", "in")),
                            node("parse", "parser." + frontend,
                                    Map.of("parsing", Map.of("frontend", "delimited"))),
                            node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                            new LinkedHashMap<>(), true));
            assertEquals(PipelineEditable.PARSER_FRONTEND_MISMATCH, ex.refusals().get(0).code());
            assertEquals("parse", ex.refusals().get(0).nodeId());
        }
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

    // ── AUTHOR-1 (a): the authored half of a transform.map node ─────────────────
    //
    // Until this slice `lower` had no branch for transform.map at all: an author typed a projection into
    // the map node's dialog, got `written: true`, and lost it. The five tests below pin the four halves
    // of the fix — the authored keys survive, the DERIVED ones still do not, and the three losses the
    // flat file genuinely cannot represent now say so by name.

    /** An authored {@code columns} list survives graph → file → graph. */
    @Test
    void authoredMapColumnsSurviveTheRoundTrip(@TempDir Path dir) throws Exception {
        List<Map<String, Object>> columns = List.of(
                Map.of("name", "ID_UPPER", "expr", "UPPER(ID)"),
                Map.of("name", "AMT_MAJOR", "expr", "AMT / 100"));
        PipelineGraph g = roundTrip(dir, node("map_authored", "transform.sql", Map.of("columns", columns)));

        PipelineNode map = g.nodes().stream()
                .filter(n -> BuiltinNodeType.TRANSFORM_SQL.type().equals(n.type()))
                .findFirst().orElseThrow(() -> new AssertionError("the lift always emits a map node"));
        assertEquals(columns, map.cfg("columns"),
                "the authored projection comes back on the map node; before this slice it was dropped");
    }

    /**
     * ⛔ The other half of the same rule: {@code schema} is put on the map node by the lift, so lowering
     * it would write a derived block back as authored config — and refusing it would refuse every
     * existing pipeline's save, since every lifted map node carries one.
     */
    @Test
    void theLiftDerivedSchemaIsStillNotLowered() {
        Map<String, Object> lowered = assertDoesNotThrow(() -> PipelineEditable.lower(
                graphWith(node("map", "transform.sql",
                        Map.of("schema", Map.of("mapping", Map.of("rules", List.of()))))),
                new LinkedHashMap<>(), true));
        assertNull(section(lowered, "processing").get("map"),
                "a derived schema is not authored config and must not become processing.map");
    }

    @Test
    void anUnknownMapKeyRefusesWithNamedCode() {
        PipelineCompileException ex = assertThrows(PipelineCompileException.class, () -> PipelineEditable.lower(
                graphWith(node("map", "transform.sql", Map.of("flavour", "vanilla"))),
                new LinkedHashMap<>(), true));
        assertEquals(1, ex.refusals().size(), ex.getMessage());
        assertEquals(PipelineEditable.UNSUPPORTED_MAP_KEY, ex.refusals().get(0).code());
        assertEquals("map", ex.refusals().get(0).nodeId());
        assertTrue(ex.refusals().get(0).message().contains("flavour"), ex.refusals().get(0).message());
    }

    /**
     * {@code RowShaper.columnsOf} checks {@code columns} first and never consults the schema when it
     * finds one — so an authored list would silently outrank a {@code mapping_file} the operator
     * declared on purpose, on the next production run. Refusing at authoring time is decision §2.3(a):
     * it needs no change to a live execution path.
     */
    @Test
    void authoredColumnsAlongsideADeclaredMappingFileRefuse() {
        PipelineGraph g = new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser", Map.of("schema_file", "s.toon", "mapping_file", "m.toon")),
                node("map", "transform.sql", Map.of("columns", List.of(Map.of("name", "A", "expr", "1")))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of());

        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertEquals(1, ex.refusals().size(), ex.getMessage());
        assertEquals(PipelineEditable.MAPPING_CONFLICT, ex.refusals().get(0).code());
        assertEquals("map", ex.refusals().get(0).nodeId());
    }

    /** One {@code processing.map} serves every branch's map node, so drift cannot be represented. */
    @Test
    void mapNodesCarryingDifferentAuthoredConfigRefuse() {
        PipelineGraph g = graphWith(
                node("map_a", "transform.sql", Map.of("columns", List.of(Map.of("name", "A", "expr", "1")))),
                node("map_b", "transform.sql", Map.of("columns", List.of(Map.of("name", "B", "expr", "2")))));

        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g, new LinkedHashMap<>(), true));
        assertEquals(1, ex.refusals().size(), ex.getMessage());
        assertEquals(PipelineEditable.MULTI_MAP_CONFIG, ex.refusals().get(0).code());
        assertEquals("map_b", ex.refusals().get(0).nodeId());
    }

    /**
     * ⛔ A map node is not a chain step, and the parser accepts {@code processing.map} beside
     * {@code steps:} — so a chain that outgrows the singular keys must not take the authored projection
     * down with it. (The three singular transform keys around it ARE removed; see the ⚠ in {@code lower}.)
     */
    @Test
    void anAuthoredMapSurvivesAChainThatLowersToSteps() {
        Map<String, Object> lowered = assertDoesNotThrow(() -> PipelineEditable.lower(
                graphWith(node("map", "transform.sql",
                                Map.of("columns", List.of(Map.of("name", "A", "expr", "1")))),
                        node("dd1", "transform.dedup", Map.of("keys", List.of("a"))),
                        node("dd2", "transform.dedup", Map.of("keys", List.of("b")))),
                new LinkedHashMap<>(), true));

        assertNotNull(lowered.get("steps"), "two dedups cannot fit the singular key — this is a steps: file");
        assertNotNull(section(lowered, "processing").get("map"),
                "processing.map is not a chain step and must survive the steps: rewrite");
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

    /**
     * The fixed-width twin of {@link #writeParsingBlockPipeline}. {@code frontend} is the spelling under
     * test (both {@code fixedwidth} and {@code fixed_width} are accepted by the parser) and
     * {@code extraFw} is appended inside the {@code fixedwidth:} block, already indented to it — the
     * binary case adds {@code record}/{@code record_length} there.
     *
     * <p>⚠ The two {@code raw.fields[].selector}s must index declared slices or the load fails in
     * {@code PipelineConfigParser#validateFixedWidthSelectors} — keep the slice count ≥ the selectors.
     */
    private static Path writeFixedWidthPipeline(Path dir, String frontend, String extraFw) throws Exception {
        Path sf = dir.resolve("fw_schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: fw_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, "0", VARCHAR
                    EVENT_DATE, "1", DATE
                mapping:
                  canonicalName: fw_data
                  rawName: fw_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: FIXED_WIDTH
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                collector:
                  connector: local
                parsing:
                  frontend: %3$s
                  fixedwidth:
                %4$s    fields[2]{name,start,length}:
                      ID, 0, 6
                      EVENT_DATE, 6, 10
                processing:
                  schema_file: %2$s
                """.formatted(base, sf.toString().replace('\\', '/'), frontend, extraFw);
        Path p = dir.resolve("fixed_width_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    /**
     * The JSON / text-regex twin of {@link #writeFixedWidthPipeline}: a frontend whose whole grammar is
     * one nested sub-block, passed in already indented to {@code parsing:}. Selectors are column NAMES
     * here (not the positional indices the delimited/fixed-width fixtures use) because both frontends
     * hand the reader named columns.
     */
    private static Path writeSimpleFrontendPipeline(Path dir, String frontend, String block) throws Exception {
        Path sf = dir.resolve(frontend + "_schema.toon");
        Files.writeString(sf, """
                partitionKey: EVENT_DATE
                raw:
                  name: %1$s_data
                  format: CSV
                  fields[2]{name,selector,type}:
                    ID, ID, VARCHAR
                    EVENT_DATE, EVENT_DATE, DATE
                mapping:
                  canonicalName: %1$s_data
                  rawName: %1$s_data
                  rules[2]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                    EVENT_DATE, EVENT_DATE, DIRECT
                """.formatted(frontend));
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: %5$s
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                collector:
                  connector: local
                parsing:
                  frontend: %3$s
                %4$sprocessing:
                  schema_file: %2$s
                """.formatted(base, sf.toString().replace('\\', '/'), frontend, block,
                        frontend.toUpperCase(java.util.Locale.ROOT));
        Path p = dir.resolve(frontend + "_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    /**
     * The ASN.1 twin of {@link #writeFixedWidthPipeline}. The grammar is INLINE X.680 text on one
     * line (whitespace-insensitive, and a single quoted scalar sidesteps TOON multi-line questions);
     * the segment schema file must exist because the load resolves {@code asn1.segments} eagerly.
     */
    private static Path writeAsn1Pipeline(Path dir) throws Exception {
        Path sf = dir.resolve("record_schema.toon");
        Files.writeString(sf, """
                partitionKey: ID
                raw:
                  name: record
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID, id, VARCHAR
                mapping:
                  canonicalName: record
                  rawName: record
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: ASN1_CDR
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                collector:
                  connector: local
                parsing:
                  frontend: asn1
                  asn1:
                    grammar: "CDR DEFINITIONS ::= BEGIN Record ::= SEQUENCE { id [0] IA5String } END"
                    root_type: Record
                    strictness: BER
                    segments:
                      Record: %2$s
                processing:
                  threads: 1
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("asn1_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    /**
     * The generic-plugin twin of {@link #writeAsn1Pipeline}: {@code frontend: plugin} wires the SAME
     * ingester/ingester_config/segments triple that framework already carried before this node-type
     * family existed — only {@code frontend: plugin} needs to be said explicitly for it to retype.
     * The ingester class need not exist on the classpath: config load stores the FQCN string and
     * validates the referenced segment schemas, never the class itself.
     */
    private static Path writePluginPipeline(Path dir) throws Exception {
        Path sf = dir.resolve("plugin_record_schema.toon");
        Files.writeString(sf, """
                partitionKey: ID
                raw:
                  name: record
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID, id, VARCHAR
                mapping:
                  canonicalName: record
                  rawName: record
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: PLUGIN_FEED
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                collector:
                  connector: local
                parsing:
                  frontend: plugin
                  plugin:
                    ingester: com.example.acme.AcmeFeedIngester
                    ingester_config:
                      mode: strict
                    segments:
                      Record: %2$s
                processing:
                  threads: 1
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("plugin_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    // ── P3d slice D: the custom-plugin parser subtype ────────────────────────────────

    /**
     * An explicit {@code frontend: plugin} file round-trips verbatim through the subtype — the whole
     * {@code plugin:} block (ingester FQCN, ingester_config, segments) is carried, not read, exactly
     * the mechanism the plain {@code parser} type already used for this frontend.
     */
    @Test
    void explicitPluginFrontendRoundTripsVerbatimThroughTheSubtype(@TempDir Path dir) throws Exception {
        Path toon = writePluginPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());

        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        nodeOfType(editable, "parser.plugin");   // the retype happened
        Map<String, Object> lowered = PipelineEditable.lower(PipelineCodec.fromMap(editable), raw, true);

        assertEquals(raw, lowered, "strict lower over the original file reproduces it verbatim");
    }

    /** A plugin node authored fresh from the palette gets {@code frontend: plugin} stamped in. */
    @Test
    void aNewPluginParserNodeIsStampedWithItsFrontend() {
        Map<String, Object> lowered = PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                node("acq", "acquisition", Map.of("poll", "in")),
                node("parse", "parser.plugin", Map.of("parsing", Map.of(
                        "plugin", Map.of("ingester", "com.example.acme.AcmeFeedIngester")))),
                node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                new LinkedHashMap<>(), true);

        Map<?, ?> parsing = (Map<?, ?>) lowered.get("parsing");
        assertEquals("plugin", parsing.get("frontend"),
                "the file must say the word the type means, or the next lift loses the identity");
    }

    /** {@code plugin} has one spelling; anything else on the node is a genuine contradiction. */
    @Test
    void aForeignFrontendOnAPluginNodeRefuses() {
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(new PipelineGraph("x", true, List.of(
                        node("acq", "acquisition", Map.of("poll", "in")),
                        node("parse", "parser.plugin", Map.of("parsing", Map.of("frontend", "delimited"))),
                        node("sink", "sink.persistent", Map.of("database", "db"))), List.of()),
                        new LinkedHashMap<>(), true));
        assertEquals(PipelineEditable.PARSER_FRONTEND_MISMATCH, ex.refusals().get(0).code());
        assertEquals("parse", ex.refusals().get(0).nodeId());
    }

    /**
     * Unlike the built-in subtypes, {@code parser.plugin} DOES take an {@code ingester/} ref — but only
     * because {@link PipelineLift} presents the config's own {@code plugin.ingester} FQCN back as a
     * derived {@code use:}, exactly as it always did for the plain {@code parser} type. It must be
     * accepted (never refused as unhomed) and never actually lowered from — the class comes from the
     * config key, not the binding — while an unrelated ref on the same node still refuses.
     */
    @Test
    void acceptsTheDerivedIngesterRefButRefusesAnUnrelatedBinding(@TempDir Path dir) throws Exception {
        Path toon = writePluginPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        Map<String, Object> editable = PipelineEditable.toMap(cfg, raw);
        PipelineGraph g = PipelineCodec.fromMap(editable);
        PipelineNode parser = g.nodes().stream().filter(n -> "parser.plugin".equals(n.type()))
                .findFirst().orElseThrow();
        assertEquals("ingester/com.example.acme.AcmeFeedIngester", parser.use(),
                "the lift presents the config's own ingester FQCN as a derived use:");
        assertDoesNotThrow(() -> PipelineEditable.lower(g, raw, true));

        PipelineNode bad = new PipelineNode(parser.id(), parser.type(), parser.name(),
                parser.description(), parser.config(), "transform/nope");
        PipelineGraph g2 = new PipelineGraph(g.name(), g.active(),
                g.nodes().stream().map(n -> n.id().equals(bad.id()) ? bad : n).toList(), g.edges());
        PipelineCompileException ex = assertThrows(PipelineCompileException.class,
                () -> PipelineEditable.lower(g2, raw, true));
        assertEquals(PipelineEditable.UNSUPPORTED_BINDING, ex.refusals().get(0).code());
    }

    /**
     * The legacy shape that predates both {@code parser.asn1} and {@code parser.plugin}: a bare
     * {@code processing.ingester}/{@code segments} pair with no {@code parsing.frontend} literal at
     * all. {@link PipelineEditable#subtypeForFrontend} is explicit-only, so the node never retypes and
     * stays plain {@code parser} — yet {@link PipelineLift} still presents the class as a derived
     * {@code ingester/<fqcn>} ref, exactly as it does for the two named subtypes. It must read as
     * DERIVED (not an unhomed authored binding), or a plain pipeline holding this legacy shape would
     * fail validate/dry-run with UNKNOWN_USE_KIND despite never having been touched.
     */
    @Test
    void thePlainParsersLegacyIngesterRefIsDerivedNotAuthored(@TempDir Path dir) throws Exception {
        Path toon = writeLegacyIngesterPipeline(dir);
        Map<String, Object> raw = decode(toon);
        PipelineConfig cfg = PipelineConfig.load(toon.toString());
        PipelineGraph g = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));

        PipelineNode parser = g.nodes().stream().filter(n -> n.type().startsWith("parser"))
                .findFirst().orElseThrow();
        assertEquals("parser", parser.type(), "no frontend literal ⇒ no retype; it stays the plain type");
        assertEquals("ingester/com.example.acme.LegacyIngester", parser.use(),
                "the lift synthesizes the ref from the class key regardless of the type");

        PipelineValidator.Result r = PipelineValidator.validate(g);
        assertFalse(r.issues().stream().anyMatch(i -> i.code().equals(PipelineValidator.UNKNOWN_USE_KIND)),
                () -> "an untouched legacy pipeline must validate, got " + r.issues());
        assertDoesNotThrow(() -> PipelineEditable.lower(g, raw, true));
    }

    /** The pre-P3d legacy shape: {@code processing.ingester} + {@code segments}, no {@code frontend}. */
    private static Path writeLegacyIngesterPipeline(Path dir) throws Exception {
        Path sf = dir.resolve("legacy_record_schema.toon");
        Files.writeString(sf, """
                partitionKey: ID
                raw:
                  name: record
                  format: CSV
                  fields[1]{name,selector,type}:
                    ID, id, VARCHAR
                mapping:
                  canonicalName: record
                  rawName: record
                  rules[1]{targetColumn,sourceExpression,transformType}:
                    ID, ID, DIRECT
                """);
        String base = dir.toString().replace('\\', '/');
        String toon = """
                name: LEGACY_FEED
                active: true
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                collector:
                  connector: local
                processing:
                  threads: 1
                  ingester: com.example.acme.LegacyIngester
                  segments:
                    Record: %2$s
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("legacy_pipeline.toon");
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
                  map:
                    columns[2]{name,expr}:
                      ID_UPPER, "UPPER(ID)"
                      EVENT_YEAR, "YEAR(EVENT_DATE)"
                """.formatted(base, sf.toString().replace('\\', '/'));
        Path p = dir.resolve("editable_rich_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    // ── Phase 4 S4a: processing.disabled_steps ↔ node-level enabled ─────────────────────

    /**
     * The flat file's ONE home for per-Step {@code enabled:}: the lift overlays the flag onto the
     * named node, and a lower derives the list back from node state — round-trip, no per-branch
     * enumerated-key edits. The list is recomputed WHOLESALE (node order), so a re-enable clears
     * its entry even on a lenient (draft) lower.
     */
    @Test
    void disabledStepsOverlayAndDeriveRoundTrip(@TempDir Path dir) throws Exception {
        Path toon = writeRichPipeline(dir);
        Map<String, Object> raw = decode(toon);
        // author the disable list onto the rich fixture: the parse node id is 'parse' (PipelineLift)
        @SuppressWarnings("unchecked")
        Map<String, Object> processing = (Map<String, Object>) raw.get("processing");
        processing.put("disabled_steps", List.of("parse"));
        // an ACTIVE pipeline with a disabled step is refused at prepare() (the S4a gate, pinned in
        // StepDisableArmingTest) — the round-trip under test is the inactive-draft shape
        raw.put("active", false);
        Path authored = dir.resolve("disabled_pipeline.toon");
        Files.writeString(authored, com.gamma.config.io.ConfigCodec.toToon(raw));
        PipelineConfig cfg = PipelineConfig.load(authored.toString());

        // lift overlay: the named node reports disabled, everything else untouched
        PipelineGraph g = PipelineLift.lift(cfg);
        assertFalse(g.byId().get("parse").enabled(), "the overlay reaches the lifted node");
        assertTrue(g.byId().get("acq").enabled(), "unnamed nodes stay enabled");

        // lower derives the list back from node state — the round-trip home
        Map<String, Object> lowered = PipelineEditable.lower(
                PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw)), raw, true);
        assertEquals(List.of("parse"),
                ((Map<?, ?>) lowered.get("processing")).get("disabled_steps"));

        // re-enable: a graph with every node enabled clears the entry, lenient lower included
        // (node configs are immutable — rebuild the disabled node without its flag)
        PipelineGraph withFlag = PipelineCodec.fromMap(PipelineEditable.toMap(cfg, raw));
        List<PipelineNode> reEnabled = withFlag.nodes().stream().map(n -> {
            if (n.enabled()) return n;
            Map<String, Object> c = new LinkedHashMap<>(n.config());
            c.remove("enabled");
            return new PipelineNode(n.id(), n.type(), n.name(), n.description(), c, n.use());
        }).toList();
        Map<String, Object> cleared = PipelineEditable.lower(
                new PipelineGraph(withFlag.name(), withFlag.active(), reEnabled, withFlag.edges()),
                lowered, false);
        assertFalse(((Map<?, ?>) cleared.get("processing")).containsKey("disabled_steps"),
                "a re-enable clears the list even on a draft (lenient) save");
    }
}
