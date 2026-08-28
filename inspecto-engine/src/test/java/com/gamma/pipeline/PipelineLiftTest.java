package com.gamma.pipeline;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.pipeline.exec.BatchGraphRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.etl.TestConfigs.csv;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lift coverage ({@link PipelineLift}): the two shipped shapes that matter most — a single-schema
 * linear pipeline and a multi-schema {@code selector} fan-out. Asserts node types, the data chain,
 * the dedup prefix (G2), the route branches + {@code unmatched} quarantine (G3), and that typed
 * config is carried verbatim (lossless lift).
 */
class PipelineLiftTest {

    @Test
    void liftsSingleSchemaToLinearChain(@TempDir Path dir) throws Exception {
        // TestConfigs builds a single-schema CSV pipeline with duplicate_check enabled (path mode).
        PipelineConfig cfg = csv(dir, PipelineConfigBatchTest.miniSchema()).load();
        PipelineGraph g = PipelineLift.lift(cfg);

        assertEquals(cfg.identity().pipelineName(), g.name());
        assertTrue(g.active());
        assertEquals(List.of("acq"), ids(g.entryNodes()));

        // node types
        assertType(g, "acq", "acquisition");
        assertType(g, "parse", "parser");
        assertType(g, "map", "transform.map");
        assertType(g, "sink", "sink.persistent");

        // NEITHER file-grain dedup is a node: fingerprint folded onto acquisition 2026-08-04, marker
        // followed in P5-a — both are decided in the poll cycle, before anything is parsed.
        // Single schema ⇒ no gap / no quarantine either.
        assertTrue(g.node("dedup_fingerprint").isEmpty());
        assertTrue(g.node("dedup_marker").isEmpty());
        assertTrue(g.node("gap").isEmpty());
        assertTrue(g.node("quarantine").isEmpty());

        // …and its keys ride acquisition instead (duplicate_check enabled, path mode)
        PipelineNode acq = g.node("acq").orElseThrow();
        assertEquals(true, acq.cfg("duplicate_check"));
        assertNotNull(acq.cfg("retention_days"));

        // the data chain acq → parse → map → sink
        assertEquals(List.of("parse"), ids2(g.dataEdgesFrom("acq")));
        assertEquals(List.of("map"), ids2(g.dataEdgesFrom("parse")));
        assertEquals(List.of("sink"), ids2(g.dataEdgesFrom("map")));

        // lossless: acquisition carries typed source sub-records; parser carries csv + schema
        assertNotNull(g.node("acq").orElseThrow().cfg("guarantee"));
        assertNotNull(g.node("acq").orElseThrow().cfg("stability"));
        assertFalse(g.node("acq").orElseThrow().hasUse());          // local FS ⇒ no connection ref
        assertNotNull(g.node("parse").orElseThrow().cfg("csv"));
        assertNotNull(g.node("parse").orElseThrow().cfg("schema"));

        // the sink declares a data store (single-schema ⇒ the schema's canonicalName "mini")
        assertEquals("mini", g.node("sink").orElseThrow().cfg("store"));
        assertEquals(Set.of("mini"), PipelineStores.produced(g));
        // the lifted sink is named after the store it produces — the business object (§3.1)
        assertEquals("mini", g.node("sink").orElseThrow().name());
        // a legacy sink rests on disk (persistent), so the deletion fence treats it as a real store
        assertTrue(PipelineStores.producedStores(g).get(0).restsOnDisk());
    }

    @Test
    void liftsSinksListToADataFedFanOut() throws Exception {
        // A plural sinks: config (constructed via fromMap — a 2-destination config is liftable but not
        // yet runnable, so it never reaches load()/prepare()). The map must fan out to one persistent sink
        // per destination, each keeping its own database — exactly what the dormant engagement predicate fires on.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "FANOUT_ETL");
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        m.put("sinks", List.of(
                Map.of("database", "out_hot", "format", "parquet"),
                Map.of("database", "out_cold", "format", "csv")));
        PipelineConfig cfg = PipelineConfig.fromMap(m);
        assertEquals(2, cfg.sinks().size());

        PipelineGraph g = PipelineLift.lift(cfg);

        // the map fans out to two persistent sinks, each fed by a data edge
        List<String> sinkIds = ids2(g.dataEdgesFrom("map"));
        assertEquals(2, sinkIds.size(), "map fans out to one sink per destination: " + sinkIds);
        for (String id : sinkIds) assertType(g, id, "sink.persistent");

        // each sink carries its own destination database (the per-sink lift, not one shared sinkBase)
        Set<String> dbs = Set.of(
                String.valueOf(g.node(sinkIds.get(0)).orElseThrow().cfg("database")),
                String.valueOf(g.node(sinkIds.get(1)).orElseThrow().cfg("database")));
        assertEquals(Set.of("out_hot", "out_cold"), dbs, "each sink keeps its own destination database");

        // Refined 2026-08-26 (arming plan S1): this pin used to assert engages == true, written when
        // "a 2-destination config is liftable but not yet runnable" — STALE since sinks: shipped
        // (2026-08-02) as FLAT-path fan-out in writeAndTrace. Two plain-data-fed sinks are N
        // destinations of ONE branch; diverting them to the runner would drop writeAndTrace's
        // reference-versioning and decision rules. Only a second route:<key> relation is a second branch.
        assertFalse(BatchGraphRunner.engages(g),
                "multi-destination is flat-path fan-out (one branch, N destinations) — never the runner");
    }

    @Test
    void liftsSelectorToRouteFanOut(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        String schema = PipelineConfigBatchTest.miniSchema();
        Path sa = dir.resolve("a.toon");
        Path sb = dir.resolve("b.toon");
        Files.writeString(sa, schema);
        Files.writeString(sb, schema);
        String d = dir.toString().replace("\\", "/");

        String toon = """
                name: SEL_ETL
                active: true
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  quarantine: %s/q
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*"
                  duplicate_check:
                    enabled: false
                  schemas[2]{column_count,file_pattern,schema_file,table}:
                    3, "glob:**/*a*", "%s", alpha
                    4, "", "%s", beta
                """.formatted(d, d, d, sa.toString().replace("\\", "/"), sb.toString().replace("\\", "/"));
        Path pipe = dir.resolve("sel_pipeline.toon");
        Files.writeString(pipe, toon);

        PipelineConfig cfg = PipelineConfig.load(pipe.toString());
        PipelineGraph g = PipelineLift.lift(cfg);

        assertEquals(List.of("acq"), ids(g.entryNodes()));
        assertEquals(List.of("parse"), ids2(g.dataEdgesFrom("acq")));
        // duplicate_check off ⇒ the acquisition node carries no marker keys at all (P5-a). The toggle
        // is absent, not false: an absent toggle is what lets a legacy marker node still be read.
        for (String k : List.of("duplicate_check", "marker_extension", "retention_days", "markers_dir"))
            assertNull(g.node("acq").orElseThrow().cfg(k), k + " must not ride a dedup-off acquisition node");
        assertNotNull(g.node("parse").orElseThrow().cfg("selector"));   // selector carried (G3)

        // one route branch per schema, each to its own map → sink, plus unmatched → quarantine
        assertTrue(hasEdge(g, "parse", PipelineRel.route("alpha"), "map_alpha"));
        assertTrue(hasEdge(g, "parse", PipelineRel.route("beta"), "map_beta"));
        assertTrue(hasEdge(g, "map_alpha", PipelineRel.DATA, "sink_alpha"));
        assertTrue(hasEdge(g, "map_beta", PipelineRel.DATA, "sink_beta"));
        assertTrue(hasEdge(g, "parse", PipelineRel.UNMATCHED, "quarantine"));

        assertType(g, "quarantine", "sink.persistent");
        assertEquals("alpha", g.node("sink_alpha").orElseThrow().cfg("table"));
        assertEquals("beta", g.node("sink_beta").orElseThrow().cfg("table"));

        // each branch declares its produced store; the quarantine sink has none
        assertEquals(Set.of("alpha", "beta"), PipelineStores.produced(g));
    }

    @Test
    void liftsASchemaLessDraftWithoutThrowing() throws Exception {
        // v5.1.0 allows an inactive draft with no schema_file/schemas/segments at all (stream
        // onboarding's D3 draft lifecycle — Schema & Mapping may not be authored yet). "View as
        // graph" (P1 §7 flagged gap) must tolerate that all-null Schemas, not NPE.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "draft_etl");
        m.put("active", false);
        m.put("dirs", Map.of("poll", "in", "database", "out"));
        m.put("processing", Map.of("threads", 1));
        PipelineConfig cfg = PipelineConfig.fromMap(m);

        PipelineGraph g = PipelineLift.lift(cfg);

        assertFalse(g.active());
        assertType(g, "acq", "acquisition");
        assertType(g, "parse", "parser");
        assertType(g, "map", "transform.map");
        assertType(g, "sink", "sink.persistent");
        assertNull(g.node("parse").orElseThrow().cfg("schema"));
        // no schema ⇒ the sink falls back to the pipeline's own identity as its store name
        assertEquals(cfg.identity().pipelineName(), g.node("sink").orElseThrow().cfg("store"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void assertType(PipelineGraph g, String id, String type) {
        assertEquals(type, g.node(id).orElseThrow(() -> new AssertionError("missing node " + id)).type());
    }

    private static boolean hasEdge(PipelineGraph g, String from, String rel, String to) {
        return g.edges().stream().anyMatch(e -> e.from().equals(from) && e.rel().equals(rel) && e.to().equals(to));
    }

    private static List<String> ids(List<PipelineNode> ns) {
        return ns.stream().map(PipelineNode::id).toList();
    }

    private static List<String> ids2(List<PipelineEdge> es) {
        return es.stream().map(PipelineEdge::to).toList();
    }

    /**
     * Pins {@code StepDisableArming.parkableSinkIds}' hand-mirrored id grammar VERBATIM against a
     * real lift (Phase 4 S4b): the sink node ids the gate allows must be exactly the sinks the
     * lifted graph feeds by {@code route:*} edges — extend the grammar in both places together.
     */
    @Test
    void parkableSinkIdsMatchTheLiftedGraph(@TempDir Path dir) throws Exception {
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String d = dir.toString().replace("\\", "/");
        Path pipe = dir.resolve("route_pipeline.toon");
        Files.writeString(pipe, """
                name: ROUTE_LIFT
                active: false
                dirs:
                  poll: %1$s/inbox
                  database: %1$s/db
                output:
                  format: CSV
                sinks[2]{database,format}:
                  "%1$s/db_emea",CSV
                  "%1$s/db_apac",CSV
                route:
                  mode: case
                  default: apac
                  branches[2]{key,where,database}:
                    emea,"ID LIKE 'E%%'","%1$s/db_emea"
                    apac,"ID LIKE 'A%%'","%1$s/db_apac"
                processing:
                  threads: 1
                  schema_file: "%2$s"
                """.formatted(d, schema.toString().replace("\\", "/")));
        PipelineConfig cfg = PipelineConfig.load(pipe.toString());

        PipelineGraph g = PipelineLift.lift(cfg);
        java.util.Set<String> routeFedSinkIds = new java.util.LinkedHashSet<>();
        for (PipelineEdge e : g.edges())
            if (e.rel().startsWith("route:")) routeFedSinkIds.add(e.to());

        List<String> sinkDbs = cfg.sinks().stream().map(PipelineConfig.Sink::database).toList();
        assertEquals(routeFedSinkIds,
                new java.util.LinkedHashSet<>(
                        com.gamma.etl.StepDisableArming.parkableSinkIds(cfg.routeConfig(), sinkDbs)),
                "the gate's mirrored id grammar must match the lift's route-fed sink ids");
        assertFalse(routeFedSinkIds.isEmpty(), "the fixture actually routes");
    }
}
